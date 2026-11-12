package com.feijimiao.xianyuassistant.service.reply;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 关键词规则匹配工具。
 *
 * <p>规则关键字支持半角/全角逗号分隔的多个表达式；匹配模式保持向后兼容：
 * 1=模糊、2=精准，新增 3=正则。正则表达式非法时只跳过当前规则，不能让消息处理链路失败。</p>
 */
public final class KeywordMatchUtils {

    public static final int FUZZY = 1;
    public static final int EXACT = 2;
    public static final int REGEX = 3;

    private KeywordMatchUtils() {
    }

    public static boolean matches(String configuredKeyword, Integer matchMode, String message) {
        if (configuredKeyword == null || configuredKeyword.isBlank()
                || message == null || message.isBlank()) {
            return false;
        }

        String normalizedMessage = message.trim();
        int mode = matchMode == null ? FUZZY : matchMode;
        return switch (mode) {
            case EXACT -> expressions(configuredKeyword).stream()
                    .anyMatch(expression -> expression.equals(normalizedMessage));
            case REGEX -> expressions(configuredKeyword).stream()
                    .anyMatch(expression -> matchesRegex(expression, normalizedMessage));
            case FUZZY -> expressions(configuredKeyword).stream()
                    .anyMatch(normalizedMessage::contains);
            default -> false;
        };
    }

    public static boolean isValidRegex(String configuredKeyword) {
        if (configuredKeyword == null || configuredKeyword.isBlank()) {
            return false;
        }
        try {
            expressions(configuredKeyword).forEach(Pattern::compile);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean matchesRegex(String expression, String message) {
        try {
            return Pattern.compile(expression).matcher(message).find();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    private static java.util.List<String> expressions(String configuredKeyword) {
        return Arrays.stream(configuredKeyword.split("[,，]"))
                .map(String::trim)
                .filter(expression -> !expression.isEmpty())
                .toList();
    }
}
