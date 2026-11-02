package com.feijimiao.xianyuassistant.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本号归一化与比较（供更新检查与在线更新共用）。
 */
public final class VersionUtils {

    private VersionUtils() {
    }

    /**
     * 归一化版本号：去掉前缀 v/V/.，截取开头的数字段（如 v3.3.20 → 3.3.20）。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim().replaceFirst("^[vV]\\.?", "");
        Matcher matcher = Pattern.compile("^(\\d+(?:\\.\\d+){0,3})").matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * 按数字段比较版本；无法解析的版本视为相等（返回 0）。
     */
    public static int compare(String v1, String v2) {
        String normalized1 = normalize(v1);
        String normalized2 = normalize(v2);
        if (normalized1.isBlank() || normalized2.isBlank()) {
            return 0;
        }
        String[] parts1 = normalized1.split("\\.");
        String[] parts2 = normalized2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }
        return 0;
    }

    private static int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
