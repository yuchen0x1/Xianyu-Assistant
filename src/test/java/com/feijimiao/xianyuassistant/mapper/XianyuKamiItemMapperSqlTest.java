package com.feijimiao.xianyuassistant.mapper;

import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;
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

class XianyuKamiItemMapperSqlTest {

    @TempDir
    Path tempDir;

    private PooledDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PooledDataSource();
        dataSource.setDriver("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("kami-mapper.db"));

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE xianyu_kami_item (
                        id INTEGER PRIMARY KEY,
                        kami_config_id BIGINT NOT NULL,
                        kami_content TEXT NOT NULL,
                        status INTEGER NOT NULL,
                        order_id TEXT,
                        used_time TEXT,
                        sort_order INTEGER,
                        create_time TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE xianyu_kami_usage_record (
                        id INTEGER PRIMARY KEY,
                        kami_item_id BIGINT NOT NULL,
                        order_id TEXT,
                        create_time TEXT
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO xianyu_kami_item
                        (id, kami_config_id, kami_content, status, order_id, used_time, sort_order, create_time)
                    VALUES
                        (77, 66, 'first-code', 1, 'order-1', '2026-01-01 00:00:02', 1, '2026-01-01 00:00:00'),
                        (78, 67, 'second-code', 1, 'order-1', '2026-01-01 00:00:03', 1, '2026-01-01 00:00:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO xianyu_kami_usage_record (id, kami_item_id, order_id, create_time)
                    VALUES
                        (100, 77, 'order-1', '2026-01-01 00:00:01'),
                        (101, 78, 'order-1', '2026-01-01 00:00:02')
                    """);
        }

        Environment environment = new Environment(
                "mapper-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(XianyuKamiItemMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    @Test
    void findsLegacyReservationsInStableOrderAndMigratesOnlyTheMatchingOldValue() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            XianyuKamiItemMapper mapper = session.getMapper(XianyuKamiItemMapper.class);

            List<XianyuKamiItem> beforeMigration = mapper.findOrderReservations(
                    List.of(66L, 67L), "order-1", "order-1#delivery-");
            int migrated = mapper.migrateLegacyReservation(
                    78L, "order-1", "order-1#delivery-2");
            int staleMigration = mapper.migrateLegacyReservation(
                    78L, "order-1", "order-1#delivery-1");
            session.commit();

            List<XianyuKamiItem> afterMigration = mapper.findOrderReservations(
                    List.of(66L, 67L), "order-1", "order-1#delivery-");

            assertThat(beforeMigration).extracting(XianyuKamiItem::getId)
                    .containsExactly(77L, 78L);
            assertThat(migrated).isEqualTo(1);
            assertThat(staleMigration).isZero();
            assertThat(afterMigration).extracting(XianyuKamiItem::getId)
                    .containsExactly(77L, 78L);
            assertThat(afterMigration.get(1).getOrderId()).isEqualTo("order-1#delivery-2");
        }
    }
}
