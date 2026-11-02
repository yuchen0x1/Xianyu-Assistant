package com.feijimiao.xianyuassistant.utils;

import com.feijimiao.xianyuassistant.constants.XianyuProtocolProfile;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class XianyuApiCallUtilsRiskLimitTest {

    @Test
    void slidingWindowAllowsUpToLimitPerMinutePerAccount() {
        XianyuApiCallUtils utils = new XianyuApiCallUtils();

        for (int i = 0; i < XianyuProtocolProfile.ACCOUNT_RATE_LIMIT_PER_MIN; i++) {
            boolean allowed = (boolean) ReflectionTestUtils.invokeMethod(
                    utils, "acquireRateLimitSlot", 1L);
            assertThat(allowed).as("第 %d 次请求应被允许", i + 1).isTrue();
        }

        boolean blocked = (boolean) ReflectionTestUtils.invokeMethod(
                utils, "acquireRateLimitSlot", 1L);
        assertThat(blocked).as("超过每分钟上限后应被拒绝").isFalse();

        // 不同账号互不影响
        boolean otherAccountAllowed = (boolean) ReflectionTestUtils.invokeMethod(
                utils, "acquireRateLimitSlot", 2L);
        assertThat(otherAccountAllowed).isTrue();
    }
}
