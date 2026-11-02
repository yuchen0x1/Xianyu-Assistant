package com.feijimiao.xianyuassistant.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XianyuAuthFailureGuardTest {

    @Test
    void entersCooldownOnlyAfterThresholdAndClearsOnSuccess() {
        XianyuAuthFailureGuard guard = new XianyuAuthFailureGuard();

        guard.recordFailure(7L, "FAIL_SYS_SESSION_EXPIRED");
        guard.recordFailure(7L, "FAIL_SYS_SESSION_EXPIRED");
        assertThat(guard.isCoolingDown(7L)).isFalse();

        guard.recordFailure(7L, "FAIL_SYS_SESSION_EXPIRED");
        assertThat(guard.isCoolingDown(7L)).isTrue();
        assertThat(guard.remainingCooldownMillis(7L)).isPositive();

        guard.recordSuccess(7L);
        assertThat(guard.isCoolingDown(7L)).isFalse();
        assertThat(guard.remainingCooldownMillis(7L)).isZero();
    }

    @Test
    void riskControlImmediatelyEntersCooldown() {
        XianyuAuthFailureGuard guard = new XianyuAuthFailureGuard();

        // RGV587 等明确风控应跳过阈值立即熔断（分级更严）
        guard.recordFailure(7L, "RGV587_ERROR");
        assertThat(guard.isCoolingDown(7L)).isTrue();
        assertThat(guard.remainingCooldownMillis(7L)).isPositive();

        guard.recordSuccess(7L);
        assertThat(guard.isCoolingDown(7L)).isFalse();
        assertThat(guard.remainingCooldownMillis(7L)).isZero();
    }

    @Test
    void tracksAccountsIndependently() {
        XianyuAuthFailureGuard guard = new XianyuAuthFailureGuard();

        guard.recordFailure(1L, "RGV587_ERROR");
        guard.recordFailure(1L, "RGV587_ERROR");
        guard.recordFailure(1L, "RGV587_ERROR");

        assertThat(guard.isCoolingDown(1L)).isTrue();
        assertThat(guard.isCoolingDown(2L)).isFalse();
    }
}
