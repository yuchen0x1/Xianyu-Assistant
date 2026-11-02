package com.feijimiao.xianyuassistant.config;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SqliteJdbcUrlTest {

    @Test
    void removesConnectionOptionsFromDatabaseFilePath() {
        String filePath = SqliteJdbcUrl.filePath(
                "jdbc:sqlite:dbdata/xianyu_assistant.db?foreign_keys=on&busy_timeout=5000");

        assertThat(filePath)
                .isEqualTo("dbdata/xianyu_assistant.db");
        assertThatCode(() -> new File(filePath).getCanonicalPath()).doesNotThrowAnyException();
    }

    @Test
    void preservesDatabasePathWithoutConnectionOptions() {
        assertThat(SqliteJdbcUrl.filePath("jdbc:sqlite:C:/data/xianyu_assistant.db"))
                .isEqualTo("C:/data/xianyu_assistant.db");
    }
}
