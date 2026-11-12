package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import jakarta.validation.Valid;
import com.feijimiao.xianyuassistant.controller.dto.ChangePasswordReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.CurrentUserRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.VersionInfoRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.SystemHealthRespDTO;
import com.feijimiao.xianyuassistant.entity.SysUser;
import com.feijimiao.xianyuassistant.service.AuthService;
import com.feijimiao.xianyuassistant.service.bo.ChangePasswordReqBO;
import com.feijimiao.xianyuassistant.service.update.SelfUpdateService;
import com.feijimiao.xianyuassistant.service.update.UpdateCheckService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 系统设置控制器
 * @author IAMLZY
 * @date 2026/4/22
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
public class SystemController {

    @Value("${app.version:3.5.22}")
    private String currentVersion;

    @Autowired
    private AuthService authService;

    @Autowired
    private SelfUpdateService selfUpdateService;

    @Autowired
    private UpdateCheckService updateCheckService;

    /**
     * 在线自更新状态（部署方式是否支持、当前更新进度）
     */
    @GetMapping("/selfUpdate/status")
    @com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission("settings:read")
    public ResultObject<com.feijimiao.xianyuassistant.controller.dto.SelfUpdateStatusDTO> selfUpdateStatus() {
        com.feijimiao.xianyuassistant.controller.dto.SelfUpdateStatusDTO dto =
                new com.feijimiao.xianyuassistant.controller.dto.SelfUpdateStatusDTO();
        dto.setAvailable(selfUpdateService.isAvailable());
        dto.setUnavailableReason(selfUpdateService.getUnavailableReason());
        var status = selfUpdateService.getStatus();
        dto.setPhase(status.phase());
        dto.setPercent(status.percent());
        dto.setDownloadedBytes(status.downloadedBytes());
        dto.setTotalBytes(status.totalBytes());
        dto.setDownloadSpeedBytesPerSecond(selfUpdateService.getDownloadSpeedBytesPerSecond());
        dto.setEstimatedSecondsRemaining(selfUpdateService.getEstimatedSecondsRemaining());
        dto.setTargetVersion(status.targetVersion());
        dto.setMessage(status.message());
        dto.setCanRestart(status.phase() != null
                && SelfUpdateService.PHASE_SWAPPED.equals(status.phase()));
        return ResultObject.success(dto);
    }

    /**
     * 开始在线更新到目标版本（下载 → SHA-256 校验 → 替换 JAR）
     */
    @PostMapping("/selfUpdate/apply")
    @com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission("settings:write")
    public ResultObject<?> applySelfUpdate(@RequestBody(required = false) Map<String, String> body) {
        String targetVersion = body == null ? null : body.get("targetVersion");
        boolean started = selfUpdateService.apply(targetVersion);
        if (started) {
            return ResultObject.success("开始下载新版本 v" + targetVersion);
        }
        var status = selfUpdateService.getStatus();
        String reason = selfUpdateService.isAvailable()
                ? (status.message() != null ? status.message()
                : "无法开始更新：目标版本无效或已有更新进行中（当前阶段 " + status.phase() + "）")
                : selfUpdateService.getUnavailableReason();
        return ResultObject.failed(reason);
    }

    /** 取消尚未完成的在线更新；已替换或已进入重启阶段的更新不可取消。 */
    @PostMapping("/selfUpdate/cancel")
    @com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission("settings:write")
    public ResultObject<?> cancelSelfUpdate() {
        if (selfUpdateService.cancel()) {
            return ResultObject.success("在线更新已取消");
        }
        return ResultObject.failed("当前没有可取消的在线更新");
    }

    /**
     * 新包就绪后执行重启（按 restart-mode 策略）
     */
    @PostMapping("/selfUpdate/restart")
    @com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission("settings:write")
    public ResultObject<?> restartSelfUpdate() {
        if (selfUpdateService.restartNow()) {
            return ResultObject.success("服务正在重启，页面将自动刷新");
        }
        return ResultObject.failed("当前没有可执行的重启（需先完成新包替换）");
    }

    /**
     * 获取当前用户信息
     */
    @PostMapping("/currentUser")
    public ResultObject<CurrentUserRespDTO> getCurrentUser(HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("currentUserId");
            if (userId == null) {
                return ResultObject.unauthorized(null);
            }
            SysUser user = authService.getCurrentUser(userId);
            if (user == null) {
                return ResultObject.failed("用户不存在");
            }
            CurrentUserRespDTO respDTO = new CurrentUserRespDTO();
            respDTO.setUsername(user.getUsername());
            respDTO.setLastLoginTime(user.getLastLoginTime() != null ? user.getLastLoginTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取当前用户信息失败", e);
            return ResultObject.failed("获取当前用户信息失败: " + e.getMessage());
        }
    }

    /**
     * 修改密码
     */
    @PostMapping("/changePassword")
    public ResultObject<?> changePassword(@Valid @RequestBody ChangePasswordReqDTO reqDTO, HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("currentUserId");
            if (userId == null) {
                return ResultObject.unauthorized(null);
            }
            // 参数校验
            if (reqDTO.getOldPassword() == null || reqDTO.getOldPassword().trim().isEmpty()) {
                return ResultObject.validateFailed("原密码不能为空");
            }
            if (reqDTO.getNewPassword() == null || reqDTO.getNewPassword().trim().isEmpty()) {
                return ResultObject.validateFailed("新密码不能为空");
            }
            // 与注册共用同一口令策略，避免「注册被拒的弱口令从改密入口设进去」
            String passwordPolicyError =
                    com.feijimiao.xianyuassistant.utils.PasswordPolicy.validateLength(reqDTO.getNewPassword());
            if (passwordPolicyError != null) {
                return ResultObject.validateFailed("新密码" + passwordPolicyError);
            }
            if (!reqDTO.getNewPassword().equals(reqDTO.getConfirmPassword())) {
                return ResultObject.validateFailed("两次密码不一致");
            }

            ChangePasswordReqBO reqBO = new ChangePasswordReqBO();
            reqBO.setUserId(userId);
            reqBO.setOldPassword(reqDTO.getOldPassword());
            reqBO.setNewPassword(reqDTO.getNewPassword());
            authService.changePassword(reqBO);

            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("修改密码失败", e);
            return ResultObject.failed(e.getMessage());
        }
    }

    @GetMapping("/version")
    public ResultObject<String> getVersion() {
        return ResultObject.success(currentVersion);
    }

    /**
     * 本机控制台的轻量健康探针。
     *
     * <p>该接口刻意不返回账号数、数据库路径、Cookie 或第三方连接状态，
     * 方便前端判断当前服务是否可达，同时避免将运行细节暴露给探针调用方。</p>
     */
    @GetMapping("/health")
    public ResultObject<SystemHealthRespDTO> getHealth() {
        SystemHealthRespDTO response = new SystemHealthRespDTO();
        response.setStatus("UP");
        response.setVersion(currentVersion);
        response.setTimestamp(System.currentTimeMillis());
        return ResultObject.success(response);
    }

    // 不设 settings:read 门控：AppLayout 对所有登录用户轮询新版本角标，
    // 403 会触发全局 toast；此接口仅返回版本信息，探测有固定 allowlist + 5 分钟缓存。
    @GetMapping("/checkUpdate")
    public ResultObject<VersionInfoRespDTO> checkUpdate() {
        return ResultObject.success(updateCheckService.checkUpdate());
    }
}
