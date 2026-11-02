package com.feijimiao.xianyuassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3_23 自动回复决策日志迁移验证：
 * <p>迁移文件占据独立版本位、建表与索引语句可被切分器解析，且与 schema.sql 全量定义一致；
 * 另用内存 SQLite 实际执行建表 DDL 与去重窗口 SQL（datetime 修饰符表达式），验证运行时行为。</p>
 */
class SchemaMigratorV3_23Test {

    private static final String MIGRATION_NAME = "db/migration/V3_23__auto_reply_decision.sql";

    private String migrationContent() throws Exception {
        Path migration = Path.of("target", "classes").toAbsolutePath().resolve(MIGRATION_NAME);
        assertThat(migration).as("构建产物应包含 V3_23 迁移文件").exists();
        return java.nio.file.Files.readString(migration, StandardCharsets.UTF_8);
    }

    private List<String> split(String content) {
        SchemaMigrator migrator = new SchemaMigrator();
        @SuppressWarnings("unchecked")
        List<String> statements = (List<String>) ReflectionTestUtils.invokeMethod(migrator, "splitStatements", content);
        return statements;
    }

    @Test
    void 迁移应创建决策日志表与索引() throws Exception {
        String content = migrationContent();
        assertThat(content)
                .contains("CREATE TABLE IF NOT EXISTS auto_reply_decision")
                .contains("reason_code VARCHAR(50) NOT NULL")
                .contains("datetime('now', 'localtime')")
                .contains("CREATE INDEX IF NOT EXISTS idx_auto_reply_decision_time")
                .contains("CREATE INDEX IF NOT EXISTS idx_auto_reply_decision_session");
    }

    @Test
    void schema与迁移的决策日志定义一致() throws Exception {
        Path schema = Path.of("target", "classes", "sql", "schema.sql").toAbsolutePath();
        assertThat(schema).as("构建产物应包含 schema.sql").exists();
        String content = java.nio.file.Files.readString(schema, StandardCharsets.UTF_8);
        assertThat(content)
                .contains("CREATE TABLE IF NOT EXISTS auto_reply_decision")
                .contains("idx_auto_reply_decision_time")
                .contains("idx_auto_reply_decision_session");
    }

    @Test
    void 语句切分器应产出预期数量的语句() throws Exception {
        List<String> statements = split(migrationContent());
        // 1 建表 + 2 索引
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).contains("auto_reply_decision");
    }

    @Test
    @SuppressWarnings("SqlSourceToSinkFlow")
    void 去重窗口SQL应在真实SQLite上执行() throws Exception {
        List<String> statements = split(migrationContent());
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement stmt = conn.createStatement()) {
                for (String sql : statements) {
                    stmt.execute(sql);
                }
                stmt.execute("INSERT INTO auto_reply_decision (xianyu_account_id, s_id, reason_code) "
                        + "VALUES (7, 's-1', 'HUMAN_TAKEOVER')");
            }
            // 与 AutoReplyDecisionMapper.countRecent 相同的 datetime 修饰符表达式（负偏移=回看窗口）
            String countRecent = "SELECT COUNT(*) FROM auto_reply_decision "
                    + "WHERE xianyu_account_id = ? AND s_id = ? AND reason_code = ? "
                    + "AND create_time >= datetime('now', 'localtime', '-' || ? || ' minutes')";
            try (PreparedStatement ps = conn.prepareStatement(countRecent)) {
                ps.setLong(1, 7L);
                ps.setString(2, "s-1");
                ps.setString(3, "HUMAN_TAKEOVER");
                ps.setInt(4, 60);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).as("60 分钟窗口内应命中刚插入的记录").isEqualTo(1);
                }
                // 时间倒序索引列可用性：ORDER BY create_time DESC 不报错即可
                try (Statement check = conn.createStatement();
                     ResultSet rs = check.executeQuery(
                             "SELECT * FROM auto_reply_decision ORDER BY create_time DESC, id DESC LIMIT 1")) {
                    assertThat(rs.next()).isTrue();
                }
            }
        }
    }
}
