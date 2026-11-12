package com.feijimiao.xianyuassistant.service.delivery;

import lombok.Builder;
import lombok.Value;

/**
 * 多段发货模板渲染上下文。
 *
 * <p>所有字段都允许为空；模板渲染时缺失值按空字符串处理，避免把 Java 的
 * {@code null} 文本发送给买家。</p>
 */
@Value
@Builder
public class DeliveryTemplateRenderContext {
    String orderId;
    String itemId;
    String itemTitle;
    String buyerId;
    String buyerNick;
    String cardName;
}
