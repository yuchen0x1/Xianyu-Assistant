package com.feijimiao.xianyuassistant.adapter.xianyu;

import com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class XianyuAdapterImplTest {

    @Mock private XianyuApiCallUtils xianyuApiCallUtils;

    private XianyuAdapterImpl buildAdapter() {
        XianyuAdapterImpl adapter = new XianyuAdapterImpl();
        ReflectionTestUtils.setField(adapter, "xianyuApiCallUtils", xianyuApiCallUtils);
        return adapter;
    }

    @Test
    void callApiDelegatesToInternalUtils() {
        XianyuApiCallUtils.ApiCallResult ok = new XianyuApiCallUtils.ApiCallResult(true, "{}", null, false);
        when(xianyuApiCallUtils.callApiWithRetry(eq(1L), eq("mtop.api.test"), anyMap(), anyString()))
                .thenReturn(ok);

        XianyuAdapterImpl adapter = buildAdapter();
        XianyuApiCallUtils.ApiCallResult result = adapter.callApi(1L, "mtop.api.test", new HashMap<>(), "cookie");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("{}");
    }

    @Test
    void callApiDegradesToFailureResultWhenInternalThrows() {
        when(xianyuApiCallUtils.callApiWithRetry(eq(1L), eq("mtop.api.broken"), anyMap(), anyString()))
                .thenThrow(new IllegalStateException("platform contract changed"));

        XianyuAdapterImpl adapter = buildAdapter();
        XianyuApiCallUtils.ApiCallResult result = adapter.callApi(1L, "mtop.api.broken", new HashMap<>(), "cookie");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("adapter-degraded");
    }

    @Test
    void callApiVersionOverloadDelegatesWithVersion() {
        XianyuApiCallUtils.ApiCallResult ok = new XianyuApiCallUtils.ApiCallResult(true, "{}", null, false);
        when(xianyuApiCallUtils.callApiWithRetry(eq(1L), eq("mtop.api.v2"), anyMap(), anyString(), eq("2.0")))
                .thenReturn(ok);

        XianyuAdapterImpl adapter = buildAdapter();
        XianyuApiCallUtils.ApiCallResult result = adapter.callApi(1L, "mtop.api.v2", new HashMap<>(), "cookie", "2.0");

        assertThat(result.isSuccess()).isTrue();
    }
}