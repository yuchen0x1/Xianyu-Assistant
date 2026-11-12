package com.feijimiao.xianyuassistant.service.delivery;

import com.feijimiao.xianyuassistant.entity.DeliveryTemplate;
import com.feijimiao.xianyuassistant.entity.DeliveryTemplateMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryTemplateRendererTest {

    private final DeliveryTemplateRenderer renderer = new DeliveryTemplateRenderer();

    @Test
    void rendersMessagesInOrderAndSubstitutesWhitelistedVariables() {
        DeliveryTemplate template = template(
                message(2, "订单 {{order_id}}，商品 {{item_title}}"),
                message(1, "你好 {{buyer_nick}}，卡密：{{card_name}}"));

        List<String> result = renderer.renderMessages(template, DeliveryTemplateRenderContext.builder()
                .orderId("O-1")
                .itemTitle("测试商品")
                .buyerNick("小明")
                .cardName("CARD-1")
                .build());

        assertThat(result).containsExactly(
                "你好 小明，卡密：CARD-1",
                "订单 O-1，商品 测试商品");
    }

    @Test
    void rejectsUnknownAndMalformedVariables() {
        assertThatThrownBy(() -> renderer.validate(List.of(message(1, "{{secret}}"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的模板变量");
        assertThatThrownBy(() -> renderer.validate(List.of(message(1, "{{order_id"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("括号");
    }

    @Test
    void enforcesMessageCountAndLengthLimits() {
        List<DeliveryTemplateMessage> tooMany = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(i -> message(i, "x"))
                .toList();
        assertThatThrownBy(() -> renderer.validate(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多20");

        assertThatThrownBy(() -> renderer.validate(List.of(message(1, "x".repeat(2001)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多2000");
    }

    private DeliveryTemplate template(DeliveryTemplateMessage... messages) {
        DeliveryTemplate template = new DeliveryTemplate();
        template.setEnabled(1);
        template.setMessages(List.of(messages));
        return template;
    }

    private DeliveryTemplateMessage message(int order, String content) {
        DeliveryTemplateMessage message = new DeliveryTemplateMessage();
        message.setSortOrder(order);
        message.setMessageType("TEXT");
        message.setContent(content);
        return message;
    }
}
