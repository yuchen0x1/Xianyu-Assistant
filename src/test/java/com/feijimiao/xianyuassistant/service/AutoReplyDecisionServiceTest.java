package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.entity.AutoReplyDecision;
import com.feijimiao.xianyuassistant.mapper.AutoReplyDecisionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动回复决策日志服务单元测试：去重、截断、异常吞噬与分页查询。
 */
class AutoReplyDecisionServiceTest {

    private AutoReplyDecisionMapper mapper;
    private AutoReplyDecisionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AutoReplyDecisionMapper.class);
        service = new AutoReplyDecisionService();
        try {
            java.lang.reflect.Field f = AutoReplyDecisionService.class.getDeclaredField("decisionMapper");
            f.setAccessible(true);
            f.set(service, mapper);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("正常记录跳过决策并截断超长消息")
    void recordSkipInsertsTruncatedDecision() {
        when(mapper.countRecent(any(), any(), any(), anyInt())).thenReturn(0);

        String longMessage = "x".repeat(1200);
        service.recordSkip(7L, "s-1", "g-1", "buyer-1", longMessage,
                AutoReplyDecisionService.REASON_HUMAN_TAKEOVER, "detail");

        ArgumentCaptor<AutoReplyDecision> captor = ArgumentCaptor.forClass(AutoReplyDecision.class);
        verify(mapper).insert(captor.capture());
        AutoReplyDecision saved = captor.getValue();
        assertEquals(7L, saved.getXianyuAccountId());
        assertEquals("s-1", saved.getSId());
        assertEquals(AutoReplyDecisionService.REASON_HUMAN_TAKEOVER, saved.getReasonCode());
        assertEquals(500, saved.getBuyerMessage().length());
        assertEquals("detail", saved.getDetail());
    }

    @Test
    @DisplayName("同一会话同一原因在去重窗口内不重复落库")
    void recordSkipDedupesWithinWindow() {
        when(mapper.countRecent(7L, "s-1", AutoReplyDecisionService.REASON_HUMAN_TAKEOVER, 60)).thenReturn(1);

        service.recordSkip(7L, "s-1", "g-1", "buyer-1", "hello",
                AutoReplyDecisionService.REASON_HUMAN_TAKEOVER);

        verify(mapper, never()).insert(any(AutoReplyDecision.class));
    }

    @Test
    @DisplayName("Mapper 异常被吞噬，不向主流程抛出")
    void recordSkipSwallowsMapperException() {
        when(mapper.countRecent(any(), any(), any(), anyInt())).thenReturn(0);
        org.mockito.Mockito.doThrow(new RuntimeException("db locked"))
                .when(mapper).insert(any(AutoReplyDecision.class));

        service.recordSkip(7L, "s-1", "g-1", "buyer-1", "hello",
                AutoReplyDecisionService.REASON_GOODS_NOT_FOUND);

        // 不抛异常即通过
    }

    @Test
    @DisplayName("分页查询返回 list/total/页码并规范化页大小")
    void queryDecisionsReturnsPagedResult() {
        AutoReplyDecision row = new AutoReplyDecision();
        row.setReasonCode(AutoReplyDecisionService.REASON_NO_REPLY_CONTENT);
        when(mapper.selectPage(eq(7L), eq(AutoReplyDecisionService.REASON_NO_REPLY_CONTENT), eq(20), eq(0)))
                .thenReturn(List.of(row));
        when(mapper.countPage(7L, AutoReplyDecisionService.REASON_NO_REPLY_CONTENT)).thenReturn(1);

        Map<String, Object> result = service.queryDecisions(7L,
                AutoReplyDecisionService.REASON_NO_REPLY_CONTENT, 1, 20);

        assertEquals(1, result.get("totalCount"));
        assertEquals(1, result.get("pageNum"));
        assertEquals(20, result.get("pageSize"));
        assertEquals(1, ((List<?>) result.get("list")).size());
    }

    @Test
    @DisplayName("非法页码与超大页大小被规范化")
    void queryDecisionsNormalizesPagination() {
        when(mapper.selectPage(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(mapper.countPage(any(), any())).thenReturn(0);

        Map<String, Object> result = service.queryDecisions(null, null, -3, 5000);

        assertEquals(1, result.get("pageNum"));
        assertEquals(100, result.get("pageSize"));
        verify(mapper).selectPage(eq(null), eq(null), eq(100), eq(0));
    }

    @Test
    @DisplayName("detail 为空时不设置 detail 字段")
    void recordSkipWithoutDetail() {
        when(mapper.countRecent(any(), any(), any(), anyInt())).thenReturn(0);

        service.recordSkip(1L, null, "g-2", "buyer-2", "msg",
                AutoReplyDecisionService.REASON_NO_REPLY_STRATEGY);

        ArgumentCaptor<AutoReplyDecision> captor = ArgumentCaptor.forClass(AutoReplyDecision.class);
        verify(mapper).insert(captor.capture());
        assertNull(captor.getValue().getDetail());
        assertNull(captor.getValue().getSId());
    }
}
