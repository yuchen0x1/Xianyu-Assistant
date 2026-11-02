package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.entity.XianyuDeliveryBlockRule;
import com.feijimiao.xianyuassistant.mapper.XianyuDeliveryBlockRuleMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsOrderMapper;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryBlockRuleServiceImplTest {

    @Mock private XianyuDeliveryBlockRuleMapper ruleMapper;
    @Mock private XianyuGoodsOrderMapper orderMapper;
    @Mock private AccountService accountService;
    @Mock private com.feijimiao.xianyuassistant.adapter.xianyu.XianyuAdapter apiCallUtils;

    private DeliveryBlockRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DeliveryBlockRuleServiceImpl();
        ReflectionTestUtils.setField(service, "ruleMapper", ruleMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "xianyuAdapter", apiCallUtils);
    }

    @Test
    void defaultsAreDisabledAndOrdered() {
        when(ruleMapper.selectByAccountId(7L)).thenReturn(List.of());

        var rules = service.getRules(7L);

        assertThat(rules).extracting("ruleCode")
                .containsExactly("buyer_credit_zero", "buyer_has_order", "buyer_unconfirmed");
        assertThat(rules).extracting("enabled").containsOnly(0);
        assertThat(rules).extracting("priority").containsExactly(10, 20, 30);
    }

    @Test
    void excludedItemSkipsEnabledRule() {
        when(ruleMapper.selectByAccountId(7L)).thenReturn(List.of(
                rule("buyer_has_order", 1, 20, "[\"goods-1\"]", "{\"same_item_only\":false}")));

        var decision = service.evaluate(7L, "goods-1", "order-1", "buyer-1");

        assertThat(decision.blocked()).isFalse();
        verify(orderMapper, never()).countOtherOrdersByBuyer(eq(7L), eq("buyer-1"), eq("order-1"), eq(null));
    }

    @Test
    void existingOrderRuleHonorsSameItemScopeAndCustomReason() {
        XianyuDeliveryBlockRule rule = rule(
                "buyer_has_order", 1, 20, "[]", "{\"same_item_only\":true}");
        rule.setBlockReason("请人工核对买家订单");
        when(ruleMapper.selectByAccountId(7L)).thenReturn(List.of(rule));
        when(orderMapper.countOtherOrdersByBuyer(7L, "buyer-1", "order-1", "goods-1"))
                .thenReturn(2);

        var decision = service.evaluate(7L, "goods-1", "order-1", "buyer-1");

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.ruleCode()).isEqualTo("buyer_has_order");
        assertThat(decision.reason()).contains("2笔其他订单").contains("请人工核对买家订单");
    }

    @Test
    void creditApiFailureFailsOpen() {
        when(ruleMapper.selectByAccountId(7L)).thenReturn(List.of(
                rule("buyer_credit_zero", 1, 10, "[]", "{\"threshold\":0}")));
        when(accountService.getCookieByAccountId(7L)).thenReturn("_m_h5_tk=token_1");
        when(apiCallUtils.callApi(
                eq(7L), eq("mtop.idle.web.trade.rate.list"), anyMap(), eq("_m_h5_tk=token_1")))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(false, null, "timeout", false));

        var decision = service.evaluate(7L, "goods-1", "order-1", "buyer-1");

        assertThat(decision.blocked()).isFalse();
    }

    @Test
    void creditCountAtThresholdBlocks() {
        when(ruleMapper.selectByAccountId(7L)).thenReturn(List.of(
                rule("buyer_credit_zero", 1, 10, "[]", "{\"threshold\":0}")));
        when(accountService.getCookieByAccountId(7L)).thenReturn("_m_h5_tk=token_1");
        when(apiCallUtils.callApi(
                eq(7L), eq("mtop.idle.web.trade.rate.list"), anyMap(), eq("_m_h5_tk=token_1")))
                .thenReturn(new XianyuApiCallUtils.ApiCallResult(
                        true, "{\"data\":{\"totalCount\":\"0\"}}", null, false));

        var decision = service.evaluate(7L, "goods-1", "order-1", "buyer-1");

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.reason()).contains("评价数为0");
    }

    private XianyuDeliveryBlockRule rule(String code, int enabled, int priority,
                                         String excludedItems, String config) {
        XianyuDeliveryBlockRule rule = new XianyuDeliveryBlockRule();
        rule.setRuleCode(code);
        rule.setEnabled(enabled);
        rule.setPriority(priority);
        rule.setExcludedItemIds(excludedItems);
        rule.setConfigJson(config);
        return rule;
    }
}
