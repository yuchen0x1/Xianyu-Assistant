package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.exception.BusinessException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuCookie;
import com.feijimiao.xianyuassistant.entity.XianyuChatMessage;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.entity.XianyuKamiConfig;
import com.feijimiao.xianyuassistant.entity.XianyuKeywordReplyRule;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuChatMessageMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsInfoMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuDeliveryBlockRuleMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoReplyRecordMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuOperationLogMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsSkuMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsSkuPropertyMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuHumanInterventionRecordMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiItemMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiUsageRecordMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKeywordReplyContentMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKeywordReplyRuleMapper;
import com.feijimiao.xianyuassistant.security.CredentialCipher;
import com.feijimiao.xianyuassistant.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.List;

/**
 * 账号服务实现类
 */
@Slf4j
@Service
public class AccountServiceImpl implements AccountService {

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private XianyuCookieMapper cookieMapper;
    
    @Autowired
    private XianyuChatMessageMapper chatMessageMapper;
    
    @Autowired
    private XianyuGoodsInfoMapper goodsInfoMapper;
    
    @Autowired
    private XianyuGoodsConfigMapper goodsConfigMapper;
    
    @Autowired
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;

    @Autowired
    private XianyuDeliveryBlockRuleMapper deliveryBlockRuleMapper;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.EmailNotifyService emailNotifyService;
    
    @Autowired
    private XianyuGoodsOrderMapper orderMapper;
    
    @Autowired
    private XianyuGoodsAutoReplyRecordMapper autoReplyRecordMapper;
    
    @Autowired
    private XianyuOperationLogMapper operationLogMapper;

    @Autowired
    private XianyuKamiConfigMapper kamiConfigMapper;

    @Autowired
    private XianyuKamiItemMapper kamiItemMapper;

    @Autowired
    private XianyuKamiUsageRecordMapper kamiUsageRecordMapper;

    @Autowired
    private XianyuKeywordReplyRuleMapper keywordReplyRuleMapper;

    @Autowired
    private XianyuKeywordReplyContentMapper keywordReplyContentMapper;

    @Autowired
    private XianyuGoodsSkuMapper goodsSkuMapper;

    @Autowired
    private XianyuGoodsSkuPropertyMapper goodsSkuPropertyMapper;

    @Autowired
    private XianyuHumanInterventionRecordMapper humanInterventionRecordMapper;
    
    
    /**
     * 获取当前时间字符串
     */
    private LocalDateTime getCurrentTimeString() {
        return LocalDateTime.now();
    }
    
    /**
     * 获取未来时间字符串
     */
    private LocalDateTime getFutureTimeString(int days) {
        return LocalDateTime.now().plusDays(days);
    }
    
    /**
     * 对数据库读取出的敏感凭据做幂等解密。
     * <p>
     * 正常情况下实体 TypeHandler 已完成解密；这里作为服务边界兜底，兼容历史查询、
     * 自定义 SQL 或未应用结果映射的场景，避免 AESGCM 密文继续传入签名与网络层。
     * </p>
     *
     * @param credential 数据库返回的 Cookie 或 Token
     * @return 可供业务层使用的明文；空值保持不变
     */
    private String decryptCredential(String credential) {
        return CredentialCipher.decrypt(credential);
    }

    /**
     * 从Cookie字符串中提取_m_h5_tk值
     *
     * @param cookie Cookie字符串
     * @return _m_h5_tk值，如果未找到则返回null
     */
    private String extractMH5TkFromCookie(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return null;
        }
        
