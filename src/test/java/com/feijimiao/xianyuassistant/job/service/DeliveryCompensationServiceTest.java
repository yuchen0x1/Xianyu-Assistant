package com.feijimiao.xianyuassistant.job.service;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsOrder;
import com.feijimiao.xianyuassistant.job.entity.DeliveryCompensation;
import com.feijimiao.xianyuassistant.job.entity.JobDefinition;
import com.feijimiao.xianyuassistant.job.mapper.DeliveryCompensationMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryCompensationServiceTest {

    @Mock
    private DeliveryCompensationMapper compensationMapper;
    @Mock
    private JobEngine jobEngine;
    @Mock
    private XianyuGoodsOrderMapper orderMapper;

    private DeliveryCompensationService service;

    @BeforeEach
    void setUp() {
        service = new DeliveryCompensationService(compensationMapper, jobEngine, orderMapper);
    }

    @Test
    void registersDeliveryReviewWorkerOnStartup() {
        service.registerWorker();
        verify(jobEngine).registerWorker(eq("DELIVERY_REVIEW"), any());
    }

    @Test
    void resolvesCompensationWhenOrderIsConfirmed() {
        when(compensationMapper.selectById(11L)).thenReturn(compensation(11L));
        XianyuGoodsOrder order = new XianyuGoodsOrder();
        order.setState(1);
        when(orderMapper.selectByAccountIdAndOrderId(20L, "order-1")).thenReturn(order);

        Boolean result = invokeReview(11L);

        assertThat(result).isTrue();
        verify(compensationMapper).updateById(org.mockito.ArgumentMatchers.argThat((DeliveryCompensation updated) ->
                "RESOLVED".equals(updated.getPhase())
                        && "AUTO_CONFIRMED".equals(updated.getResolvedBy())));
    }

    @Test
    void keepsUnknownWhenOrderStillAwaitingManualReview() {
        when(compensationMapper.selectById(12L)).thenReturn(compensation(12L));
        XianyuGoodsOrder order = new XianyuGoodsOrder();
        order.setState(0);
        order.setFailReason("发货结果未知，请人工核对");
        when(orderMapper.selectByAccountIdAndOrderId(20L, "order-1")).thenReturn(order);

        Boolean result = invokeReview(12L);

        assertThat(result).isTrue();
        verify(compensationMapper, never()).updateById(any(DeliveryCompensation.class));
    }

    @Test
    void keepsUnknownWhenOrderIsMissing() {
        when(compensationMapper.selectById(13L)).thenReturn(compensation(13L));
        when(orderMapper.selectByAccountIdAndOrderId(20L, "order-1")).thenReturn(null);

        Boolean result = invokeReview(13L);

        assertThat(result).isTrue();
        verify(compensationMapper, never()).updateById(any(DeliveryCompensation.class));
    }

    @Test
    void missingCompensationIsTreatedAsHandled() {
        when(compensationMapper.selectById(99L)).thenReturn(null);

        Boolean result = invokeReview(99L);

        assertThat(result).isTrue();
        verify(orderMapper, never()).selectByAccountIdAndOrderId(any(), any());
    }

    private DeliveryCompensation compensation(long id) {
        DeliveryCompensation dc = new DeliveryCompensation();
        dc.setId(id);
        dc.setOrderId("order-1");
        dc.setXianyuAccountId(20L);
        dc.setPhase("UNKNOWN");
        return dc;
    }

    private Boolean invokeReview(long compensationId) {
        JobDefinition job = new JobDefinition();
        job.setId(1L);
        job.setJobType("DELIVERY_REVIEW");
        return ReflectionTestUtils.invokeMethod(service, "handleReview", job,
                Map.of("compensationId", compensationId));
    }
}
