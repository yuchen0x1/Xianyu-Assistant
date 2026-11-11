package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.annotation.NoAuth;
import com.feijimiao.xianyuassistant.common.ResultObject;
import jakarta.validation.Valid;
import com.feijimiao.xianyuassistant.controller.dto.CheckUserExistsRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.LoginReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.LoginRespDTO;
import com.feijimiao.xianyuassistant.controller.dto.RegisterReqDTO;
import com.feijimiao.xianyuassistant.service.AuthService;
import com.feijimiao.xianyuassistant.service.bo.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 登录控制器
 * @author IAMLZY
 * @date 2026/4/22
 */
@Slf4j
@RestController
@RequestMapping("/api/login")
@NoAuth
public class LoginController {

    /**
     * 注册口令最小长度：初始管理员账号是整个系统的第一道门，设定最低口令强度底线。
     *
     * <p>实际口径以 {@link com.feijimiao.xianyuassistant.utils.PasswordPolicy} 为准，
     * 这里保留常量仅用于注册接口的提示文案。</p>
     */
    private static final int MIN_PASSWORD_LENGTH =
            com.feijimiao.xianyuassistant.utils.PasswordPolicy.MIN_LENGTH;

    @Autowired
    private AuthService authService;

    /** 受信反代地址白名单（逗号分隔，如 127.0.0.1,::1）。仅当直连地址在名单内时才采信 X-Real-IP / X-Forwarded-For。 */
    @Value("${app.security.trusted-proxies:}")
    private String trustedProxies;

    /**
     * 检查是否有用户（决定显示登录还是注册）
     */
    @PostMapping("/checkUserExists")
    public ResultObject<CheckUserExistsRespDTO> checkUserExists(HttpServletRequest request) {
        try {
            String ip = getClientIp(request);
            // 高频但低敏接口：宽松限流（60次/10分钟）挡住脚本化探测即可
            if (!authService.checkAttempt(ip, "check")) {
                return ResultObject.failed("请求过于频繁，请10分钟后再试");
            }
            CheckUserExistsRespBO respBO = authService.checkUserExists();
            CheckUserExistsRespDTO respDTO = new CheckUserExistsRespDTO();
            respDTO.setExists(respBO.getExists());
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("检查用户存在失败", e);
            return ResultObject.failed("检查用户存在失败: " + e.getMessage());
        }
    }

    /**
     * 注册（仅首个账号可注册，自动绑定 ADMIN）
     */
    @PostMapping("/register")
    public ResultObject<LoginRespDTO> register(@Valid @RequestBody RegisterReqDTO reqDTO, HttpServletRequest request) {
        String ip = getClientIp(request);
        try {
            // 防止对初始管理员账号的口令爆破：与登录同一严格档位（5次/10分钟）
            if (!authService.checkAttempt(ip, "register")) {
                return ResultObject.failed("注册尝试过于频繁，请10分钟后再试");
            }
            // 参数校验
            if (reqDTO.getUsername() == null || reqDTO.getUsername().trim().isEmpty()) {
                return ResultObject.validateFailed("用户名不能为空");
            }
            if (reqDTO.getPassword() == null || reqDTO.getPassword().trim().isEmpty()) {
                return ResultObject.validateFailed("密码不能为空");
            }
            if (reqDTO.getConfirmPassword() == null || !reqDTO.getPassword().equals(reqDTO.getConfirmPassword())) {
                return ResultObject.validateFailed("两次密码不一致");
            }
            if (reqDTO.getUsername().length() < 3 || reqDTO.getUsername().length() > 20) {
                return ResultObject.validateFailed("用户名长度需在3-20之间");
            }
            if (reqDTO.getPassword().length() < MIN_PASSWORD_LENGTH || reqDTO.getPassword().length() > 50) {
                return ResultObject.validateFailed("密码长度需在" + MIN_PASSWORD_LENGTH + "-50之间");
            }
            RegisterReqBO reqBO = new RegisterReqBO();
            reqBO.setUsername(reqDTO.getUsername().trim());
            reqBO.setPassword(reqDTO.getPassword());

            LoginRespBO respBO = authService.register(reqBO);
            authService.clearFailure(ip, "register");

            LoginRespDTO respDTO = new LoginRespDTO();
            respDTO.setToken(respBO.getToken());
            respDTO.setUsername(respBO.getUsername());
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("注册失败", e);
            authService.recordFailure(ip, "register");
            return ResultObject.failed(e.getMessage());
        }
    }

