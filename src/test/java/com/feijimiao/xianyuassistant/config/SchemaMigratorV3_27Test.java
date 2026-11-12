package com.feijimiao.xianyuassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigratorV3_27Test {

    private String migrationContent() throws Exception {
        Path migration = Path.of("target", "classes", "db", "migration", "V3_27__delivery_templates.sql")
                .toAbsolutePath();
        assertThat(migration).exists();
        return Files.readString(migration, StandardCharsets.UTF_8);
    }

    @Test
    void migrationCreatesTemplateTablesAndBindsConfigColumn() throws Exception {
        String content = migrationContent();
        assertThat(content)
                .contains("CREATE TABLE IF NOT EXISTS xianyu_delivery_template")
                .contains("CREATE TABLE IF NOT EXISTS xianyu_delivery_template_message")
                .contains("ADD COLUMN delivery_template_id BIGINT")
                .contains("uk_delivery_template_message_order");
    }

    @Test
    void migrationStatementsAreSplitWithoutLosingTriggerBoundaries() throws Exception {
        SchemaMigrator migrator = new SchemaMigrator();
        @SuppressWarnings("unchecked")
        List<String> statements = (List<String>) ReflectionTestUtils.invokeMethod(
                migrator, "splitStatements", migrationContent());
        assertThat(statements).hasSize(8);
        assertThat(statements.get(0)).contains("xianyu_delivery_template");
        assertThat(statements.get(6)).contains("delivery_template_id");
    }
}
