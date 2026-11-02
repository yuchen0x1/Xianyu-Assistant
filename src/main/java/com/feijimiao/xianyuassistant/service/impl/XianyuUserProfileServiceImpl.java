package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.adapter.xianyu.XianyuAdapter;
import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.service.XianyuUserProfileService;
import com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 闲鱼账号资料抓取实现。
 * 优先取个人主页接口（mtop.idle.web.user.page.head），缺失时回退首页导航接口
 * （mtop.idle.web.user.page.nav）。对响应做限深递归字段查找，避免依赖不确定的
 * 响应层级；失败时不抛异常，由调用方决定回退行为。
 */
@Slf4j
@Service
public class XianyuUserProfileServiceImpl implements XianyuUserProfileService {

    private static final String PERSONAL_PAGE_HEAD_API = "mtop.idle.web.user.page.head";
    private static final String HOME_NAV_API = "mtop.idle.web.user.page.nav";

    private static final Set<String> NICKNAME_KEYS = Set.of("displayName", "nickName", "nickname", "nick", "userNickName");
    private static final Set<String> AVATAR_KEYS = Set.of("avatarUrl", "avatar", "headPic", "userHeadPic");
    private static final Set<String> CREDIT_KEYS = Set.of("creditScore");
    private static final Set<String> SOLD_KEYS = Set.of("soldCount");
    private static final Set<String> FOLLOWERS_KEYS = Set.of("followers");
    private static final Set<String> FOLLOWING_KEYS = Set.of("following");
    private static final int SEARCH_DEPTH = 8;

    @Autowired
    private XianyuAdapter xianyuAdapter;

    @Autowired
    private AccountService accountService;

    @Override
    public boolean refreshAccountProfile(Long accountId) {
        try {
            String cookies = accountService.getCookieByAccountId(accountId);
            if (cookies == null || cookies.isBlank()) {
                log.info("无有效Cookie，跳过闲鱼资料刷新: accountId={}", accountId);
                return false;
            }

            Map<String, Object> headResp = callUserApi(accountId, PERSONAL_PAGE_HEAD_API,
                    Map.<String, Object>of("self", true), cookies,
                    Map.of("Referer", XianyuProtocolProfile.PERSONAL_PAGE_REFERER),
                    Map.of("spm_cnt", XianyuProtocolProfile.PERSONAL_PAGE_SPM_CNT));
            String nickname = headResp != null ? findString(headResp, NICKNAME_KEYS) : null;
            String avatarUrl = headResp != null ? findHttpUrl(headResp, AVATAR_KEYS) : null;
            Integer creditScore = headResp != null ? findInteger(headResp, CREDIT_KEYS) : null;
            Integer soldCount = headResp != null ? findInteger(headResp, SOLD_KEYS) : null;
            Integer followers = headResp != null ? findInteger(headResp, FOLLOWERS_KEYS) : null;
            Integer following = headResp != null ? findInteger(headResp, FOLLOWING_KEYS) : null;

            Map<String, Object> navResp = null;
            if ((nickname == null || soldCount == null) && headResp != null) {
                navResp = callUserApi(accountId, HOME_NAV_API,
                        Map.of(), cookies, Map.of(),
                        Map.of("spm_cnt", "a21ybx.home.0.0"));
                if (navResp != null) {
                    if (nickname == null) {
                        nickname = findString(navResp, NICKNAME_KEYS);
                    }
                    if (avatarUrl == null) {
                        avatarUrl = findHttpUrl(navResp, AVATAR_KEYS);
                    }
                    if (creditScore == null) {
                        creditScore = findInteger(navResp, CREDIT_KEYS);
                    }
                    if (soldCount == null) {
                        soldCount = findInteger(navResp, SOLD_KEYS);
                    }
                    if (followers == null) {
                        followers = findInteger(navResp, FOLLOWERS_KEYS);
                    }
                    if (following == null) {
                        following = findInteger(navResp, FOLLOWING_KEYS);
                    }
                }
            }

            if (nickname == null && avatarUrl == null && creditScore == null
                    && soldCount == null && followers == null && following == null) {
                log.info("用户接口响应未包含资料字段: accountId={}, headKeys={}, navKeys={}",
                        accountId,
                        headResp != null ? headResp.keySet() : null,
                        navResp != null ? navResp.keySet() : null);
                return false;
            }

            XianyuAccount account = accountService.getById(accountId);
            if (account == null) {
                log.warn("账号不存在，未更新闲鱼资料: accountId={}", accountId);
                return false;
            }
            if (nickname != null) {
                account.setNickname(nickname);
            }
            if (avatarUrl != null) {
                account.setAvatarUrl(avatarUrl);
            }
            if (creditScore != null) {
                account.setCreditScore(creditScore);
            }
            if (soldCount != null) {
                account.setSoldCount(soldCount);
            }
            if (followers != null) {
                account.setFollowers(followers);
            }
            if (following != null) {
                account.setFollowing(following);
            }
            if (!accountService.updateAccount(account)) {
                log.warn("闲鱼资料写入失败: accountId={}", accountId);
                return false;
            }
            log.info("闲鱼资料刷新成功: accountId={}, hasNickname={}, hasAvatar={}, creditScore={}, soldCount={}, followers={}, following={}",
                    accountId, nickname != null, avatarUrl != null, creditScore, soldCount, followers, following);
            return true;
        } catch (Exception e) {
            log.warn("刷新闲鱼资料异常: accountId={}, errorType={}",
                    accountId, e.getClass().getSimpleName());
            return false;
        }
    }

    private Map<String, Object> callUserApi(Long accountId, String apiName,
                                            Map<String, Object> data, String cookies,
                                            Map<String, String> headers,
                                            Map<String, String> queryParams) {
        try {
            XianyuApiCallUtils.ApiCallResult result = xianyuAdapter.callApi(
                    accountId, apiName, new HashMap<>(data), cookies, headers, queryParams);
            if (!result.isSuccess()) {
                log.info("闲鱼用户接口调用失败: accountId={}, api={}, errorType={}",
                        accountId, apiName,
                        result.getErrorMessage() != null ? "REMOTE_FAILED" : "UNKNOWN");
                return null;
            }
            return result.extractData();
        } catch (Exception e) {
            log.warn("闲鱼用户接口调用异常: accountId={}, api={}, errorType={}",
                    accountId, apiName, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 在响应嵌套结构中按字段名候选集查找字符串值。
     */
    private String findString(Map<String, Object> node, Set<String> keys) {
        return findValue(node, keys, 0);
    }

    private String findHttpUrl(Map<String, Object> node, Set<String> keys) {
        String value = findValue(node, keys, 0);
        return value != null && value.startsWith("http") ? value : null;
    }

    private Integer findInteger(Map<String, Object> node, Set<String> keys) {
        String value = findValue(node, keys, 0);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String findValue(Map<String, Object> node, Set<String> keys, int depth) {
        if (node == null || depth > SEARCH_DEPTH) {
            return null;
        }
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (keys.contains(entry.getKey()) && entry.getValue() != null
                    && !entry.getValue().toString().isBlank()) {
                return entry.getValue().toString().trim();
            }
        }
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) nested;
                String found = findValue(nestedMap, keys, depth + 1);
                if (found != null) {
                    return found;
                }
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> nested) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> nestedMap = (Map<String, Object>) nested;
                        String found = findValue(nestedMap, keys, depth + 1);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }
        }
        return null;
    }
}
