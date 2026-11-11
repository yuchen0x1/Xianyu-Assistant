package com.feijimiao.xianyuassistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationYamlSecurityDefaultsTest {

    private static final String SQLITE_OPTIONS =
            "foreign_keys=on&busy_timeout=5000&journal_mode=WAL";

    @Test
    void applicationDefaultsStayLocalAndDoNotExposeSensitiveData() throws IOException {
        PropertySource<?> properties = applicationProperties();

        assertThat(properties.getProperty("server.address"))
                .isEqualTo("${XIANYU_ASSISTANT_BIND_ADDRESS:127.0.0.1}");
        assertThat(properties.getProperty("jwt.secret"))
                .isEqualTo("${XIANYU_ASSISTANT_JWT_SECRET:}");
        assertThat(properties.getProperty("jwt.secret-file"))
                .isEqualTo("${XIANYU_ASSISTANT_JWT_SECRET_FILE:dbdata/jwt-secret.key}");
        assertThat((String) properties.getProperty("app.version"))
                .doesNotContain("@project.version@");
        assertThat(properties.getProperty("websocket.print-raw-message"))
                .isEqualTo("${XIANYU_ASSISTANT_PRINT_RAW_MESSAGE:false}");
        assertThat(properties.getProperty("spring.task.scheduling.pool.size"))
                .isEqualTo("${XIANYU_ASSISTANT_SCHEDULING_POOL_SIZE:4}");
        assertThat(properties.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:sqlite:dbdata/xianyu_assistant.db?" + SQLITE_OPTIONS);

        // actuator 默认只暴露 health/info：metrics/prometheus 无需登录即可访问，
        // 必须显式设置 XIANYU_ASSISTANT_ACTUATOR_EXPOSED_ENDPOINTS 才开放。
        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("${XIANYU_ASSISTANT_ACTUATOR_EXPOSED_ENDPOINTS:health,info}");
    }

    @Test
    void configuredSqliteOptionsAreSupportedByTheBundledDriver(@TempDir Path tempDir)
            throws SQLException {
        String databaseUrl = "jdbc:sqlite:" + tempDir.resolve("config-test.db")
                + "?" + SQLITE_OPTIONS;

        try (Connection connection = DriverManager.getConnection(databaseUrl)) {
            assertThat(readPragma(connection, "foreign_keys")).isEqualTo("1");
            assertThat(readPragma(connection, "busy_timeout")).isEqualTo("5000");
            assertThat(readPragma(connection, "journal_mode")).isEqualToIgnoringCase("wal");
        }
    }

    private PropertySource<?> applicationProperties() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))
                .getFirst();
    }

    private String readPragma(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA " + pragma)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }
}
