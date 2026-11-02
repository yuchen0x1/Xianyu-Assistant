package com.feijimiao.xianyuassistant.backup.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;


@Slf4j
@Component
public class AccountBackupHandler implements DataBackupHandler {

    private static java.time.LocalDateTime parseTime(Object value) {
        if (value == null) return null;
        String str = value.toString();
        if (str.isBlank()) return null;
        // Try standard storage format first, then ISO
        try {
            return java.time.LocalDateTime.parse(str, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            try {
                return java.time.LocalDateTime.parse(str);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private XianyuCookieMapper cookieMapper;

    @Override
    public String getModuleKey() {
        return "account";
    }

    @Override
    public String getModuleName() {
        return "闲鱼账号";
    }

    @Override
    public Set<String> getRequiredImportListKeys() {
        return Set.of("accounts", "cookies");
    }

    @Override
    public Map<String, Object> exportData() {
        return exportData(new java.util.LinkedHashMap<>());
    }

    /** 带导出上下文的导出：includeSecrets=true 时才包含明文 Cookie/Token。 */
    public Map<String, Object> exportData(Map<String, Object> context) {
        List<XianyuAccount> accounts = accountMapper.selectList(null);
        List<XianyuCookie> cookies = cookieMapper.selectList(null);

        boolean includeSecrets = com.feijimiao.xianyuassistant.backup.DataBackupHandler.exportSecrets(context);

        List<Map<String, Object>> accountList = new ArrayList<>();
        for (XianyuAccount account : accounts) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", account.getUnb());
            map.put("accountNote", account.getAccountNote());
            map.put("deviceId", account.getDeviceId());
            map.put("status", account.getStatus());
            map.put("websocketEnabled", account.getWebsocketEnabled());
            accountList.add(map);
        }

        List<Map<String, Object>> cookieList = new ArrayList<>();
        for (XianyuCookie cookie : cookies) {
            XianyuAccount account = accountMapper.selectById(cookie.getXianyuAccountId());
            if (account == null) continue;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", account.getUnb());
            // 默认不导出明文 Cookie/Token，避免备份包泄露已解密凭据。
            map.put("cookieText", includeSecrets ? cookie.getCookieText() : null);
            map.put("mH5Tk", includeSecrets ? cookie.getMH5Tk() : null);
            map.put("websocketToken", includeSecrets ? cookie.getWebsocketToken() : null);
            map.put("cookieStatus", cookie.getCookieStatus());
            map.put("expireTime", cookie.getExpireTime());
            map.put("tokenExpireTime", cookie.getTokenExpireTime());
            cookieList.add(map);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accounts", accountList);
        data.put("cookies", cookieList);
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        Map<String, Long> unbToAccountId = new HashMap<>();
        Object existingMapping = context.get("unbToAccountId");
        if (existingMapping instanceof Map<?, ?> mapping) {
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                if (entry.getKey() instanceof String unb && entry.getValue() instanceof Long accountId) {
                    unbToAccountId.put(unb, accountId);
                }
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accountMaps = (List<Map<String, Object>>) data.get("accounts");
        if (accountMaps != null) {
            for (Map<String, Object> map : accountMaps) {
                try {
                    String unb = (String) map.get("unb");
                    if (unb == null || unb.isBlank()) {
                        throw new IllegalArgumentException("账号数据缺少UNB");
                    }

                    LambdaQueryWrapper<XianyuAccount> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(XianyuAccount::getUnb, unb);
                    XianyuAccount existing = accountMapper.selectOne(wrapper);

                    XianyuAccount account;
                    if (existing == null) {
                        account = new XianyuAccount();
                        account.setUnb(unb);
                        account.setAccountNote((String) map.get("accountNote"));
                        account.setDeviceId((String) map.get("deviceId"));
                        account.setStatus(map.get("status") != null ? ((Number) map.get("status")).intValue() : null);
                        account.setWebsocketEnabled(map.get("websocketEnabled") != null
                                ? ((Number) map.get("websocketEnabled")).intValue() : 1);
                        accountMapper.insert(account);
                    } else {
                        account = existing;
                        if (map.get("accountNote") != null) {
                            account.setAccountNote((String) map.get("accountNote"));
                        }
                        if (map.get("deviceId") != null) {
                            account.setDeviceId((String) map.get("deviceId"));
                        }
                        if (map.get("status") != null) {
                            account.setStatus(((Number) map.get("status")).intValue());
                        }
                        if (map.get("websocketEnabled") != null) {
                            account.setWebsocketEnabled(
                                    ((Number) map.get("websocketEnabled")).intValue());
                        }
                        accountMapper.updateById(account);
                    }
                    if (account.getId() == null) {
                        throw new IllegalStateException("账号写入后未返回ID: " + unb);
                    }
                    unbToAccountId.put(unb, account.getId());
                } catch (Exception e) {
                    throw new IllegalStateException("导入单条账号数据失败", e);
                }
            }
        }

        context.put("unbToAccountId", unbToAccountId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cookieMaps = (List<Map<String, Object>>) data.get("cookies");
        if (cookieMaps != null) {
            for (Map<String, Object> map : cookieMaps) {
                try {
                    String unb = (String) map.get("unb");
                    if (unb == null || unb.isBlank()) {
                        throw new IllegalArgumentException("Cookie数据缺少UNB");
                    }

                    Long accountId = unbToAccountId.get(unb);
                    if (accountId == null) {
                        throw new IllegalStateException("找不到Cookie关联账号: " + unb);
                    }
                    LambdaQueryWrapper<XianyuCookie> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(XianyuCookie::getXianyuAccountId, accountId);
                    XianyuCookie existing = cookieMapper.selectOne(wrapper);

                    XianyuCookie cookie = new XianyuCookie();
                    cookie.setXianyuAccountId(accountId);
                    cookie.setCookieText((String) map.get("cookieText"));
                    cookie.setMH5Tk((String) map.get("mH5Tk"));
                    cookie.setCookieStatus(map.get("cookieStatus") != null ? ((Number) map.get("cookieStatus")).intValue() : null);
                    cookie.setWebsocketToken((String) map.get("websocketToken"));
                    cookie.setTokenExpireTime(map.get("tokenExpireTime") != null ? ((Number) map.get("tokenExpireTime")).longValue() : null);

                    if (existing == null) {
                        cookieMapper.insert(cookie);
                    } else {
                        cookie.setId(existing.getId());
                        cookieMapper.updateById(cookie);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("导入单条Cookie数据失败", e);
                }
            }
        }
    }
}



