package com.feijimiao.xianyuassistant.service.delivery;

import com.feijimiao.xianyuassistant.entity.DeliveryTemplate;
import com.feijimiao.xianyuassistant.entity.DeliveryTemplateMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 有序多段发货模板校验与变量渲染器。
 *
 * <p>渲染器只接受显式白名单变量，避免模板把任意字段或内部对象带入买家消息。</p>
 */
@Component
public class DeliveryTemplateRenderer {

    public static final int MAX_MESSAGES = 20;
    public static final int MAX_MESSAGE_LENGTH = 2000;
    public static final int MAX_TOTAL_LENGTH = 10000;

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*\\}\\}");
    private static final Set<String> ALLOWED_VARIABLES = Set.of(
            "order_id", "item_id", "item_title", "buyer_id", "buyer_nick", "card_name");

    /** 校验模板消息，非法模板直接抛出 {@link IllegalArgumentException}。 */
    public void validate(List<DeliveryTemplateMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("模板至少需要一条消息");
        }
        if (messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("一个模板最多" + MAX_MESSAGES + "条消息");
        }

        Set<Integer> orders = new HashSet<>();
        int totalLength = 0;
        for (DeliveryTemplateMessage message : messages) {
            if (message == null) {
                throw new IllegalArgumentException("模板消息不能为空");
            }
            if (message.getSortOrder() == null || message.getSortOrder() < 1) {
                throw new IllegalArgumentException("消息顺序必须从1开始");
            }
            if (!orders.add(message.getSortOrder())) {
                throw new IllegalArgumentException("消息顺序不能重复: " + message.getSortOrder());
            }
            String type = normalizeType(message.getMessageType());
            if (!"TEXT".equals(type)) {
                throw new IllegalArgumentException("暂不支持的模板消息类型: " + type);
            }
            String content = message.getContent();
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("模板消息内容不能为空");
            }
            if (content.length() > MAX_MESSAGE_LENGTH) {
                throw new IllegalArgumentException("单条模板消息最多" + MAX_MESSAGE_LENGTH + "个字符");
            }
            validateVariables(content);
            totalLength += content.length();
        }
        if (totalLength > MAX_TOTAL_LENGTH) {
            throw new IllegalArgumentException("模板消息总长度不能超过" + MAX_TOTAL_LENGTH + "个字符");
        }
    }

    /** 按消息顺序渲染模板。返回的新列表不会修改持久化实体。 */
    public List<String> renderMessages(DeliveryTemplate template, DeliveryTemplateRenderContext context) {
        if (template == null) {
            throw new IllegalArgumentException("发货模板不存在");
        }
        if (template.getEnabled() != null && template.getEnabled() == 0) {
            throw new IllegalArgumentException("发货模板已停用");
        }
        List<DeliveryTemplateMessage> messages = template.getMessages();
        validate(messages);

        Map<String, String> values = values(context);
        List<DeliveryTemplateMessage> sorted = new ArrayList<>(messages);
        sorted.sort(Comparator.comparing(DeliveryTemplateMessage::getSortOrder));
        List<String> rendered = new ArrayList<>(sorted.size());
        for (DeliveryTemplateMessage message : sorted) {
            rendered.add(renderOne(message.getContent(), values));
        }
        return rendered;
    }

    /** 校验变量语法并拒绝白名单之外的变量。 */
    public void validateVariables(String content) {
        Matcher matcher = VARIABLE.matcher(content);
        int end = 0;
        while (matcher.find()) {
            String between = content.substring(end, matcher.start());
            if (between.contains("{{") || between.contains("}}")) {
                throw new IllegalArgumentException("模板变量括号不完整");
            }
            String name = matcher.group(1);
            if (!ALLOWED_VARIABLES.contains(name)) {
                throw new IllegalArgumentException("不支持的模板变量: " + name);
            }
            end = matcher.end();
        }
        String tail = content.substring(end);
        if (tail.contains("{{") || tail.contains("}}")) {
            throw new IllegalArgumentException("模板变量括号不完整");
        }
    }

    private String renderOne(String content, Map<String, String> values) {
        Matcher matcher = VARIABLE.matcher(content);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String value = values.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private Map<String, String> values(DeliveryTemplateRenderContext context) {
        DeliveryTemplateRenderContext safe = context == null
                ? DeliveryTemplateRenderContext.builder().build() : context;
        Map<String, String> values = new HashMap<>();
        values.put("order_id", safe.getOrderId());
        values.put("item_id", safe.getItemId());
        values.put("item_title", safe.getItemTitle());
        values.put("buyer_id", safe.getBuyerId());
        values.put("buyer_nick", safe.getBuyerNick());
        values.put("card_name", safe.getCardName());
        values.replaceAll((key, value) -> value == null ? "" : value);
        return values;
    }

    private String normalizeType(String type) {
        return type == null || type.isBlank() ? "TEXT" : type.trim().toUpperCase();
    }
}
