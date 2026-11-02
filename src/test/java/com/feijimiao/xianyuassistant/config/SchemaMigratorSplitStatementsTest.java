package com.feijimiao.xianyuassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigratorSplitStatementsTest {

    private final SchemaMigrator migrator = new SchemaMigrator();

    @SuppressWarnings("unchecked")
    private List<String> split(String sql) {
        return (List<String>) ReflectionTestUtils.invokeMethod(migrator, "splitStatements", sql);
    }

    @Test
    void splitsSimpleStatementsOnSemicolons() {
        List<String> statements = split("""
                CREATE TABLE t (id INTEGER PRIMARY KEY);
                INSERT INTO t(id) VALUES (1);
                """);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("CREATE TABLE t");
        assertThat(statements.get(1)).contains("INSERT INTO t");
    }

    @Test
    void keepsTriggerBodyTogetherAcrossInnerSemicolons() {
        List<String> statements = split("""
                CREATE TRIGGER IF NOT EXISTS update_sys_role_time
                AFTER UPDATE ON sys_role
                BEGIN
                    UPDATE sys_role SET updated_time = CURRENT_TIMESTAMP WHERE id = NEW.id;
                END;
                INSERT OR IGNORE INTO sys_permission(code) VALUES ('dashboard:read');
                """);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0))
                .contains("CREATE TRIGGER IF NOT EXISTS update_sys_role_time")
                .contains("END;");
        assertThat(statements.get(1)).contains("INSERT OR IGNORE INTO sys_permission");
    }

    @Test
    void multipleTriggersAreSplitAsWholeBlocks() {
        List<String> statements = split("""
                CREATE TRIGGER IF NOT EXISTS t1
                AFTER UPDATE ON a
                BEGIN
                    UPDATE a SET v = 1 WHERE id = NEW.id;
                END;
                CREATE TRIGGER IF NOT EXISTS t2
                AFTER UPDATE ON b
                BEGIN
                    UPDATE b SET v = 2 WHERE id = NEW.id;
                END;
                """);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("ON a").doesNotContain("ON b");
        assertThat(statements.get(1)).contains("ON b");
    }

    @Test
    void commentLinesInsideTriggerAreKeptInBlock() {
        List<String> statements = split("""
                CREATE TRIGGER IF NOT EXISTS t1
                AFTER UPDATE ON a
                BEGIN
                    -- 内部注释不产生独立语句
                    UPDATE a SET v = 1 WHERE id = NEW.id;
                END;
                """);

        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).contains("UPDATE a SET v = 1");
    }
}
