package com.feijimiao.xianyuassistant.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * SQLite LocalDateTime TypeHandler。
 * <p>
 * SQLite 没有原生 DATETIME 类型，时间统一以 TEXT 存储（格式：yyyy-MM-dd HH:mm:ss）。
 * 兼容格式 yyyy-MM-dd'T'HH:mm:ss 的读取。
 * </p>
 */
public class SqliteLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

    private static final DateTimeFormatter STORAGE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.format(STORAGE_FORMATTER));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String value = rs.getString(columnName);
        return parse(value);
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String value = rs.getString(columnIndex);
        return parse(value);
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String value = cs.getString(columnIndex);
        return parse(value);
    }

    private static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, STORAGE_FORMATTER);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value, ISO_FORMATTER);
            } catch (DateTimeParseException e2) {
                // 最后尝试直接解析（兼容 Java 默认格式）
                return LocalDateTime.parse(value);
            }
        }
    }
}
