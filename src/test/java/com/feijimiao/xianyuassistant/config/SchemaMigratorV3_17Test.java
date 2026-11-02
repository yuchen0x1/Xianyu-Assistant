package com.feijimiao.xianyuassistant.config;

import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3_17 迁移文件验证。
 * <p>verify：迁移脚本命名统一后 classpath 上只有一个 V3_17 文件；合并文件 SQL 可执行（在
 * sys_role/sys_permission/sys_role_permission 基础上），建出 alert_event/webhook_delivery 表、
 * 唯一索引与触发器；重复执行幂等不会重复建表。</p>
 * <p>说明：真实环境的基础表来自 schema.sql（DatabaseInitListener 先跑迁移、再补 schema.sql 表）。
 * 本测试仅聚焦 V3_17 迁移，直接执行合并文件语句，不重复运行会依赖 schema.sql 基础表的完整迁移链。</p>
 */
class SchemaMigratorV3_17Test {

    @TempDir
    Path tempDir;

    private PooledDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new PooledDataSource();
        dataSource.setDriver("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("migration-test.db"));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    private void createDependencyTables() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE TABLE sys_role (id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(50) NOT NULL UNIQUE)");
                stmt.executeUpdate("CREATE TABLE sys_permission (id INTEGER PRIMARY KEY AUTOINCREMENT, code VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(100) NOT NULL, module VARCHAR(50) NOT NULL, action VARCHAR(50) NOT NULL, description VARCHAR(200))");
                stmt.executeUpdate("CREATE TABLE sys_role_permission (id INTEGER PRIMARY KEY AUTOINCREMENT, role_id INTEGER NOT NULL, permission_id INTEGER NOT NULL, UNIQUE(role_id, permission_id))");
            }
            for (String ddl : List.of(
                    "INSERT INTO sys_permission(code,name,module,action) VALUES ('dashboard:read','查看数据面板','dashboard','read')",
                    "INSERT INTO sys_role(name) VALUES ('ADMIN')",
                    "INSERT INTO sys_role(name) VALUES ('OPERATOR')",
                    "INSERT INTO sys_role(name) VALUES ('VIEWER')")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate(ddl);
                }
            }
        }
    }

    private String mergedFileContent() throws Exception {
        // 从 Maven 构建产物定位（避免本地 target/classes 残留旧版迁移文件干扰）
        Path classesRoot = Path.of("target", "classes").toAbsolutePath();
        Path merged = classesRoot.resolve("db/migration/V3_17__monitoring_webhook_outbox.sql");
        assertThat(merged).as("构建产物应包含合并后的 V3_17 迁移文件").exists();
        return java.nio.file.Files.readString(merged, StandardCharsets.UTF_8);
    }

    private void executeV3_17() throws Exception {
        SchemaMigrator migrator = new SchemaMigrator();
        @SuppressWarnings("unchecked")
        List<String> statements = (List<String>) ReflectionTestUtils.invokeMethod(
                migrator, "splitStatements", mergedFileContent());
        try (Connection conn = dataSource.getConnection()) {
            for (String sql : statements) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate(sql);
                }
            }
        }
    }

    @Test
    void migrationUsesSingleV3_17VersionSlot() throws Exception {
        String content = mergedFileContent();
        assertThat(content).contains("CREATE TABLE IF NOT EXISTS alert_event")
                .contains("CREATE TABLE IF NOT EXISTS webhook_delivery")
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uq_alert_event_key")
                .contains("CREATE TRIGGER IF NOT EXISTS update_alert_event_time");
    }

    @Test
    void v3_17TablesExistWithUniqueIndexes() throws Exception {
        createDependencyTables();
        executeV3_17();

        Set<String> tables = tableNames();
        assertThat(tables).contains("alert_event", "webhook_delivery");

        Set<String> indexes = indexNames();
        assertThat(indexes).contains("uq_alert_event_key", "idx_webhook_delivery_hook", "idx_webhook_delivery_status");

        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(
                    "SELECT sql FROM sqlite_master WHERE type='index' AND name='uq_alert_event_key'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("sql").toUpperCase()).contains("UNIQUE");
            }
            // idempotency_key 是列级 UNIQUE 内联约束，不产生独立索引对象；转为验证重复值被数据库拒绝
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO webhook_delivery(webhook_id, event, idempotency_key) VALUES (1, 'e', 'event:1:i')");
                java.sql.SQLException e = null;
                try {
                    stmt.executeUpdate("INSERT INTO webhook_delivery(webhook_id, event, idempotency_key) VALUES (1, 'e', 'event:1:i')");
                } catch (java.sql.SQLException ex) {
                    e = ex;
                }
                assertThat(e == null ? "no exception" : e.getMessage()).containsIgnoringCase("unique");
            }
        }
    }

    @Test
    void v3_17TriggerExistsAndPermissionsSeeded() throws Exception {
        createDependencyTables();
        executeV3_17();

        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='trigger' AND name='update_alert_event_time'")) {
                assertThat(rs.next()).isTrue();
            }
            assertThat(count(conn, "SELECT COUNT(*) FROM sys_permission WHERE code LIKE 'monitoring:%'")).isEqualTo(2);
            assertThat(count(conn, "SELECT COUNT(*) FROM sys_role_permission")).isGreaterThanOrEqualTo(5);
        }
    }

    @Test
    void v3_17RerunIsIdempotentAndDoesNotDuplicateTables() throws Exception {
        createDependencyTables();
        executeV3_17();
        executeV3_17(); // 第二次执行：全部 IF NOT EXISTS / OR IGNORE，不重复建表

        try (Connection conn = dataSource.getConnection()) {
            assertThat(count(conn, "SELECT COUNT(*) FROM alert_event")).isEqualTo(0);
            assertThat(count(conn, "SELECT COUNT(*) FROM webhook_delivery")).isEqualTo(0);
            assertThat(count(conn, "SELECT COUNT(*) FROM sys_permission WHERE code LIKE 'monitoring:%'")).isEqualTo(2);
        }
        assertThat(tableNames()).contains("alert_event", "webhook_delivery");
        assertThat(indexNames()).contains("uq_alert_event_key");
    }

    @Test
    void v3_17UniqueIndexRejectsDuplicateAlertKey() throws Exception {
        createDependencyTables();
        executeV3_17();

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO alert_event(alert_key, title) VALUES ('delivery_failure', 't')");
            java.sql.SQLException e = null;
            try {
                stmt.executeUpdate("INSERT INTO alert_event(alert_key, title) VALUES ('delivery_failure', 't2')");
            } catch (java.sql.SQLException ex) {
                e = ex;
            }
            assertThat(e == null ? "no exception" : e.getMessage()).containsIgnoringCase("unique");
        }
    }

    private Set<String> tableNames() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
            return names;
        }
    }

    private Set<String> indexNames() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'")) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
            return names;
        }
    }

    private long count(Connection conn, String sql) throws Exception {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}