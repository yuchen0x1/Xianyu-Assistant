package com.feijimiao.xianyuassistant.mapper;

import com.feijimiao.xianyuassistant.entity.XianyuNotification;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class XianyuNotificationMapperSqlTest {

    @TempDir
    Path tempDir;

    private PooledDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PooledDataSource();
        dataSource.setDriver("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("notification-mapper.db"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE xianyu_notification (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title VARCHAR(200) NOT NULL,
                        content TEXT,
                        notify_type VARCHAR(50),
                        level VARCHAR(20),
                        recipient_user_id BIGINT,
                        account_id BIGINT,
                        broadcast TINYINT DEFAULT 0,
                        read_status TINYINT DEFAULT 0,
                        created_time TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE notification_read_receipt (
                        notification_id INTEGER NOT NULL,
                        user_id BIGINT NOT NULL,
                        read_time TEXT,
                        PRIMARY KEY (notification_id, user_id)
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO xianyu_notification
                        (title, content, notify_type, level, broadcast, read_status, created_time)
                    VALUES ('广播', '内容', 'system', 'INFO', 1, 0, '2026-08-01 00:00:00')
                    """);
        }
        Environment environment = new Environment("mapper-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(XianyuNotificationMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    @Test
    void broadcastReadReceiptIsIsolatedPerUser() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            XianyuNotificationMapper mapper = session.getMapper(XianyuNotificationMapper.class);
            assertThat(mapper.countUnread(10L)).isEqualTo(1);
            assertThat(mapper.countUnread(20L)).isEqualTo(1);

            assertThat(mapper.markBroadcastRead(1L, 10L)).isEqualTo(1);

            List<XianyuNotification> userA = mapper.selectRecent(10L, 10);
            List<XianyuNotification> userB = mapper.selectRecent(20L, 10);
            assertThat(userA.get(0).getReadStatus()).isEqualTo(1);
            assertThat(userB.get(0).getReadStatus()).isEqualTo(0);
            assertThat(mapper.countUnread(10L)).isZero();
            assertThat(mapper.countUnread(20L)).isEqualTo(1);
        }
    }

    @Test
    void markAllBroadcastReadOnlyCreatesCurrentUserReceipts() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            XianyuNotificationMapper mapper = session.getMapper(XianyuNotificationMapper.class);
            assertThat(mapper.markAllBroadcastRead(10L)).isEqualTo(1);
            assertThat(mapper.countUnread(10L)).isZero();
            assertThat(mapper.countUnread(20L)).isEqualTo(1);
        }
    }
}
