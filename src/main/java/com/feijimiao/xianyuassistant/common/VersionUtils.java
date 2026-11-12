package com.feijimiao.xianyuassistant.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本号归一化与比较（供更新检查与在线更新共用）。
 */
public final class VersionUtils {

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^(\\d+(?:\\.\\d+){0,3})(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    private VersionUtils() {
    }

    /** 归一化版本号，并保留 SemVer 预发布后缀（如 v3.6.0-beta.2 → 3.6.0-beta.2）。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim().replaceFirst("^[vV]\\.?", "");
        Matcher matcher = VERSION_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return "";
        }
        String prerelease = matcher.group(2);
        return matcher.group(1) + (prerelease == null ? "" : "-" + prerelease);
    }

    /** 按 SemVer 核心版本与预发布标识比较；无法解析的版本视为相等。 */
    public static int compare(String v1, String v2) {
        String normalized1 = normalize(v1);
        String normalized2 = normalize(v2);
        if (normalized1.isBlank() || normalized2.isBlank()) {
            return 0;
        }
        ParsedVersion parsed1 = parse(normalized1);
        ParsedVersion parsed2 = parse(normalized2);
        if (parsed1 == null || parsed2 == null) {
            return 0;
        }
        String[] parts1 = parsed1.core().split("\\.");
        String[] parts2 = parsed2.core().split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }
        if (parsed1.prerelease().isBlank() && parsed2.prerelease().isBlank()) {
            return 0;
        }
        if (parsed1.prerelease().isBlank()) {
            return 1;
        }
        if (parsed2.prerelease().isBlank()) {
            return -1;
        }
        String[] identifiers1 = parsed1.prerelease().split("\\.");
        String[] identifiers2 = parsed2.prerelease().split("\\.");
        for (int i = 0; i < Math.min(identifiers1.length, identifiers2.length); i++) {
            String identifier1 = identifiers1[i];
            String identifier2 = identifiers2[i];
            boolean numeric1 = identifier1.matches("\\d+");
            boolean numeric2 = identifier2.matches("\\d+");
            if (numeric1 && numeric2) {
                int result = compareNumericIdentifier(identifier1, identifier2);
                if (result != 0) return result;
            } else if (numeric1 != numeric2) {
                return numeric1 ? -1 : 1;
            } else {
                int result = identifier1.compareTo(identifier2);
                if (result != 0) return result;
            }
        }
        return Integer.compare(identifiers1.length, identifiers2.length);
    }

    private static ParsedVersion parse(String normalized) {
        int separator = normalized.indexOf('-');
        return separator < 0
                ? new ParsedVersion(normalized, "")
                : new ParsedVersion(normalized.substring(0, separator), normalized.substring(separator + 1));
    }

    private static int compareNumericIdentifier(String value1, String value2) {
        try {
            return new java.math.BigInteger(value1).compareTo(new java.math.BigInteger(value2));
        } catch (NumberFormatException ignored) {
            return value1.compareTo(value2);
        }
    }

    private static int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private record ParsedVersion(String core, String prerelease) {
    }
}
