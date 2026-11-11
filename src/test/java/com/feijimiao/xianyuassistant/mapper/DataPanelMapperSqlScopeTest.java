package com.feijimiao.xianyuassistant.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.feijimiao.xianyuassistant.service.DataPanelAccountScope;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配级回归测试：真正装配 MyBatis 并执行「带账号范围」的数据面板查询。
 *
 * <p>这一类缺陷的盲区在于：单元测试用 {@code ArgumentCaptor} 只能证明 scope <em>传出去了</em>，
 * 无法证明它<em>进了 SQL</em>。历史上 7 条查询就因此静默跨账号返回数据。本测试对每个查询
 * 断言三种范围语义：ADMIN（null=不过滤）、空范围（必须一条都看不到）、单账号（只能看到该账号）。
 */
class DataPanelMapperSqlScopeTest {

    private static final long AUTHORIZED_ACCOUNT = 7L;
    private static final long OTHER_ACCOUNT = 9L;

    private PooledDataSource dataSource;
    private SqlSession session;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new PooledDataSource();
        dataSource.setDriver("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("data-panel-scope.db"));

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // 消息表：账号 7 两条、账号 9 三条（用于区分跨账号泄漏）
            statement.executeUpdate("""
                    CREATE TABLE xianyu_chat_message (
                        id INTEGER PRIMARY KEY,
                        xianyu_account_id BIGINT NOT NULL,
                        xy_goods_id TEXT,
                        s_id TEXT,
                        content TEXT,
                        content_type INTEGER,
                        message_time TEXT,
                        sender_user_id TEXT,
                        pnm_id TEXT
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO xianyu_chat_message(xianyu_account_id, xy_goods_id)
                    VALUES (7, 'GOODS-7'), (7, 'GOODS-7'), (9, 'GOODS-9'), (9, 'GOODS-9'), (9, 'GOODS-9')
                    """);

            // 自动回复记录：用固定日期，避免依赖 now
            statement.executeUpdate("""
                    CREATE TABLE xianyu_goods_auto_reply_record (
                        id INTEGER PRIMARY KEY,
                        xianyu_account_id BIGINT,
                        xy_goods_id TEXT,
                        buyer_user_id TEXT,
                        matched_keyword TEXT,
                        state INTEGER,
                        create_time TEXT
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO xianyu_goods_auto_reply_record
                        (xianyu_account_id, xy_goods_id, buyer_user_id, matched_keyword, state, create_time)
                    VALUES
                        (7, 'GOODS-7', 'buyer-a', 'kw7',  1, '2026-11-09 10:00:00'),
                        (7, 'GOODS-7', 'buyer-b', 'kw7',  0, '2026-11-09 11:00:00'),
                        (9, 'GOODS-9', 'buyer-c', 'kw9', -1, '2026-11-09 12:00:00')
                    """);

            // 操作日志：create_time 是毫秒时间戳
            statement.executeUpdate("""
                    CREATE TABLE xianyu_operation_log (
                        id INTEGER PRIMARY KEY,
                        xianyu_account_id BIGINT,
                        operation_type TEXT,
                        operation_status INTEGER,
                        error_message TEXT,
                        duration_ms INTEGER,
                        create_time INTEGER
                    )
                    """);
            long dayMillis = java.time.LocalDate.of(2026, 11, 9)
                    .atTime(12, 0)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
            statement.executeUpdate("""
                    INSERT INTO xianyu_operation_log
                        (xianyu_account_id, operation_type, operation_status, error_message, duration_ms, create_time)
                    VALUES
                        (7, 'REFRESH', 1, NULL, 120, %d),
                        (9, 'PUBLISH', 0, 'platform rejected', 300, %d)
                    """.formatted(dayMillis, dayMillis));
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        // MyBatis 在这里解析 @Select：若存在同名重载，会在这一行打出 "is ignored"
        configuration.addMapper(XianyuChatMessageMapper.class);
        configuration.addMapper(XianyuGoodsAutoReplyRecordMapper.class);
        configuration.addMapper(XianyuOperationLogMapper.class);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession();
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    // ---------- 咨询商品排行 ----------

    @Test
    void topGoodsByMessagesRespectsAccountScope() {
        XianyuChatMessageMapper mapper = session.getMapper(XianyuChatMessageMapper.class);

        assertThat(goodsIds(mapper.selectTopGoodsByMessagesScoped(10, scopeOf(AUTHORIZED_ACCOUNT))))
                .containsExactly("GOODS-7");

        assertThat(goodsIds(mapper.selectTopGoodsByMessagesScoped(10, scopeOf(OTHER_ACCOUNT))))
                .containsExactly("GOODS-9");

        assertThat(mapper.selectTopGoodsByMessagesScoped(10, emptyScope())).isEmpty();

        assertThat(goodsIds(mapper.selectTopGoodsByMessagesScoped(10, adminScope())))
                .containsExactlyInAnyOrder("GOODS-7", "GOODS-9");
    }

    // ---------- 自动回复统计 ----------

    @Test
    void replyCountsRespectAccountScope() {
        XianyuGoodsAutoReplyRecordMapper mapper = session.getMapper(XianyuGoodsAutoReplyRecordMapper.class);

        assertThat(mapper.countAiRepliesByDateScoped("2026-11-09", scopeOf(AUTHORIZED_ACCOUNT)))
                .as("账号7在当天有2条记录").isEqualTo(2);
        assertThat(mapper.countAiRepliesByDateScoped("2026-11-09", scopeOf(OTHER_ACCOUNT)))
                .as("账号9在当天有1条记录").isEqualTo(1);
        assertThat(mapper.countAiRepliesByDateScoped("2026-11-09", emptyScope()))
                .as("空范围必须一条都统计不到").isZero();
        assertThat(mapper.countAiRepliesByDateScoped("2026-11-09", adminScope()))
                .as("管理员看到全量3条").isEqualTo(3);

        assertThat(mapper.countAllRepliesScoped(scopeOf(AUTHORIZED_ACCOUNT))).isEqualTo(2);
        assertThat(mapper.countAllRepliesScoped(emptyScope())).isZero();
        assertThat(mapper.countAllRepliesScoped(adminScope())).isEqualTo(3);
    }

    @Test
    void accountRepliesByDateReturnsOnlyAuthorizedAccounts() {
        XianyuGoodsAutoReplyRecordMapper mapper = session.getMapper(XianyuGoodsAutoReplyRecordMapper.class);

        List<Map<String, Object>> rows =
                mapper.selectAccountRepliesByDateScoped("2026-11-09", scopeOf(AUTHORIZED_ACCOUNT));
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("accountId")).longValue()).isEqualTo(AUTHORIZED_ACCOUNT);