    /**
     * 登录
     */
    @PostMapping("/login")
    public ResultObject<LoginRespDTO> login(@Valid @RequestBody LoginReqDTO reqDTO, HttpServletRequest request) {
        try {
            String ip = getClientIp(request);

            // 检查登录错误次数限制
            if (!authService.checkAttempt(ip, "login")) {
                return ResultObject.failed("登录错误次数过多，请10分钟后再试");
            }

            // 参数校验
            if (reqDTO.getUsername() == null || reqDTO.getUsername().trim().isEmpty()) {
                return ResultObject.validateFailed("用户名不能为空");
            }
            if (reqDTO.getPassword() == null || reqDTO.getPassword().trim().isEmpty()) {
                return ResultObject.validateFailed("密码不能为空");
            }

            String deviceId = request.getHeader("User-Agent");
            if (deviceId != null && deviceId.length() > 100) {
                deviceId = deviceId.substring(0, 100);
            }

            LoginReqBO reqBO = new LoginReqBO();
            reqBO.setUsername(reqDTO.getUsername().trim());
            reqBO.setPassword(reqDTO.getPassword());
            reqBO.setIp(ip);
            reqBO.setDeviceId(deviceId);

            LoginRespBO respBO = authService.login(reqBO);

            // 登录成功，清除失败记录
            authService.clearFailure(ip, "login");

            LoginRespDTO respDTO = new LoginRespDTO();
            respDTO.setToken(respBO.getToken());
            respDTO.setUsername(respBO.getUsername());
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("登录失败", e);
            // 登录失败，记录错误次数
            String ip = getClientIp(request);
            authService.recordFailure(ip, "login");
            return ResultObject.failed(e.getMessage());
        }
    }

    /**
     * 刷新会话：校验当前有效 Token 后轮换签发新 Token（旧 Token 立即撤销）。
     * 前端在用户活跃期间定期调用，避免活跃用户被短有效期强制登出。
     */
    @PostMapping("/refresh")
    public ResultObject<LoginRespDTO> refresh(HttpServletRequest request) {
        try {
            String token = extractBearerToken(request);
            LoginRespBO respBO = authService.refreshToken(token);

            LoginRespDTO respDTO = new LoginRespDTO();
            respDTO.setToken(respBO.getToken());
            respDTO.setUsername(respBO.getUsername());
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.warn("会话刷新失败: errorType={}", e.getClass().getSimpleName());
            return ResultObject.failed(e.getMessage());
        }
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public ResultObject<?> logout(HttpServletRequest request) {
        try {
            String token = extractBearerToken(request);
            authService.logout(token);
            return ResultObject.success(null);
        } catch (Exception e) {
            log.error("退出登录失败", e);
            return ResultObject.failed("退出登录失败: " + e.getMessage());
        }
    }

    private static String extractBearerToken(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            return token.substring(7);
        }
        return token;
    }

    /**
     * 获取客户端IP。默认取直连地址；仅当直连地址属于配置的受信反代白名单
     * （app.security.trusted-proxies，如 nginx 所在的本机回环地址）时，才采信代理写入的
     * X-Real-IP / X-Forwarded-For（取最右一跳，即受信代理追加的真实来源），防止头伪造绕过限流。
     */
    private String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] hops = forwardedFor.split(",");
            return hops[hops.length - 1].trim();
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (trustedProxies == null || trustedProxies.isBlank() || remoteAddr == null) {
            return false;
        }
        Set<String> trusted = Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return trusted.contains(remoteAddr.toLowerCase(Locale.ROOT));
    }
}
