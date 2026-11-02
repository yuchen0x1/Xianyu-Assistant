package com.feijimiao.xianyuassistant.utils;

import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 账号级认证/风控失败保护。
 *
 * <p>它只处理明确的认证或风控失败，不把网络超时、HTTP 错误和解析错误计入失败次数，
 * 避免后台任务在账号已被要求人工验证时继续重试。</p>
 */
@Slf4j
@Component
public class XianyuAuthFailureGuard {

    private final ConcurrentMap<Long, FailureState> states = new ConcurrentHashMap<>();

    /**
     * 判断账号当前是否处于冷却期。
     */
    public boolean isCoolingDown(Long accountId) {
        if (accountId == null) {
            return false;
        }

        FailureState state = states.get(accountId);
        if (state == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (state.cooldownUntil > now) {
            return true;
        }

        if (state.cooldownUntil > 0 || now - state.windowStartedAt >= XianyuProtocolProfile.AUTH_FAILURE_WINDOW_MS) {
            states.remove(accountId, state);
        }
        return false;
    }

    /**
     * 返回剩余冷却时间，便于给上层返回可操作的提示。
     */
    public long remainingCooldownMillis(Long accountId) {
        FailureState state = accountId == null ? null : states.get(accountId);
        if (state == null) {
            return 0L;
        }
        return Math.max(0L, state.cooldownUntil - System.currentTimeMillis());
    }

    /**
     * 记录一次明确的认证/风控失败。
     */
    public void recordFailure(Long accountId, String reason) {
        if (accountId == null) {
            return;
        }

        long now = System.currentTimeMillis();
        states.compute(accountId, (id, previous) -> {
            FailureState state = previous;
            if (state == null || now - state.windowStartedAt >= XianyuProtocolProfile.AUTH_FAILURE_WINDOW_MS
                    || state.cooldownUntil > 0) {
                state = new FailureState(now, 0, 0L);
            }

            state.failureCount++;

            // 分级熔断：RGV587 等明确风控立即进入更长冷却，不等待普通阈值。
            if (isRiskControlReason(reason)) {
                state.cooldownUntil = now + XianyuProtocolProfile.RISK_CONTROL_COOLDOWN_MS;
                log.warn("账号{}触发明确风控，立即进入{}秒熔断；原因={}",
                        id,
                        XianyuProtocolProfile.RISK_CONTROL_COOLDOWN_MS / 1000,
                        sanitizeReason(reason));
            } else if (state.failureCount >= XianyuProtocolProfile.AUTH_FAILURE_THRESHOLD) {
                state.cooldownUntil = now + XianyuProtocolProfile.AUTH_FAILURE_COOLDOWN_MS;
                log.warn("账号{}连续认证失败达到阈值，进入{}秒冷却；原因={}",
                        id,
                        XianyuProtocolProfile.AUTH_FAILURE_COOLDOWN_MS / 1000,
                        sanitizeReason(reason));
            }
            return state;
        });
    }

    /**
     * 成功请求后清除该账号的失败窗口。
     */
    public void recordSuccess(Long accountId) {
        if (accountId != null) {
            states.remove(accountId);
        }
    }

    /** 仅用于单元测试或账号删除后的清理。 */
    public void clear(Long accountId) {
        recordSuccess(accountId);
    }

    private String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        String normalized = reason.replaceAll("[\\r\\n\\t]", " ");
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }

    /** 是否为明确的平台风控错误（触发更严的分级熔断）。 */
    private boolean isRiskControlReason(String reason) {
        if (reason == null) {
            return false;
        }
        return reason.contains("RGV587_ERROR")
                || reason.contains("被挤爆啦")
                || reason.contains("FAIL_SYS_USER_VALIDATE");
    }

    private static final class FailureState {
        private final long windowStartedAt;
        private int failureCount;
        private long cooldownUntil;

        private FailureState(long windowStartedAt, int failureCount, long cooldownUntil) {
            this.windowStartedAt = windowStartedAt;
            this.failureCount = failureCount;
            this.cooldownUntil = cooldownUntil;
        }
    }
}
