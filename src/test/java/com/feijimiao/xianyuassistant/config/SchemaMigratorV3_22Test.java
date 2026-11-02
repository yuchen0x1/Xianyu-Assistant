package com.feijimiao.xianyuassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3_22 议价迁移验证（内容级）：
 * <p>迁移文件占据独立版本位、包含商品配置议价字段的 ADD COLUMN、bargain_session 建表与唯一索引，
 * 且与 schema.sql 的全量建表定义保持一致；语句切分器可正确解析（触发器块外按分号切分）。</p>
 * <p>说明：迁移语句在真实启动时由 SchemaMigrator 对 SQLite 执行（DatabaseInitListener 链路），
 * 此处聚焦文件内容与结构约束，避免在测试中拼装执行动态 SQL。</p>
 */
class SchemaMigratorV3_22Test {

    private static final String MIGRATION_NAME = "db/migration/V3_22__bargain_strategy.sql";

    private String migrationContent() throws Exception {
        Path migration = Path.of("target", "classes").toAbsolutePath().resolve(MIGRATION_NAME);
        assertThat(migration).as("构建产物应包含 V3_22 迁移文件").exists();
        return java.nio.file.Files.readString(migration, StandardCharsets.UTF_8);
    }

    private List<String> split(String content) {
        SchemaMigrator migrator = new SchemaMigrator();
        @SuppressWarnings("unchecked")
        List<String> statements = (List<String>) ReflectionTestUtils.invokeMethod(migrator, "splitStatements", content);
        return statements;
    }

    @Test
    void 迁移应包含商品配置议价字段() throws Exception {
        String content = migrationContent();
        assertThat(content)
                .contains("ALTER TABLE xianyu_goods_config ADD COLUMN bargain_on")
                .contains("ALTER TABLE xianyu_goods_config ADD COLUMN bargain_floor_price")
                .contains("ALTER TABLE xianyu_goods_config ADD COLUMN bargain_step_price")
                .contains("ALTER TABLE xianyu_goods_config ADD COLUMN bargain_max_rounds");
    }

    @Test
    void 迁移应创建议价会话表与唯一索引() throws Exception {
        String content = migrationContent();
        assertThat(content)
                .contains("CREATE TABLE IF NOT EXISTS bargain_session")
                .contains("UNIQUE (xianyu_account_id, s_id)")
                .contains("CREATE INDEX IF NOT EXISTS idx_bargain_session_goods")
                .contains("rounds_used")
                .contains("last_bargain_at");
    }

    @Test
    void schema与迁移的议价定义应一致() throws Exception {
        Path schema = Path.of("target", "classes", "sql", "schema.sql").toAbsolutePath();
        assertThat(schema).as("构建产物应包含 schema.sql").exists();
        String content = java.nio.file.Files.readString(schema, StandardCharsets.UTF_8);
        assertThat(content)
                .contains("bargain_on TINYINT DEFAULT 0")
                .contains("bargain_floor_price TEXT")
                .contains("bargain_step_price TEXT")
                .contains("bargain_max_rounds INTEGER DEFAULT 3")
                .contains("CREATE TABLE IF NOT EXISTS bargain_session")
                .contains("UNIQUE (xianyu_account_id, s_id)");
    }

    @Test
    void 语句切分器应产出预期数量的语句() throws Exception {
        List<String> statements = split(migrationContent());
        // 4 条 ADD COLUMN + 1 建表 + 1 索引
        assertThat(statements).hasSize(6);
        assertThat(statements.get(0)).contains("bargain_on");
        assertThat(statements.get(4)).contains("bargain_session");
        assertThat(statements.get(5)).contains("idx_bargain_session_goods");
    }
}
