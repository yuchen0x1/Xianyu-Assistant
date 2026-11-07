package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.adapter.xianyu.XianyuAdapter;
import com.feijimiao.xianyuassistant.controller.dto.BatchOffShelfItemsRespDTO;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuPublishHistoryMapper;
import com.feijimiao.xianyuassistant.service.AccountService;
import com.feijimiao.xianyuassistant.utils.XianyuApiCallUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublishServiceImplBatchOffShelfTest {

    @Mock
    private XianyuAdapter xianyuAdapter;
    @Mock
    private AccountService accountService;
    @Mock
    private XianyuAccountMapper accountMapper;
    @Mock
    private XianyuPublishHistoryMapper publishHistoryMapper;

    private PublishServiceImpl publishService;

    @BeforeEach
    void setUp() {
        publishService = new PublishServiceImpl();
        ReflectionTestUtils.setField(publishService, "xianyuAdapter", xianyuAdapter);
        ReflectionTestUtils.setField(publishService, "accountService", accountService);
        ReflectionTestUtils.setField(publishService, "accountMapper", accountMapper);
        ReflectionTestUtils.setField(publishService, "publishHistoryMapper", publishHistoryMapper);

        lenient().when(accountService.getCookieByAccountId(anyLong())).thenReturn("cookie");
        lenient().when(accountMapper.selectById(anyLong())).thenReturn(new XianyuAccount());
    }

    @Test
    void reportsPerItemResultsAndContinuesAfterFailure() {
        when(xianyuAdapter.callApi(anyLong(), anyString(), anyMap(), anyString(), anyString()))
                .thenReturn(successResult())
                .thenReturn(failedResult())
                .thenReturn(successResult());

        BatchOffShelfItemsRespDTO resp = publishService.batchOffShelf(1L, List.of("A", "B", "C"));

        assertThat(resp.getTotal()).isEqualTo(3);
        assertThat(resp.getSuccess()).isEqualTo(2);
        assertThat(resp.getFailed()).isEqualTo(1);
        assertThat(resp.getDetails()).hasSize(3);

        assertThat(resp.getDetails().get(0).getXyGoodsId()).isEqualTo("A");
        assertThat(resp.getDetails().get(0).getSuccess()).isTrue();

        assertThat(resp.getDetails().get(1).getXyGoodsId()).isEqualTo("B");
        assertThat(resp.getDetails().get(1).getSuccess()).isFalse();

        assertThat(resp.getDetails().get(2).getXyGoodsId()).isEqualTo("C");
        assertThat(resp.getDetails().get(2).getSuccess()).isTrue();
    }

    @Test
    void emptyListReturnsZeroCounts() {
        BatchOffShelfItemsRespDTO resp = publishService.batchOffShelf(1L, List.of());

        assertThat(resp.getTotal()).isZero();
        assertThat(resp.getSuccess()).isZero();
        assertThat(resp.getFailed()).isZero();
        assertThat(resp.getDetails()).isEmpty();
    }

    @Test
    void apiExceptionIsolatedAsFailureForThatItem() {
        when(xianyuAdapter.callApi(anyLong(), anyString(), anyMap(), anyString(), anyString()))
                .thenThrow(new RuntimeException("network down"));

        BatchOffShelfItemsRespDTO resp = publishService.batchOffShelf(1L, List.of("A"));

        assertThat(resp.getTotal()).isEqualTo(1);
        assertThat(resp.getSuccess()).isZero();
        assertThat(resp.getFailed()).isEqualTo(1);
        assertThat(resp.getDetails().get(0).getMessage()).contains("network down");
    }

    private XianyuApiCallUtils.ApiCallResult successResult() {
        return new XianyuApiCallUtils.ApiCallResult(true, "{\"ok\":true}", null, false);
    }

    private XianyuApiCallUtils.ApiCallResult failedResult() {
        return new XianyuApiCallUtils.ApiCallResult(false, null, "item not found", false);
    }
}