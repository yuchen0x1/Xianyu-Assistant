package com.feijimiao.xianyuassistant.command;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 命令参数读取工具：统一的类型转换与必填校验。
 */
final class CommandArgs {

    private CommandArgs() {
    }

    static Object require(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new CommandExecutionException("缺少必填参数: " + name);
        }
        return value;
    }

    static Long getLong(Map<String, Object> args, String name, boolean required) {
        Object value = args.get(name);
        if (value == null) {
            if (required) {
                throw new CommandExecutionException("缺少必填参数: " + name);
            }
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new CommandExecutionException("参数 " + name + " 不是合法的数字: " + value);
        }
    }

    static Integer getInteger(Map<String, Object> args, String name, boolean required) {
        Long value = getLong(args, name, required);
        return value == null ? null : value.intValue();
    }

    static String getString(Map<String, Object> args, String name, boolean required) {
        Object value = args.get(name);
        if (value == null) {
            if (required) {
                throw new CommandExecutionException("缺少必填参数: " + name);
            }
            return null;
        }
        return value.toString();
    }

    static Boolean getBoolean(Map<String, Object> args, String name, boolean required) {
        Object value = args.get(name);
        if (value == null) {
            if (required) {
                throw new CommandExecutionException("缺少必填参数: " + name);
            }
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = value.toString().trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
            return false;
        }
        throw new CommandExecutionException("参数 " + name + " 不是合法的布尔值: " + value);
    }

    static BigDecimal getBigDecimal(Map<String, Object> args, String name, boolean required) {
        Object value = args.get(name);
        if (value == null) {
            if (required) {
                throw new CommandExecutionException("缺少必填参数: " + name);
            }
            return null;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new CommandExecutionException("参数 " + name + " 不是合法的金额: " + value);
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> getStringList(Map<String, Object> args, String name, boolean required) {
        Object value = args.get(name);
        if (value == null) {
            if (required) {
                throw new CommandExecutionException("缺少必填参数: " + name);
            }
            return Collections.emptyList();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toList());
        }
        // 兼容逗号分隔字符串
        String s = value.toString().trim();
        if (s.isEmpty()) {
            return Collections.emptyList();
        }
        return java.util.Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());
    }
}
