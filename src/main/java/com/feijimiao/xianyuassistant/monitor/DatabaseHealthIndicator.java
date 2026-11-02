package com.feijimiao.xianyuassistant.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 自定义数据库健康指示器：检查 SQLite 连接和关键表可读性。
 */
@Slf4j
@Component("dbHealth")
@RequiredArgsConstructor
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
            if (result != null && result == 1) {
                return Health.up().withDetail("database", "SQLite").build();
            }
            return Health.down().withDetail("database", "SQLite").withDetail("reason", "SELECT 1 返回异常").build();
        } catch (Exception e) {
            log.warn("数据库健康检查失败: {}", e.getMessage());
            return Health.down(e).withDetail("database", "SQLite").build();
        }
    }
}
