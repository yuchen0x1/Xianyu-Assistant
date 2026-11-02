package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import jakarta.validation.Valid;
import com.feijimiao.xianyuassistant.controller.dto.*;
import com.feijimiao.xianyuassistant.service.SysSettingService;
import com.feijimiao.xianyuassistant.service.EmailNotifyService;
import com.feijimiao.xianyuassistant.notify.NotifyService;
import com.feijimiao.xianyuassistant.service.bo.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 系统配置控制器
 * @author IAMLZY
 * @date 2026/4/22
 */
@Slf4j
@RestController
@RequestMapping("/api/setting")
@RequirePermission("settings:read")
public class SysSettingController {

    /** AI服务端点键：API Key 会以Bearer头发往该地址，仅 ADMIN 可修改，防止低权限账号借端点重定向外带凭据 */
    private static final java.util.Set<String> AI_ENDPOINT_KEYS =
            java.util.Set.of("ai_base_url", "ai_embedding_base_url");

    @Autowired
    private SysSettingService sysSettingService;

    @Autowired
    private RbacService rbacService;

    @Autowired(required = false)
    private EmailNotifyService emailNotifyService;

    @Autowired
    private NotifyService notifyService;

    /**
     * 获取配置
     */
    @PostMapping("/get")
    public ResultObject<GetSettingRespDTO> getSetting(@RequestBody GetSettingReqDTO reqDTO) {
        try {
            if (reqDTO == null || reqDTO.getSettingKey() == null || reqDTO.getSettingKey().trim().isEmpty()) {
                return ResultObject.validateFailed("配置键不能为空");
            }

            GetSettingReqBO reqBO = new GetSettingReqBO();
            reqBO.setSettingKey(reqDTO.getSettingKey());

            GetSettingRespBO respBO = sysSettingService.getSetting(reqBO);

            GetSettingRespDTO respDTO = new GetSettingRespDTO();
            if (respBO != null) {
                respDTO.setSettingKey(respBO.getSettingKey());
                respDTO.setSettingValue(respBO.getSettingValue());
                respDTO.setSettingDesc(respBO.getSettingDesc());
                respDTO.setHasSecret(respBO.getHasSecret());
            } else {
                respDTO.setSettingKey(reqDTO.getSettingKey());
                respDTO.setSettingValue(null);
                respDTO.setSettingDesc(null);
            }
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取配置失败", e);
            return ResultObject.failed("获取配置失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有配置
     */
    @PostMapping("/list")
    public ResultObject<List<GetSettingRespDTO>> getAllSettings() {
        try {
            List<GetSettingRespBO> respBOList = sysSettingService.getAllSettings();
            List<GetSettingRespDTO> result = new ArrayList<>();

            for (GetSettingRespBO respBO : respBOList) {
                GetSettingRespDTO respDTO = new GetSettingRespDTO();
                respDTO.setSettingKey(respBO.getSettingKey());
                respDTO.setSettingValue(respBO.getSettingValue());
                respDTO.setSettingDesc(respBO.getSettingDesc());
                respDTO.setHasSecret(respBO.getHasSecret());
                result.add(respDTO);
            }

            return ResultObject.success(result);
        } catch (Exception e) {
            log.error("获取所有配置失败", e);
            return ResultObject.failed("获取所有配置失败: " + e.getMessage());
        }
    }

    /**
     * 保存配置
     */
    @PostMapping("/save")
    @RequirePermission("settings:write")
    public ResultObject<?> saveSetting(@Valid @RequestBody SaveSettingReqDTO reqDTO) {
        try {
            if (reqDTO == null || reqDTO.getSettingKey() == null || reqDTO.getSettingKey().trim().isEmpty()) {
                return ResultObject.validateFailed("配置键不能为空");
            }

            String settingKey = reqDTO.getSettingKey().trim();
            if (AI_ENDPOINT_KEYS.contains(settingKey)) {
                Long userId = UserContext.getUserId();
                if (userId == null || !rbacService.getUserRoleNames(userId).contains("ADMIN")) {
                    log.warn("[SysSetting] 非ADMIN用户尝试修改AI服务端点被拒绝: key={}, userId={}", settingKey, userId);
                    return ResultObject.validateFailed("AI服务端点仅管理员可修改");
                }
            }

            SaveSettingReqBO reqBO = new SaveSettingReqBO();
            reqBO.setSettingKey(reqDTO.getSettingKey());
            reqBO.setSettingValue(reqDTO.getSettingValue());
            reqBO.setSettingDesc(reqDTO.getSettingDesc());
            reqBO.setClearSecret(reqDTO.getClearSecret());

            sysSettingService.saveSetting(reqBO);
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("保存配置失败", e);
            return ResultObject.failed("保存配置失败: " + e.getMessage());
        }
    }

    /**
     * 删除配置
     */
    @PostMapping("/delete")
    @RequirePermission("settings:write")
    public ResultObject<?> deleteSetting(@RequestBody GetSettingReqDTO reqDTO) {
        try {
            if (reqDTO == null || reqDTO.getSettingKey() == null || reqDTO.getSettingKey().trim().isEmpty()) {
                return ResultObject.validateFailed("配置键不能为空");
            }

            sysSettingService.deleteSetting(reqDTO.getSettingKey());
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("删除配置失败", e);
            return ResultObject.failed("删除配置失败: " + e.getMessage());
        }
    }

    /**
     * 测试邮箱配置
     */
    @PostMapping("/testEmail")
    @RequirePermission("settings:write")
    public ResultObject<?> testEmail() {
        try {
            if (emailNotifyService == null) {
                return ResultObject.failed("邮件服务未初始化");
            }
            if (!emailNotifyService.isEmailConfigured()) {
                return ResultObject.failed("邮箱配置不完整，请先配置SMTP信息");
            }
            String error = emailNotifyService.sendTestEmail();
            if (error == null) {
                return ResultObject.success("测试邮件发送成功，请检查收件箱");
            } else {
                return ResultObject.failed(error);
            }
        } catch (Exception e) {
            log.error("测试邮箱失败", e);
            return ResultObject.failed("测试邮箱失败: " + e.getMessage());
        }
    }

    /**
     * 向指定通知渠道发送测试推送（channel: wecom/dingtalk/serverchan/bark/telegram/ntfy）
     */
    @PostMapping("/testNotify")
    @RequirePermission("settings:write")
    public ResultObject<?> testNotify(@RequestBody Map<String, String> reqDTO) {
        try {
            String channelKey = reqDTO == null ? null : reqDTO.get("channel");
            if (channelKey == null || channelKey.isBlank()) {
                return ResultObject.validateFailed("通知渠道不能为空");
            }
            com.feijimiao.xianyuassistant.notify.NotifyChannel channel;
            try {
                channel = com.feijimiao.xianyuassistant.notify.NotifyChannel.valueOf(channelKey.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResultObject.validateFailed("未知的通知渠道: " + channelKey);
            }
            String error = notifyService.testChannel(channel);
            if (error == null) {
                return ResultObject.success("测试推送已发送，请查看对应渠道");
            }
            return ResultObject.failed(error);
        } catch (Exception e) {
            log.error("测试通知渠道失败", e);
            return ResultObject.failed("测试通知渠道失败: " + e.getMessage());
        }
    }
}
