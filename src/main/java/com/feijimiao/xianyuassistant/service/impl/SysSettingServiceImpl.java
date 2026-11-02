package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.exception.BusinessException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.config.rag.DynamicAIChatClientManager;
import com.feijimiao.xianyuassistant.entity.XianyuSysSetting;
import com.feijimiao.xianyuassistant.mapper.XianyuSysSettingMapper;
import com.feijimiao.xianyuassistant.proxy.ProxyPoolManager;
import com.feijimiao.xianyuassistant.security.AiEndpointValidator;
import com.feijimiao.xianyuassistant.security.CredentialCipher;
import com.feijimiao.xianyuassistant.service.SysSettingService;
import com.feijimiao.xianyuassistant.service.bo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 系统配置服务实现
 * @author IAMLZY
 * @date 2026/4/22
 */
@Slf4j
@Service
public class SysSettingServiceImpl implements SysSettingService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final String SECRET_MASK = "********";

    /** 敏感配置键：读取时仅返回掩码，保存时空值/掩码回填保留原值，clearSecret=true 才显式清空 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "ai_api_key", "ai_embedding_api_key",
            "email_smtp_password",
            "notify_dingtalk_secret", "notify_serverchan_sendkey",
            "notify_bark_device_key", "notify_telegram_bot_token",
            "notify_ntfy_token",
            "proxy_list");

    /** AI相关配置键，变更时需要触发ChatClient重建 */
    private static final Set<String> AI_RELATED_KEYS = Set.of("ai_api_key", "ai_base_url", "ai_model");

    /** AI服务端点键：API Key会以Bearer头发往该地址，保存前必须通过安全校验 */
    private static final Set<String> AI_ENDPOINT_KEYS = Set.of("ai_base_url", "ai_embedding_base_url");

    /** 代理池相关配置键，变更时需要触发代理池热重载 */
    private static final Set<String> PROXY_RELATED_KEYS = Set.of(
            ProxyPoolManager.KEY_ENABLED,
            ProxyPoolManager.KEY_LIST,
            ProxyPoolManager.KEY_BLACKLIST_TTL,
            ProxyPoolManager.KEY_MAX_RETRIES);

    @Autowired
    private XianyuSysSettingMapper sysSettingMapper;

    @Autowired
    @Lazy
    private DynamicAIChatClientManager dynamicAIChatClientManager;

    @Autowired
    @Lazy
    private ProxyPoolManager proxyPoolManager;

    @Autowired
    private AiEndpointValidator aiEndpointValidator;

    @Override
    public String getSettingValue(String settingKey) {
        if (settingKey == null || settingKey.trim().isEmpty()) {
            return null;
        }

        LambdaQueryWrapper<XianyuSysSetting> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuSysSetting::getSettingKey, settingKey.trim());
        XianyuSysSetting setting = sysSettingMapper.selectOne(wrapper);

        // 敏感键落库为 AESGCM 密文，读取侧透明解密；历史明文（无 AESGCM: 前缀）原样返回
        if (setting != null && isSensitive(setting.getSettingKey()) && setting.getSettingValue() != null) {
            return CredentialCipher.decrypt(setting.getSettingValue());
        }

        return setting != null ? setting.getSettingValue() : null;
    }

    @Override
    public GetSettingRespBO getSetting(GetSettingReqBO reqBO) {
        if (reqBO == null || reqBO.getSettingKey() == null || reqBO.getSettingKey().trim().isEmpty()) {
            return null;
        }

        LambdaQueryWrapper<XianyuSysSetting> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuSysSetting::getSettingKey, reqBO.getSettingKey().trim());
        XianyuSysSetting setting = sysSettingMapper.selectOne(wrapper);

        if (setting == null) {
            return null;
        }

        GetSettingRespBO respBO = new GetSettingRespBO();
        respBO.setSettingKey(setting.getSettingKey());
        respBO.setSettingValue(setting.getSettingValue());
        respBO.setSettingDesc(setting.getSettingDesc());
        applyMask(respBO);
        return respBO;
    }

    @Override
    public List<GetSettingRespBO> getAllSettings() {
        List<XianyuSysSetting> settings = sysSettingMapper.selectList(null);
        List<GetSettingRespBO> result = new ArrayList<>();

        for (XianyuSysSetting setting : settings) {
            GetSettingRespBO respBO = new GetSettingRespBO();
            respBO.setSettingKey(setting.getSettingKey());
            respBO.setSettingValue(setting.getSettingValue());
            respBO.setSettingDesc(setting.getSettingDesc());
            result.add(respBO);
            applyMask(respBO);
        }

        return result;
    }

    @Override
    public void saveSetting(SaveSettingReqBO reqBO) {
        if (reqBO == null || reqBO.getSettingKey() == null || reqBO.getSettingKey().trim().isEmpty()) {
            throw new BusinessException("配置键不能为空");
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        String key = reqBO.getSettingKey().trim();
        boolean sensitive = isSensitive(key);
        boolean clear = Boolean.TRUE.equals(reqBO.getClearSecret());
        String newValue = reqBO.getSettingValue();
        if (sensitive && clear) {
            // 显式清空：忽略传入值，直接置空
            newValue = null;
        } else if (sensitive && isMaskedOrBlank(newValue)) {
            // 掩码/空值不会直接落库（新增时无原值可保留）
            newValue = null;
        }

        // AI端点地址会携带 API Key 出站，非空新值必须先通过 SSRF 校验
        if (AI_ENDPOINT_KEYS.contains(key) && newValue != null && !newValue.isBlank()) {
            aiEndpointValidator.validate(newValue);
        }

        // 敏感键新写值先加密再落库（AESGCM 密文），读取侧 getSettingValue 会透明解密
        if (sensitive && newValue != null) {
            newValue = CredentialCipher.encrypt(newValue);
        }

        // 查询是否已存在
        LambdaQueryWrapper<XianyuSysSetting> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuSysSetting::getSettingKey, key);
        XianyuSysSetting existing = sysSettingMapper.selectOne(wrapper);

        if (existing != null) {
            // 更新：敏感键掩码/空回填时保留原密钥（原值已是密文，直接回写不重复加密）；
            // clearSecret=true 时清空
            if (sensitive && !clear && newValue == null) {
                newValue = existing.getSettingValue();
            }
            existing.setSettingValue(newValue);
            existing.setSettingDesc(reqBO.getSettingDesc());
            existing.setUpdatedTime(now);
            sysSettingMapper.updateById(existing);
            log.info("[SysSetting] 更新配置成功: key={}", key);
        } else {
            // 新增
            XianyuSysSetting setting = new XianyuSysSetting();
            setting.setSettingKey(key);
            setting.setSettingValue(newValue);
            setting.setSettingDesc(reqBO.getSettingDesc());
            setting.setCreatedTime(now);
            setting.setUpdatedTime(now);
            sysSettingMapper.insert(setting);
            log.info("[SysSetting] 新增配置成功: key={}", key);
        }

        // 如果是AI相关配置，触发ChatClient重建
        if (AI_RELATED_KEYS.contains(reqBO.getSettingKey().trim())) {
            log.info("[SysSetting] AI配置变更，触发ChatClient重建: key={}", reqBO.getSettingKey());
            dynamicAIChatClientManager.forceRebuild();
        }

        // 代理池相关配置变更，触发代理池热重载
        if (PROXY_RELATED_KEYS.contains(reqBO.getSettingKey().trim())) {
            log.info("[SysSetting] 代理池配置变更，触发代理池热重载: key={}", reqBO.getSettingKey());
            try {
                proxyPoolManager.reload();
            } catch (Exception e) {
                log.warn("[SysSetting] 代理池热重载失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public void deleteSetting(String settingKey) {
        if (settingKey == null || settingKey.trim().isEmpty()) {
            throw new BusinessException("配置键不能为空");
        }

        LambdaQueryWrapper<XianyuSysSetting> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XianyuSysSetting::getSettingKey, settingKey.trim());
        sysSettingMapper.delete(wrapper);
        log.info("[SysSetting] 删除配置成功: key={}", settingKey);

        // 如果是AI相关配置，触发ChatClient重建
        if (AI_RELATED_KEYS.contains(settingKey.trim())) {
            log.info("[SysSetting] AI配置删除，触发ChatClient重建: key={}", settingKey);
            dynamicAIChatClientManager.forceRebuild();
        }

        // 代理池相关配置删除，触发代理池热重载
        if (PROXY_RELATED_KEYS.contains(settingKey.trim())) {
            log.info("[SysSetting] 代理池配置删除，触发代理池热重载: key={}", settingKey);
            try {
                proxyPoolManager.reload();
            } catch (Exception e) {
                log.warn("[SysSetting] 代理池热重载失败: {}", e.getMessage());
            }
        }
    }

    private boolean isSensitive(String key) {
        return key != null && SENSITIVE_KEYS.contains(key.trim());
    }

    private boolean isMaskedOrBlank(String value) {
        return value == null || value.isBlank() || SECRET_MASK.equals(value.trim());
    }

    private void applyMask(GetSettingRespBO respBO) {
        if (respBO == null) return;
        boolean hasSecret = respBO.getSettingValue() != null && !respBO.getSettingValue().isBlank();
        if (isSensitive(respBO.getSettingKey())) {
            respBO.setSettingValue(hasSecret ? SECRET_MASK : "");
            respBO.setHasSecret(hasSecret);
        } else {
            respBO.setHasSecret(false);
        }
    }
}
