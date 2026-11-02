package com.feijimiao.xianyuassistant.interceptor;

import com.feijimiao.xianyuassistant.annotation.NoAuth;
import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import com.feijimiao.xianyuassistant.service.AuthService;
import com.feijimiao.xianyuassistant.util.JwtUtil;
import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录认证 + 权限校验拦截器
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AuthService authService;

    @Autowired(required = false)
    private RbacService rbacService;

    private final Gson gson = new Gson();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 免登录注解
        if (handlerMethod.getMethodAnnotation(NoAuth.class) != null
                || handlerMethod.getBeanType().getAnnotation(NoAuth.class) != null) {
            return true;
        }

        // Token 验证
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        if (token == null || token.isEmpty()) {
            writeUnauthorized(response, "暂未登录或token已经过期");
            return false;
        }
        if (!jwtUtil.validateToken(token)) {
            writeUnauthorized(response, "Token无效或已过期");
            return false;
        }
        if (!authService.isTokenValid(token)) {
            writeUnauthorized(response, "账号已在其他设备登录");
            return false;
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        String username = jwtUtil.getUsernameFromToken(token);
        request.setAttribute("currentUserId", userId);
        request.setAttribute("currentUsername", username);
        com.feijimiao.xianyuassistant.context.UserContext.set(userId, username);

        // 权限校验
        RequirePermission permAnnotation = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (permAnnotation == null) {
            permAnnotation = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (permAnnotation != null) {
            if (rbacService == null) {
                log.error("RBAC service is unavailable; deny protected request: path={}", request.getRequestURI());
                writeForbidden(response, "权限服务暂不可用，请稍后重试");
                return false;
            }
            boolean hasPerm = rbacService.hasAnyPermission(userId, permAnnotation.value());
            if (!hasPerm) {
                log.warn("权限不足: userId={}, required={}", userId, String.join(",", permAnnotation.value()));
                writeForbidden(response, "权限不足，需要权限: " + String.join(" 或 ", permAnnotation.value()));
                return false;
            }
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        com.feijimiao.xianyuassistant.context.UserContext.clear();
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        ResultObject<?> result = ResultObject.unauthorized(null);
        result.setMsg(message);
        response.getWriter().write(gson.toJson(result));
    }

    private void writeForbidden(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        ResultObject<?> result = ResultObject.forbidden(null);
        result.setMsg(message);
        response.getWriter().write(gson.toJson(result));
    }
}