        assertThat(mapper.selectAccountRepliesByDateScoped("2026-11-09", emptyScope())).isEmpty();
        assertThat(mapper.selectAccountRepliesByDateScoped("2026-11-09", adminScope())).hasSize(2);
    }

    @Test
    void keywordRankRespectsAccountScope() {
        XianyuGoodsAutoReplyRecordMapper mapper = session.getMapper(XianyuGoodsAutoReplyRecordMapper.class);

        List<Map<String, Object>> scoped =
                mapper.selectKeywordRankByDateScoped("2026-11-09", 10, scopeOf(AUTHORIZED_ACCOUNT));
        assertThat(scoped).hasSize(1);
        assertThat(scoped.get(0).get("keyword")).isEqualTo("kw7");

        assertThat(mapper.selectKeywordRankByDateScoped("2026-11-09", 10, emptyScope())).isEmpty();

        assertThat(mapper.selectKeywordRankByDateScoped("2026-11-09", 10, adminScope()))
                .extracting(row -> row.get("keyword"))
                .containsExactlyInAnyOrder("kw7", "kw9");
    }

    // ---------- 操作日志统计 ----------

    @Test
    void operationStatsRespectAccountScope() {
        XianyuOperationLogMapper mapper = session.getMapper(XianyuOperationLogMapper.class);

        List<Map<String, Object>> scoped =
                mapper.selectOperationStatsByDateScoped("2026-11-09", 10, scopeOf(OTHER_ACCOUNT));
        assertThat(scoped).hasSize(1);
        assertThat(scoped.get(0).get("typeName")).isEqualTo("PUBLISH");

        assertThat(mapper.selectOperationStatsByDateScoped("2026-11-09", 10, emptyScope())).isEmpty();
        assertThat(mapper.selectOperationStatsByDateScoped("2026-11-09", 10, adminScope())).hasSize(2);
    }

    @Test
    void failureRankRespectsAccountScope() {
        XianyuOperationLogMapper mapper = session.getMapper(XianyuOperationLogMapper.class);

        assertThat(mapper.selectFailureRankByDateScoped("2026-11-09", 10, scopeOf(AUTHORIZED_ACCOUNT)))
                .as("账号7没有失败记录，不得看到账号9的失败原因").isEmpty();

        assertThat(mapper.selectFailureRankByDateScoped("2026-11-09", 10, emptyScope())).isEmpty();

        List<Map<String, Object>> adminRows =
                mapper.selectFailureRankByDateScoped("2026-11-09", 10, adminScope());
        assertThat(adminRows).hasSize(1);
        assertThat(adminRows.get(0).get("reason")).isEqualTo("platform rejected");
    }

    // ---------- 辅助 ----------

    /** 空范围：非管理员且未被授予任何账号，必须拒绝全部数据。 */
    private static DataPanelAccountScope emptyScope() {
        return DataPanelAccountScope.restricted(11L, List.of());
    }

    /** null 账号列表：仅 ADMIN 的全量可见语义。 */
    private static DataPanelAccountScope adminScope() {
        return DataPanelAccountScope.all(1L);
    }

    private static DataPanelAccountScope scopeOf(Long... accountIds) {
        return DataPanelAccountScope.restricted(11L, List.of(accountIds));
    }

    private static List<String> goodsIds(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> String.valueOf(row.get("goodsId"))).toList();
    }
}
