package com.feijimiao.xianyuassistant.mapper;

import com.feijimiao.xianyuassistant.entity.XianyuGoodsInfo;
import com.feijimiao.xianyuassistant.entity.XianyuPublishHistory;
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

class GoodsAndPublishScopeSqlTest {

    @TempDir
    Path tempDir;

    private PooledDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PooledDataSource();
        dataSource.setDriver("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("goods-publish-scope.db"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE xianyu_goods (id INTEGER PRIMARY KEY, xy_good_id TEXT, title TEXT, cover_pic TEXT, info_pic TEXT, detail_info TEXT, detail_url TEXT, xianyu_account_id BIGINT, sold_price TEXT, sku_count INTEGER, status INTEGER, created_time TEXT, updated_time TEXT)");
            statement.executeUpdate("CREATE TABLE xianyu_publish_history (id INTEGER PRIMARY KEY, account_id BIGINT, account_note TEXT, title TEXT, description TEXT, price TEXT, quantity INTEGER, image_count INTEGER, category_id TEXT, item_id TEXT, operation_type TEXT, status TEXT, error_message TEXT, raw_response TEXT, created_time TEXT)");
            statement.executeUpdate("INSERT INTO xianyu_goods (id, xy_good_id, title, xianyu_account_id, status, updated_time) VALUES (1, 'g1', 'good1', 10, 0, '2026-11-01 00:00:00'), (2, 'g2', 'good2', 20, 0, '2026-11-02 00:00:00')");
            statement.executeUpdate("INSERT INTO xianyu_publish_history (id, account_id, title) VALUES (1, 10, 'history1'), (2, 20, 'history2')");
        }
        Environment environment = new Environment("goods-publish-scope-test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(XianyuGoodsInfoMapper.class);
        configuration.addMapper(XianyuPublishHistoryMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    @Test
    void restrictedScopeFiltersGoodsListAndCountAndExplicitAccount() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            XianyuGoodsInfoMapper mapper = session.getMapper(XianyuGoodsInfoMapper.class);

            assertThat(mapper.countByAccountScope(0, null, List.of(10L))).isEqualTo(1);
            assertThat(mapper.selectByAccountScope(0, null, List.of(10L), 0, 20))
                    .extracting(XianyuGoodsInfo::getXianyuAccountId)
                    .containsExactly(10L);
            assertThat(mapper.countByAccountScope(0, 20L, List.of(10L))).isZero();
            assertThat(mapper.selectByAccountScope(0, 20L, List.of(10L), 0, 20)).isEmpty();
        }
    }

    @Test
    void emptyGoodsScopeReturnsNoRowsAndNullScopeKeepsAdminVisibility() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            XianyuGoodsInfoMapper mapper = session.getMapper(XianyuGoodsInfoMapper.class);

            assertThat(mapper.countByAccountScope(null, null, List.of())).isZero();
            assertThat(mapper.selectByAccountScope(null, null, List.of(), 0, 20)).isEmpty();
            assertThat(mapper.countByAccountScope(null, null, null)).isEqualTo(2);
            assertThat(mapper.selectByAccountScope(null, null, null, 0, 20)).hasSize(2);
        }
    }

    @Test
    void restrictedScopeFiltersPublishHistoryAndEmptyScopeReturnsNoRows() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            XianyuPublishHistoryMapper mapper = session.getMapper(XianyuPublishHistoryMapper.class);

            assertThat(mapper.selectByAccountScope(null, List.of(10L), 20))
                    .extracting(XianyuPublishHistory::getAccountId)
                    .containsExactly(10L);
            assertThat(mapper.selectByAccountScope(20L, List.of(10L), 20))
                    .extracting(XianyuPublishHistory::getAccountId)
                    .containsExactly(20L);
            assertThat(mapper.selectByAccountScope(null, List.of(), 20)).isEmpty();
            assertThat(mapper.selectByAccountScope(null, null, 20)).hasSize(2);
        }
    }
}
