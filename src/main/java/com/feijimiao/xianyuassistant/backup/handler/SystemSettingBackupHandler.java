package com.feijimiao.xianyuassistant.backup.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.entity.XianyuSysSetting;
import com.feijimiao.xianyuassistant.mapper.XianyuSysSettingMapper;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.security.AiEndpointValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class SystemSettingBackupHandler implements DataBackupHandler {

    private static final Set<String> BACKUP_KEYS = Set.of(
            "ai_api_key", "ai_base_url", "ai_model",
            "ai_embedding_api_key", "ai_embedding_base_url", "ai_embedding_model",
            "sys_prompt", "similarity_threshold",
            "email_smtp_host", "email_smtp_port", "email_smtp_username", "email_smtp_password",
            "email_smtp_from", "email_smtp_ssl",
            "email_notify_ws_disconnect_enabled", "email_notify_cookie_expire_enabled",
            "email_notify_captcha_enabled",
            "api_delivery_fallback_enabled", "api_delivery_fallback_account_ids"
    );

    /** 凭据类设置：默认不导出，仅在 includeSecrets=true 时导出原值。 */
    private static final Set<String> SECRET_KEYS = Set.of(
            "ai_api_key", "ai_embedding_api_key", "email_smtp_username", "email_smtp_password"
    );

    /**
     * 仅管理员可改的端点设置（与 {@code SysSettingController.AI_ENDPOINT_KEYS} 对齐）。
     * 备份导入直接写库、绕过保存接口的 ADMIN 校验，若不过滤，持有 backup:write 的
     * 运营账号可导入一份自定义备份把 AI 端点指向自己的公网服务器，下次调用时
     * {@code Authorization: Bearer <ai_api_key>} 即被外带。
     */
    private static final Set<String> ADMIN_ONLY_KEYS = Set.of(
            "ai_base_url", "ai_embedding_base_url"
    );

    @Autowired
    private XianyuSysSettingMapper sysSettingMapper;

    @Autowired
    private RbacService rbacService;

    @Autowired
    private AiEndpointValidator aiEndpointValidator;

    @Override
    public String getModuleKey() {
        return "systemSetting";
    }

    @Override
    public String getModuleName() {
        return "系统设置";
    }

    @Override
    public Set<String> getRequiredImportListKeys() {
        return Set.of("settings");
    }

    @Override
    public Map<String, Object> exportData() {
        return exportData(new LinkedHashMap<>());
    }

    @Override
    public Map<String, Object> exportData(Map<String, Object> context) {
        boolean includeSecrets = DataBackupHandler.exportSecrets(context);
        LambdaQueryWrapper<XianyuSysSetting> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(XianyuSysSetting::getSettingKey, BACKUP_KEYS);
        List<XianyuSysSetting> settings = sysSettingMapper.selectList(wrapper);
        if (!includeSecrets) {
            // 与 AccountBackupHandler 同一默认策略：凭据默认置空，避免自动备份/分享备份泄漏密钥
            for (XianyuSysSetting setting : settings) {
                if (SECRET_KEYS.contains(setting.getSettingKey())) {
                    setting.setSettingValue(null);
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settings", settings);
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> settingMaps = (List<Map<String, Object>>) data.get("settings");
        if (settingMaps == null) return;

        boolean admin = isCurrentUserAdmin();
        for (Map<String, Object> map : settingMaps) {
            try {
                String settingKey = (String) map.get("settingKey");
                String settingValue = (String) map.get("settingValue");
                String settingDesc = (String) map.get("settingDesc");

                if (settingKey == null) {
                    throw new IllegalArgumentException("系统设置缺少设置键");
                }
                if (!BACKUP_KEYS.contains(settingKey)) continue;

                if (ADMIN_ONLY_KEYS.contains(settingKey)) {
                    if (!admin) {
                        log.warn("[Backup] 非管理员导入 AI 端点设置被忽略: key={}, userId={}",
                                settingKey, UserContext.getUserId());
                        continue;
                    }
                    // 即便管理员恢复备份，也按保存路径同一标准校验端点，
                    // 避免导入一个内网地址后 AI 功能整体不可用。
                    aiEndpointValidator.validate(settingValue);
                }

                LambdaQueryWrapper<XianyuSysSetting> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(XianyuSysSetting::getSettingKey, settingKey);
                XianyuSysSetting existing = sysSettingMapper.selectOne(wrapper);

                if (existing == null) {
                    XianyuSysSetting setting = new XianyuSysSetting();
                    setting.setSettingKey(settingKey);
                    setting.setSettingValue(settingValue);
                    setting.setSettingDesc(settingDesc);
                    sysSettingMapper.insert(setting);
                } else {
                    existing.setSettingValue(settingValue);
                    existing.setSettingDesc(settingDesc);
                    sysSettingMapper.updateById(existing);
                }
            } catch (Exception e) {
                throw new IllegalStateException("导入单条系统设置失败", e);
            }
        }
    }

    private boolean isCurrentUserAdmin() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return false;
        }
        try {
            return rbacService.getUserRoleNames(userId).contains("ADMIN");
        } catch (RuntimeException e) {
            // 查不到角色时按最小权限处理，宁可跳过端点设置
            log.warn("[Backup] 读取用户角色失败，AI 端点设置按只读处理: userId={}, errorType={}",
                    userId, e.getClass().getSimpleName());
            return false;
        }
    }
}