        // 查找_m_h5_tk=后面的值
        String[] cookieParts = cookie.split(";\\s*");
        for (String part : cookieParts) {
            if (part.startsWith("_m_h5_tk=")) {
                return part.substring(9); // "_m_h5_tk=".length() = 9
            }
        }
        
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveAccountAndCookie(String accountNote, String unb, String cookieText) {
        return saveAccountAndCookie(accountNote, unb, cookieText, null);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveAccountAndCookie(String accountNote, String unb, String cookieText, String mH5Tk) {
        try {
            log.info("开始保存账号和Cookie: 包含m_h5_tk={}, Cookie长度={}",
                    mH5Tk != null, cookieText != null ? cookieText.length() : 0);

            // 1. 检查账号是否已存在（根据UNB）
            LambdaQueryWrapper<XianyuAccount> accountQuery = new LambdaQueryWrapper<>();
            accountQuery.eq(XianyuAccount::getUnb, unb);
            XianyuAccount existingAccount = accountMapper.selectOne(accountQuery);

            Long accountId;
            if (existingAccount != null) {
                // 账号已存在，更新信息
                accountId = existingAccount.getId();
                existingAccount.setAccountNote(accountNote);
                existingAccount.setStatus(1); // 正常状态
                existingAccount.setWebsocketEnabled(1);
                existingAccount.setUpdatedTime(getCurrentTimeString());
                accountMapper.updateById(existingAccount);
                log.info("账号已存在，更新账号信息: accountId={}", accountId);
            } else {
                // 创建新账号
                XianyuAccount account = new XianyuAccount();
                account.setAccountNote(accountNote);
                account.setUnb(unb);
                account.setStatus(1);
                account.setWebsocketEnabled(1);
                account.setCreatedTime(getCurrentTimeString());
                account.setUpdatedTime(getCurrentTimeString());
                accountMapper.insert(account);
                accountId = account.getId();
                log.info("创建新账号成功: accountId={}", accountId);
            }

            // 2. 保存或更新Cookie
            LambdaQueryWrapper<XianyuCookie> cookieQuery = new LambdaQueryWrapper<>();
            cookieQuery.eq(XianyuCookie::getXianyuAccountId, accountId);
            XianyuCookie existingCookie = cookieMapper.selectOne(cookieQuery);

            if (existingCookie != null) {
                // Cookie已存在，更新
                existingCookie.setCookieText(cookieText);
                existingCookie.setMH5Tk(mH5Tk);
                existingCookie.setCookieStatus(1); // 有效状态
                existingCookie.setExpireTime(getFutureTimeString(30)); // 30天后过期
                existingCookie.setWebsocketToken(null);
                existingCookie.setTokenExpireTime(0L);
                existingCookie.setUpdatedTime(getCurrentTimeString());
                cookieMapper.updateById(existingCookie);
                // updateById默认不写入null，因此显式清除旧Cookie对应的WebSocket凭据。
                cookieMapper.update(null, new UpdateWrapper<XianyuCookie>()
                        .eq("id", existingCookie.getId())
                        .set("websocket_token", null)
                        .set("token_expire_time", 0L));
                log.info("更新Cookie成功: cookieId={}, m_h5_tk={}", 
                        existingCookie.getId(), mH5Tk != null ? "已保存" : "未提供");
            } else {
                // 创建新Cookie
                XianyuCookie cookie = new XianyuCookie();
                cookie.setXianyuAccountId(accountId);
                cookie.setCookieText(cookieText);
                cookie.setMH5Tk(mH5Tk);
                cookie.setCookieStatus(1);
                cookie.setExpireTime(getFutureTimeString(30));
                cookie.setCreatedTime(getCurrentTimeString());
                cookie.setUpdatedTime(getCurrentTimeString());
                cookieMapper.insert(cookie);
                log.info("创建新Cookie成功: cookieId={}, m_h5_tk={}", 
                        cookie.getId(), mH5Tk != null ? "已保存" : "未提供");
            }

            log.info("保存账号和Cookie完成: accountId={}", accountId);
            return accountId;

        } catch (Exception e) {
            log.error("保存账号和Cookie失败", e);
            throw new BusinessException("保存账号和Cookie失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getCookieByAccountId(Long accountId) {
        try {
            log.info("根据账号ID获取Cookie: accountId={}", accountId);

            // 查询最新的有效Cookie
            LambdaQueryWrapper<XianyuCookie> cookieQuery = new LambdaQueryWrapper<>();
            cookieQuery.eq(XianyuCookie::getXianyuAccountId, accountId)
                    .eq(XianyuCookie::getCookieStatus, 1) // 只查询有效的Cookie
                    .orderByDesc(XianyuCookie::getCreatedTime)
                    .last("LIMIT 1");
            XianyuCookie cookie = cookieMapper.selectOne(cookieQuery);

            if (cookie == null) {
                log.warn("未找到有效Cookie: accountId={}", accountId);
                return null;
            }

            log.info("获取Cookie成功: accountId={}", accountId);
            return decryptCredential(cookie.getCookieText());

        } catch (Exception e) {
            log.error("获取Cookie失败: accountId={}", accountId, e);
            return null;
        }
    }

    @Override
    public String getCookieByUnb(String unb) {
        try {
            // 1. 根据UNB查询账号
            LambdaQueryWrapper<XianyuAccount> accountQuery = new LambdaQueryWrapper<>();
            accountQuery.eq(XianyuAccount::getUnb, unb);
            XianyuAccount account = accountMapper.selectOne(accountQuery);

            if (account == null) {
                log.warn("未找到UNB对应的账号");
                return null;
            }

            // 2. 查询Cookie
            LambdaQueryWrapper<XianyuCookie> cookieQuery = new LambdaQueryWrapper<>();
            cookieQuery.eq(XianyuCookie::getXianyuAccountId, account.getId())
                    .eq(XianyuCookie::getCookieStatus, 1) // 只查询有效的Cookie
                    .orderByDesc(XianyuCookie::getCreatedTime)
                    .last("LIMIT 1");
            XianyuCookie cookie = cookieMapper.selectOne(cookieQuery);

            if (cookie == null) {
                log.warn("未找到有效Cookie: accountId={}", account.getId());
                return null;
            }

            log.info("获取Cookie成功: accountId={}", account.getId());
            return decryptCredential(cookie.getCookieText());

        } catch (Exception e) {
            log.error("根据UNB获取Cookie失败", e);
            return null;
        }
    }

    @Override
    public String getCookieByAccountNote(String accountNote) {
        try {
            // 1. 根据账号备注查询账号
            LambdaQueryWrapper<XianyuAccount> accountQuery = new LambdaQueryWrapper<>();
            accountQuery.eq(XianyuAccount::getAccountNote, accountNote);
            XianyuAccount account = accountMapper.selectOne(accountQuery);

            if (account == null) {
                log.warn("未找到账号: accountNotePresent={}", accountNote != null && !accountNote.isBlank());
                return null;
            }

            // 2. 查询Cookie
            LambdaQueryWrapper<XianyuCookie> cookieQuery = new LambdaQueryWrapper<>();
            cookieQuery.eq(XianyuCookie::getXianyuAccountId, account.getId())
                    .eq(XianyuCookie::getCookieStatus, 1)
                    .orderByDesc(XianyuCookie::getCreatedTime)
                    .last("LIMIT 1");
            XianyuCookie cookie = cookieMapper.selectOne(cookieQuery);

            if (cookie == null) {
                log.warn("未找到有效Cookie: accountId={}", account.getId());
                return null;
            }

            log.info("获取Cookie成功: accountId={}", account.getId());
            return decryptCredential(cookie.getCookieText());

        } catch (Exception e) {
            log.error("获取Cookie失败: accountNotePresent={}, errorType={}",
                    accountNote != null && !accountNote.isBlank(), e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateCookie(Long accountId, String cookieText) {
        try {
            log.info("更新Cookie: accountId={}", accountId);

            // 查询现有Cookie
            LambdaQueryWrapper<XianyuCookie> cookieQuery = new LambdaQueryWrapper<>();
            cookieQuery.eq(XianyuCookie::getXianyuAccountId, accountId);
            XianyuCookie cookie = cookieMapper.selectOne(cookieQuery);

            if (cookie != null) {
                // 更新现有Cookie
                cookie.setCookieText(cookieText);
                cookie.setCookieStatus(1);
                cookie.setExpireTime(getFutureTimeString(30));
                cookie.setUpdatedTime(getCurrentTimeString());
                cookieMapper.updateById(cookie);
            } else {
                // 创建新Cookie
                cookie = new XianyuCookie();
                cookie.setXianyuAccountId(accountId);
                cookie.setCookieText(cookieText);
                cookie.setCookieStatus(1);
                cookie.setExpireTime(getFutureTimeString(30));
                cookie.setCreatedTime(getCurrentTimeString());
                cookie.setUpdatedTime(getCurrentTimeString());
                cookieMapper.insert(cookie);
            }

            log.info("更新Cookie成功: accountId={}", accountId);
            return true;

        } catch (Exception e) {
            log.error("更新Cookie失败: accountId={}", accountId, e);
            return false;
        }
    }
    
    @Override
    public String getMh5tkByAccountId(Long accountId) {
        try {
            log.info("根据账号ID获取m_h5_tk: accountId={}", accountId);

            // 查询Cookie记录
            LambdaQueryWrapper<XianyuCookie> cookieQuery = new LambdaQueryWrapper<>();
            cookieQuery.eq(XianyuCookie::getXianyuAccountId, accountId)
                    .eq(XianyuCookie::getCookieStatus, 1)
                    .orderByDesc(XianyuCookie::getCreatedTime)
                    .last("LIMIT 1");
            XianyuCookie cookie = cookieMapper.selectOne(cookieQuery);

            if (cookie == null) {
                log.warn("未找到Cookie记录: accountId={}", accountId);
                return null;
            }

            String mH5Tk = decryptCredential(cookie.getMH5Tk());
            if (mH5Tk != null && !mH5Tk.isEmpty()) {
                log.info("获取m_h5_tk成功: accountId={}", accountId);
            } else {
                log.warn("m_h5_tk为空: accountId={}", accountId);
            }
            
            return mH5Tk;

        } catch (Exception e) {
            log.error("获取m_h5_tk失败: accountId={}", accountId, e);
            return null;
        }
    }
    
    @Override
    public Long getAccountIdByAccountNote(String accountNote) {
        try {
            log.info("根据账号备注获取账号ID: accountNotePresent={}",
                    accountNote != null && !accountNote.isBlank());

            // 查询账号
            LambdaQueryWrapper<XianyuAccount> accountQuery = new LambdaQueryWrapper<>();
            accountQuery.eq(XianyuAccount::getAccountNote, accountNote);
            XianyuAccount account = accountMapper.selectOne(accountQuery);

            if (account == null) {
                log.warn("未找到账号: accountNotePresent={}", accountNote != null && !accountNote.isBlank());
                return null;
            }

            log.info("获取账号ID成功: accountId={}", account.getId());
            return account.getId();

        } catch (Exception e) {
            log.error("获取账号ID失败: accountNotePresent={}, errorType={}",
                    accountNote != null && !accountNote.isBlank(), e.getClass().getSimpleName());
            return null;
        }
    }
    
    @Override
    public Long getAccountIdByUnb(String unb) {
        try {
            log.info("根据UNB获取账号ID: UNB已提供={}", unb != null);

            // 查询账号
            LambdaQueryWrapper<XianyuAccount> accountQuery = new LambdaQueryWrapper<>();
            accountQuery.eq(XianyuAccount::getUnb, unb);
            XianyuAccount account = accountMapper.selectOne(accountQuery);

            if (account == null) {
                log.warn("未找到UNB对应的账号");
                return null;
            }

            log.info("获取账号ID成功: accountId={}", account.getId());
            return account.getId();

        } catch (Exception e) {
            log.error("根据UNB获取账号ID失败", e);
            return null;
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateCookieStatus(Long accountId, Integer cookieStatus) {
        return updateCookieStatus(accountId, cookieStatus, false);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateCookieStatus(Long accountId, Integer cookieStatus, boolean sendNotify) {
        try {
            log.info("更新Cookie状态: accountId={}, cookieStatus={}, sendNotify={}", accountId, cookieStatus, sendNotify);

            // 查询Cookie记录
            LambdaQueryWrapper<XianyuCookie> cookieQuery = new LambdaQueryWrapper<>();
            cookieQuery.eq(XianyuCookie::getXianyuAccountId, accountId);
            XianyuCookie cookie = cookieMapper.selectOne(cookieQuery);

            if (cookie == null) {
                log.warn("未找到Cookie记录: accountId={}", accountId);
                return false;
            }

            Integer oldStatus = cookie.getCookieStatus();
            
            // 更新Cookie状态
            cookie.setCookieStatus(cookieStatus);
            cookie.setUpdatedTime(getCurrentTimeString());
            cookieMapper.updateById(cookie);

            log.info("更新Cookie状态成功: accountId={}, cookieStatus={}", accountId, cookieStatus);
            
            // 只有在明确指定发送通知时才发送邮件（即确认无法自动续期后）
            if (sendNotify && Objects.equals(cookieStatus, 2) && !Objects.equals(oldStatus, 2)) {
                try {
                    XianyuAccount account = accountMapper.selectById(accountId);
                    String accountNote = account != null ? account.getAccountNote() : null;
                    log.info("【账号{}】Cookie已确认无法自动续期，触发Cookie过期通知流程", accountId);
                    emailNotifyService.sendCookieExpireNotifyEmail(accountId, accountNote);
                } catch (Exception e) {
                    log.error("【账号{}】发送Cookie过期邮件通知失败", accountId, e);
                }
            } else if (Objects.equals(cookieStatus, 2) && !Objects.equals(oldStatus, 2)) {
                log.info("【账号{}】Cookie被标记为过期，但未指定发送通知（可能系统将尝试自动续期）", accountId);
            }
            
            return true;

        } catch (Exception e) {
            log.error("更新Cookie状态失败: accountId={}, cookieStatus={}", accountId, cookieStatus, e);
            return false;
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteAccountAndRelatedData(Long accountId) {
        try {
            log.info("开始删除账号及其所有关联数据: accountId={}", accountId);

            // 先删除二级关联数据，确保启用 SQLite 外键约束后仍可完整删除账号。
            LambdaQueryWrapper<XianyuKamiConfig> kamiConfigQuery = new LambdaQueryWrapper<>();
            kamiConfigQuery.eq(XianyuKamiConfig::getXianyuAccountId, accountId);
            List<Long> kamiConfigIds = kamiConfigMapper.selectList(kamiConfigQuery).stream()
                    .map(XianyuKamiConfig::getId)
                    .toList();

            kamiUsageRecordMapper.delete(new LambdaQueryWrapper<com.feijimiao.xianyuassistant.entity.XianyuKamiUsageRecord>()
                    .eq(com.feijimiao.xianyuassistant.entity.XianyuKamiUsageRecord::getXianyuAccountId, accountId));
            if (!kamiConfigIds.isEmpty()) {
                kamiItemMapper.delete(new LambdaQueryWrapper<com.feijimiao.xianyuassistant.entity.XianyuKamiItem>()
                        .in(com.feijimiao.xianyuassistant.entity.XianyuKamiItem::getKamiConfigId, kamiConfigIds));
            }
            kamiConfigMapper.delete(kamiConfigQuery);

            LambdaQueryWrapper<XianyuKeywordReplyRule> keywordRuleQuery = new LambdaQueryWrapper<>();
            keywordRuleQuery.eq(XianyuKeywordReplyRule::getXianyuAccountId, accountId);
            List<Long> keywordRuleIds = keywordReplyRuleMapper.selectList(keywordRuleQuery).stream()
                    .map(XianyuKeywordReplyRule::getId)
                    .toList();
            if (!keywordRuleIds.isEmpty()) {
                keywordReplyContentMapper.delete(new LambdaQueryWrapper<com.feijimiao.xianyuassistant.entity.XianyuKeywordReplyContent>()
                        .in(com.feijimiao.xianyuassistant.entity.XianyuKeywordReplyContent::getRuleId, keywordRuleIds));
            }
            keywordReplyRuleMapper.delete(keywordRuleQuery);

            goodsSkuPropertyMapper.delete(new LambdaQueryWrapper<com.feijimiao.xianyuassistant.entity.XianyuGoodsSkuProperty>()
                    .eq(com.feijimiao.xianyuassistant.entity.XianyuGoodsSkuProperty::getXianyuAccountId, accountId));
            goodsSkuMapper.delete(new LambdaQueryWrapper<com.feijimiao.xianyuassistant.entity.XianyuGoodsSku>()
                    .eq(com.feijimiao.xianyuassistant.entity.XianyuGoodsSku::getXianyuAccountId, accountId));
            humanInterventionRecordMapper.deleteByAccountId(accountId);
            
            // 1. 删除闲鱼聊天消息表数据
            int chatMessageCount = chatMessageMapper.deleteByAccountId(accountId);
            log.info("删除聊天消息数据: accountId={}, 删除数量={}", accountId, chatMessageCount);
            
            // 2. 删除闲鱼商品信息表数据
            LambdaQueryWrapper<XianyuGoodsInfo> goodsInfoQuery = new LambdaQueryWrapper<>();
            goodsInfoQuery.eq(XianyuGoodsInfo::getXianyuAccountId, accountId);
            int goodsInfoCount = goodsInfoMapper.delete(goodsInfoQuery);
            log.info("删除商品信息数据: accountId={}, 删除数量={}", accountId, goodsInfoCount);
            
            // 3. 删除闲鱼商品配置表数据
            int goodsConfigCount = goodsConfigMapper.deleteByAccountId(accountId);
            log.info("删除商品配置数据: accountId={}, 删除数量={}", accountId, goodsConfigCount);
            
            // 4. 删除闲鱼商品自动发货配置表数据
            int autoDeliveryConfigCount = autoDeliveryConfigMapper.deleteByAccountId(accountId);
            log.info("删除自动发货配置数据: accountId={}, 删除数量={}", accountId, autoDeliveryConfigCount);

            int deliveryBlockRuleCount = deliveryBlockRuleMapper.deleteByAccountId(accountId);
            log.info("删除发货风控规则数据: accountId={}, 删除数量={}", accountId, deliveryBlockRuleCount);
            
            // 5. 删除订单数据
            int orderCount = orderMapper.deleteByAccountId(accountId);
            log.info("删除订单数据: accountId={}, 删除数量={}", accountId, orderCount);
            
            // 6. 删除自动回复记录数据
            int autoReplyRecordCount = autoReplyRecordMapper.deleteByAccountId(accountId);
            log.info("删除自动回复记录数据: accountId={}, 删除数量={}", accountId, autoReplyRecordCount);
            
            // 7. 删除操作记录数据
            int operationLogCount = operationLogMapper.deleteByAccountId(accountId);
            log.info("删除操作记录数据: accountId={}, 删除数量={}", accountId, operationLogCount);
            
            // 8. 删除Cookie数据
            LambdaQueryWrapper<XianyuCookie> cookieQuery = new LambdaQueryWrapper<>();
            cookieQuery.eq(XianyuCookie::getXianyuAccountId, accountId);
            int cookieCount = cookieMapper.delete(cookieQuery);
            log.info("删除Cookie数据: accountId={}, 删除数量={}", accountId, cookieCount);
            
            // 9. 删除闲鱼账号表数据
            int accountCount = accountMapper.deleteById(accountId);
            log.info("删除账号数据: accountId={}, 删除数量={}", accountId, accountCount);

            // AUTOINCREMENT 的 sqlite_sequence 记录历史最大 ID，不清除则删除账号后新账号无法复用其 ID。
            int seqReset = accountMapper.resetAutoincrementCounter();
            log.info("重置账号ID计数器: 影响行数={}", seqReset);

            log.info("账号及其所有关联数据删除成功: accountId={}", accountId);
            return true;
        } catch (Exception e) {
            log.error("删除账号及其关联数据失败: accountId={}", accountId, e);
            throw new BusinessException("删除账号失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateAccountCookie(Long accountId, String unb, String cookieText) {
        try {
            log.info("更新账号Cookie: accountId={}, UNB已提供={}", accountId, unb != null);

            // 1. 更新账号的UNB
            XianyuAccount account = accountMapper.selectById(accountId);
            if (account == null) {
                log.warn("账号不存在: accountId={}", accountId);
                return false;
            }
            
            account.setUnb(unb);
            account.setStatus(1);
            account.setWebsocketEnabled(1);
            account.setUpdatedTime(getCurrentTimeString());
            accountMapper.updateById(account);
            log.info("更新账号UNB成功: accountId={}", accountId);

            // 2. 提取_m_h5_tk
            String mH5Tk = extractMH5TkFromCookie(cookieText);

            // 3. 查询现有Cookie
            LambdaQueryWrapper<XianyuCookie> cookieQuery = new LambdaQueryWrapper<>();
            cookieQuery.eq(XianyuCookie::getXianyuAccountId, accountId);
            XianyuCookie cookie = cookieMapper.selectOne(cookieQuery);

            if (cookie != null) {
                // 更新现有Cookie
                cookie.setCookieText(cookieText);
                cookie.setMH5Tk(mH5Tk);
                cookie.setCookieStatus(1);
                cookie.setExpireTime(getFutureTimeString(30));
                cookie.setWebsocketToken(null);
                cookie.setTokenExpireTime(0L);
                cookie.setUpdatedTime(getCurrentTimeString());
                cookieMapper.updateById(cookie);
                // MyBatis-Plus normally omits null fields from updateById; explicitly clear
                // the persisted token so a replaced Cookie can never reuse the old credential.
                cookieMapper.update(null, new UpdateWrapper<XianyuCookie>()
                        .eq("id", cookie.getId())
                        .set("websocket_token", null)
                        .set("token_expire_time", 0L));
                log.info("更新Cookie成功: accountId={}", accountId);
            } else {
                // 创建新Cookie
                cookie = new XianyuCookie();
                cookie.setXianyuAccountId(accountId);
                cookie.setCookieText(cookieText);
                cookie.setMH5Tk(mH5Tk);
                cookie.setCookieStatus(1);
                cookie.setExpireTime(getFutureTimeString(30));
                cookie.setCreatedTime(getCurrentTimeString());
                cookie.setUpdatedTime(getCurrentTimeString());
                cookieMapper.insert(cookie);
                log.info("创建Cookie成功: accountId={}", accountId);
            }

            return true;

        } catch (Exception e) {
            try {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            } catch (Exception rollbackException) {
                log.debug("更新账号Cookie失败时未找到活动事务: accountId={}", accountId);
            }
            log.error("更新账号Cookie失败: accountId={}", accountId, e);
            return false;
        }
    }
    
    @Override
    public String getOrGenerateDeviceId(Long accountId, String unb) {
        try {
            log.info("获取或生成设备ID: accountId={}, UNB已提供={}", accountId, unb != null);
            
            // 1. 查询账号
            XianyuAccount account = accountMapper.selectById(accountId);
            if (account == null) {
                log.warn("账号不存在: accountId={}", accountId);
                return null;
            }
            
            // 2. 检查是否已有设备ID
            String existingDeviceId = account.getDeviceId();
            if (existingDeviceId != null && !existingDeviceId.isEmpty()) {
                log.info("使用已有设备ID: accountId={}, 设备ID长度={}", accountId, existingDeviceId.length());
                return existingDeviceId;
            }
            
            // 3. 生成新的设备ID
            String newDeviceId = com.feijimiao.xianyuassistant.utils.XianyuDeviceUtils.generateDeviceId(unb);
            log.info("生成新设备ID: accountId={}, 设备ID长度={}", accountId, newDeviceId.length());
            
            // 4. 保存到数据库
            account.setDeviceId(newDeviceId);
            account.setUpdatedTime(getCurrentTimeString());
            accountMapper.updateById(account);
            log.info("设备ID已保存到数据库: accountId={}", accountId);
            
            return newDeviceId;
            
        } catch (Exception e) {
            log.error("获取或生成设备ID失败: accountId={}", accountId, e);
            return null;
        }
    }
    
    @Override
    public boolean updateDeviceId(Long accountId, String deviceId) {
        try {
            log.info("更新设备ID: accountId={}, 设备ID长度={}", accountId,
                    deviceId != null ? deviceId.length() : 0);
            
            // 查询账号
            XianyuAccount account = accountMapper.selectById(accountId);
            if (account == null) {
                log.warn("账号不存在: accountId={}", accountId);
                return false;
            }
            
            // 更新设备ID
            account.setDeviceId(deviceId);
            account.setUpdatedTime(getCurrentTimeString());
            accountMapper.updateById(account);
            
            log.info("设备ID更新成功: accountId={}", accountId);
            return true;
            
        } catch (Exception e) {
            log.error("更新设备ID失败: accountId={}", accountId, e);
            return false;
        }
    }
    
    @Override
    public String getXianyuUserId(Long accountId) {
        try {
            // 查询账号
            XianyuAccount account = accountMapper.selectById(accountId);
            if (account == null) {
                log.warn("账号不存在: accountId={}", accountId);
                return null;
            }
            
            // UNB就是闲鱼用户ID
            String unb = account.getUnb();
            log.debug("获取闲鱼用户ID: accountId={}, UNB长度={}", accountId,
                    unb != null ? unb.length() : 0);
            return unb;
            
        } catch (Exception e) {
            log.error("获取闲鱼用户ID失败: accountId={}", accountId, e);
            return null;
        }
    }

    @Override
    public java.util.List<XianyuAccount> listAll() {
        return accountMapper.selectList(null);
    }

    @Override
    public XianyuAccount getById(Long accountId) {
        if (accountId == null) return null;
        return accountMapper.selectById(accountId);
    }

    @Override
    public boolean updateAccount(XianyuAccount account) {
        if (account == null) return false;
        return accountMapper.updateById(account) > 0;
    }

}

