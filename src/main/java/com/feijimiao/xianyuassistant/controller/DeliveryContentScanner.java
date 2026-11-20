package com.feijimiao.xianyuassistant.controller;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic delivery-text scanning: no backtracking over user or model text.
 * ASCII literals remain protected; sensitive runs contain a digit, an email/domain, or a labelled value.
 */
final class DeliveryContentScanner {
    private static final List<String> LABELS = List.of("卡密", "提取码", "账号", "密码", "订单号", "兑换码");
    private static final String ASCII_LITERAL_SYMBOLS = "._~:/?#[]@!$&'()*+,;=%+\\-";

    private DeliveryContentScanner() {}

    record Fragment(int start, int end) {
        String value(String text) {
            return text.substring(start, end);
        }
    }

    static List<Fragment> scan(String text, boolean sensitiveOnly) {
        List<Fragment> result = new ArrayList<>();
        int position = 0;
        while (position < text.length()) {
            int end = urlEnd(text, position);
            if (end == position) {
                end = placeholderEnd(text, position);
            }
            if (end == position) {
                end = labelledValueEnd(text, position);
            }
            if (end > position) {
                result.add(new Fragment(position, end));
                position = end;
            } else if (isAsciiAlphanumeric(text.charAt(position))) {
                end = position + 1;
                while (end < text.length() && isAsciiLiteral(text.charAt(end))) {
                    end++;
                }
                if (!sensitiveOnly || isSensitiveAscii(text, position, end)) {
                    result.add(new Fragment(position, end));
                }
                position = end;
            } else {
                position++;
            }
        }
        return result;
    }

    private static int urlEnd(String text, int start) {
        int prefixLength = text.regionMatches(true, start, "https://", 0, 8) ? 8
                : text.regionMatches(true, start, "http://", 0, 7) ? 7
                : text.regionMatches(true, start, "www.", 0, 4) ? 4 : 0;
        if (prefixLength == 0) {
            return start;
        }
        int end = start + prefixLength;
        while (end < text.length() && !isSpace(text.charAt(end))
                && "，。；！？".indexOf(text.charAt(end)) < 0) {
            end++;
        }
        return end > start + prefixLength ? end : start;
    }

    private static int placeholderEnd(String text, int start) {
        if (text.charAt(start) != '{') {
            return start;
        }
        int end = start + 1;
        while (end < text.length() && "{}\r\n".indexOf(text.charAt(end)) < 0) {
            end++;
        }
        return end > start + 1 && end < text.length() && text.charAt(end) == '}' ? end + 1 : start;
    }

    private static int labelSeparatorEnd(String text, int start) {
        for (String label : LABELS) {
            if (!text.startsWith(label, start)) {
                continue;
            }
            int end = start + label.length();
            int separatorStart = end;
            while (end < text.length() && isSpace(text.charAt(end))) {
                end++;
            }
            if (end < text.length() && (text.charAt(end) == ':' || text.charAt(end) == '：')) {
                end++;
                while (end < text.length() && isSpace(text.charAt(end))) {
                    end++;
                }
            }
            return end > separatorStart ? end : start;
        }
        return start;
    }

    private static int labelledValueEnd(String text, int start) {
        int valueStart = labelSeparatorEnd(text, start);
        if (valueStart == start || valueStart == text.length()) {
            return start;
        }
        for (int end = valueStart; end < text.length(); end++) {
            char current = text.charAt(end);
            if ("\r\n，。；！？;".indexOf(current) >= 0) {
                return end > valueStart ? end : start;
            }
            if (",.!?".indexOf(current) >= 0
                    && (end + 1 == text.length() || isSpace(text.charAt(end + 1)))) {
                return end > valueStart ? end : start;
            }
            if (isSpace(current)) {
                int next = end + 1;
                while (next < text.length() && isSpace(text.charAt(next))) {
                    next++;
                }
                if (labelSeparatorEnd(text, next) > next) {
                    return end > valueStart ? end : start;
                }
                end = next - 1;
            }
        }
        return text.length();
    }

    private static boolean isSensitiveAscii(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char current = text.charAt(i);
            // Conservative: any email-like run is protected from model additions, even if malformed.
            if (current >= '0' && current <= '9' || current == '@') {
                return true;
            }
            if (current == '.' && i > start && isAsciiAlphanumeric(text.charAt(i - 1))) {
                int suffix = i + 1;
                while (suffix < end && isAsciiLetter(text.charAt(suffix))) {
                    suffix++;
                }
                if (suffix - i > 2) {
                    return true;
                }
                i = suffix - 1;
            }
        }
        return false;
    }

    private static boolean isAsciiAlphanumeric(char value) {
        return isAsciiLetter(value) || value >= '0' && value <= '9';
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private static boolean isAsciiLiteral(char value) {
        return isAsciiAlphanumeric(value) || ASCII_LITERAL_SYMBOLS.indexOf(value) >= 0;
    }

    private static boolean isSpace(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f' || value == 0x0b;
    }
}
