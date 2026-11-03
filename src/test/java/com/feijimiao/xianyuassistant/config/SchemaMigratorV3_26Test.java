package com.feijimiao.xianyuassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigratorV3_26Test {

    private String migrationContent() throws Exception {
        Path migration = Path.of("target", "classes", "db", "migration", "V3_26__job_lease_fencing.sql")
                .toAbsolutePath();
        assertThat(migration).exists();
        return Files.readString(migration, StandardCharsets.UTF_8);
    }

    @Test
    void migrationAddsLeaseFencingToken() throws Exception {
        String content = migrationContent();
        assertThat(content)
                .contains("ALTER TABLE job_definition ADD COLUMN lease_token VARCHAR(100)");
    }

    @Test
    void migrationIsSplitIntoSingleStatement() throws Exception {
        SchemaMigrator migrator = new SchemaMigrator();
        @SuppressWarnings("unchecked")
        List<String> statements = (List<String>) ReflectionTestUtils.invokeMethod(
                migrator, "splitStatements", migrationContent());
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("lease_token");
    }
}
