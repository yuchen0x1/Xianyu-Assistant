package com.feijimiao.xianyuassistant.config;

final class SqliteJdbcUrl {

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    private SqliteJdbcUrl() {
    }

    static String filePath(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("SQLite JDBC URL不能为空");
        }

        String pathWithOptions = jdbcUrl.startsWith(SQLITE_PREFIX)
                ? jdbcUrl.substring(SQLITE_PREFIX.length())
                : jdbcUrl;
        int queryStart = pathWithOptions.indexOf('?');
        return queryStart >= 0 ? pathWithOptions.substring(0, queryStart) : pathWithOptions;
    }
}
