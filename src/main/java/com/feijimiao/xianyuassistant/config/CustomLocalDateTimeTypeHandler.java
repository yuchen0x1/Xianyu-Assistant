package com.feijimiao.xianyuassistant.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;

/**
 * SQLite 时间字段自定义类型处理器。
 *
 * <p>写路径统一输出 {@code yyyy-MM-dd HH:mm:ss}（无毫秒）；
 * 读路径兼容历史数据中的毫秒/微秒格式（如 {@code 2026-07-31 15:51:15.473}），
 * 避免 DateTimeParseException 导致登录、备份等查询失败。</p>
 */
public class CustomLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 可选的 1-9 位小数秒，用于解析历史带毫秒数据。 */
    private static final DateTimeFormatter FLEXIBLE_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.format(FORMATTER));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseDateTime(rs.getString(columnName));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseDateTime(rs.getString(columnIndex));
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseDateTime(cs.getString(columnIndex));
    }

    private static LocalDateTime parseDateTime(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        String value = ts.trim();
        try {
            return LocalDateTime.parse(value, FORMATTER);
        } catch (DateTimeParseException ignored) {
            // 带毫秒/微秒的历史数据，走宽松格式
        }
        try {
            return LocalDateTime.parse(value, FLEXIBLE_FORMATTER);
        } catch (DateTimeParseException ignored) {
            // 极端脏数据兜底：截断到秒再解析
            String truncated = value.substring(0, Math.min(19, value.length()));
            return LocalDateTime.parse(truncated, FORMATTER);
        }
    }
}
