package com.feijimiao.xianyuassistant.mapper;

import com.feijimiao.xianyuassistant.service.DataPanelAccountScope;
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

class XianyuGoodsOrderMapperScopeSqlTest {

    @TempDir
    Path tempDir;

    private PooledDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PooledDataSource();
        dataSource.setDriver("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("order-scope.db"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE xianyu_goods_order (id INTEGER PRIMARY KEY, xianyu_account_id BIGINT, xianyu_goods_id TEXT, xy_goods_id TEXT, pnm_id TEXT, order_id TEXT, buyer_user_id TEXT, buyer_user_name TEXT, sid TEXT, content TEXT, state INTEGER, fail_reason TEXT, confirm_state INTEGER, goods_title TEXT, sku_name TEXT, order_create_time TEXT, pay_success_time TEXT, consign_time TEXT, total_price TEXT, buy_num INTEGER, create_time TEXT)");
            statement.executeUpdate("CREATE TABLE xianyu_goods (xy_good_id TEXT PRIMARY KEY, title TEXT)");
            statement.executeUpdate("INSERT INTO xianyu_goods_order (id, xianyu_account_id, xy_goods_id, order_id, state, buyer_user_name, create_time) VALUES (1, 10, 'g1', 'o1', 1, 'buyer1', '2026-11-01 00:00:00'), (2, 20, 'g2', 'o2', 1, 'buyer2', '2026-11-02 00:00:00')");
            statement.executeUpdate("INSERT INTO xianyu_goods (xy_good_id, title) VALUES ('g1', 'good1'), ('g2', 'good2')");
        }
        Environment environment = new Environment("order-scope-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(XianyuGoodsOrderMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    @Test
    void restrictedScopeFiltersListAndCountAndIntersectsExplicitAccount() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            XianyuGoodsOrderMapper mapper = session.getMapper(XianyuGoodsOrderMapper.class);
            DataPanelAccountScope restricted = DataPanelAccountScope.restricted(100L, List.of(10L));

            assertThat(mapper.countByConditionScoped(null, null, null, null, restricted)).isEqualTo(1);
            assertThat(mapper.selectByConditionWithPageScoped(null, null, null, null, 20, 0, restricted))
                    .extracting("xianyuAccountId")
                    .containsExactly(10L);
            assertThat(mapper.countByConditionScoped(20L, null, null, null, restricted)).isZero();
            assertThat(mapper.selectByConditionWithPageScoped(20L, null, null, null, 20, 0, restricted))
                    .isEmpty();
        }
    }

    @Test
    void emptyScopeReturnsNoRowsWhileAllScopeKeepsAdminVisibility() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            XianyuGoodsOrderMapper mapper = session.getMapper(XianyuGoodsOrderMapper.class);
            DataPanelAccountScope empty = DataPanelAccountScope.restricted(100L, List.of());
            DataPanelAccountScope all = DataPanelAccountScope.all(1L);

            assertThat(mapper.countByConditionScoped(null, null, null, null, empty)).isZero();
            assertThat(mapper.selectByConditionWithPageScoped(null, null, null, null, 20, 0, empty)).isEmpty();
            assertThat(mapper.countByConditionScoped(null, null, null, null, all)).isEqualTo(2);
            assertThat(mapper.selectByConditionWithPageScoped(null, null, null, null, 20, 0, all)).hasSize(2);
        }
    }
}
