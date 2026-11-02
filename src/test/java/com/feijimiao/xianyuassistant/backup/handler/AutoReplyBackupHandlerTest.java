package com.feijimiao.xianyuassistant.backup.handler;

import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AutoReplyBackupHandlerTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private AutoReplyBackupHandler handler;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("auto-reply-backup.db"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE xianyu_account (
                    id INTEGER PRIMARY KEY,
                    unb TEXT NOT NULL UNIQUE
                )
                """);
        jdbc.execute("""
                CREATE TABLE xianyu_goods_config (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    xianyu_account_id BIGINT NOT NULL,
                    xy_goods_id TEXT NOT NULL,
                    xianyu_auto_delivery_on INTEGER DEFAULT 0,
                    xianyu_auto_reply_on INTEGER DEFAULT 0,
                    xianyu_auto_reply_context_on INTEGER DEFAULT 1,
                    xianyu_keyword_reply_on INTEGER DEFAULT 0,
                    human_intervention_on INTEGER DEFAULT 0,
                    human_intervention_minutes INTEGER DEFAULT 10,
                    reply_manual_confirm_on INTEGER DEFAULT 0,
                    fixed_material TEXT,
                    UNIQUE(xianyu_account_id, xy_goods_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE xianyu_keyword_reply_rule (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    xianyu_account_id BIGINT NOT NULL,
                    xy_goods_id TEXT NOT NULL,
                    keyword TEXT NOT NULL,
                    match_mode INTEGER,
                    is_fallback INTEGER
                )
                """);
        jdbc.execute("""
                CREATE TABLE xianyu_keyword_reply_content (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    rule_id BIGINT NOT NULL,
                    reply_text TEXT,
                    reply_image_url TEXT
                )
                """);
        jdbc.update("INSERT INTO xianyu_account(id, unb) VALUES (7, 'account-1')");
        jdbc.update("""
                INSERT INTO xianyu_goods_config
                    (xianyu_account_id, xy_goods_id, xianyu_auto_reply_on,
                     xianyu_auto_reply_context_on, xianyu_keyword_reply_on,
                     human_intervention_on, human_intervention_minutes, fixed_material)
                VALUES (7, 'goods-1', 1, 0, 1, 1, 25, 'material')
                """);
        jdbc.update("""
                INSERT INTO xianyu_keyword_reply_rule
                    (id, xianyu_account_id, xy_goods_id, keyword, match_mode, is_fallback)
                VALUES (11, 7, 'goods-1', 'hello', 2, 0)
                """);
        jdbc.update("""
                INSERT INTO xianyu_keyword_reply_content(rule_id, reply_text, reply_image_url)
                VALUES (11, 'world', '/images/reply.png')
                """);

        handler = new AutoReplyBackupHandler();
        ReflectionTestUtils.setField(handler, "jdbcTemplate", jdbc);
    }

    @Test
    void versionTwoRoundTripRestoresReplySettingsRulesAndContents() {
        Map<String, Object> exported = handler.exportData();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configs =
                (List<Map<String, Object>>) exported.get("autoReplyConfigs");
        assertThat(configs).singleElement().satisfies(config -> {
            assertThat(config.get("keywordReplyOn")).isEqualTo(1);
            assertThat(config.get("humanInterventionOn")).isEqualTo(1);
            assertThat(config.get("humanInterventionMinutes")).isEqualTo(25);
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules =
                (List<Map<String, Object>>) exported.get("keywordReplyRules");
        assertThat(rules).singleElement().satisfies(rule -> {
            assertThat(rule.get("keyword")).isEqualTo("hello");
            assertThat((List<?>) rule.get("contents")).singleElement();
        });

        jdbc.update("INSERT INTO xianyu_account(id, unb) VALUES (8, 'account-2')");
        jdbc.update("""
                INSERT INTO xianyu_goods_config
                    (xianyu_account_id, xy_goods_id, xianyu_auto_reply_on)
                VALUES (8, 'goods-2', 1)
                """);
        jdbc.update("""
                INSERT INTO xianyu_keyword_reply_rule
                    (id, xianyu_account_id, xy_goods_id, keyword, match_mode, is_fallback)
                VALUES (99, 8, 'goods-2', 'keep-me', 1, 0)
                """);
        jdbc.update("""
                INSERT INTO xianyu_keyword_reply_content(rule_id, reply_text, reply_image_url)
                VALUES (99, 'other-account-content', NULL)
                """);
        jdbc.update("""
                UPDATE xianyu_goods_config SET xianyu_auto_reply_on = 0,
                    xianyu_auto_reply_context_on = 1, xianyu_keyword_reply_on = 0,
                    human_intervention_on = 0, human_intervention_minutes = 5,
                    fixed_material = 'changed'
                WHERE xianyu_account_id = 7
                """);
        jdbc.update("DELETE FROM xianyu_keyword_reply_content WHERE rule_id = 11");
        jdbc.update("DELETE FROM xianyu_keyword_reply_rule WHERE id = 11");

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("account-1", 7L));
        context.put(DataBackupHandler.BACKUP_VERSION_CONTEXT_KEY, "2.0");
        handler.importData(exported, context);

        Map<String, Object> restored = jdbc.queryForMap("""
                SELECT xianyu_auto_reply_on, xianyu_auto_reply_context_on,
                    xianyu_keyword_reply_on, human_intervention_on,
                    human_intervention_minutes, fixed_material
                FROM xianyu_goods_config
                WHERE xianyu_account_id = 7
                """);
        assertThat(restored.get("xianyu_auto_reply_on")).isEqualTo(1);
        assertThat(restored.get("xianyu_auto_reply_context_on")).isEqualTo(0);
        assertThat(restored.get("xianyu_keyword_reply_on")).isEqualTo(1);
        assertThat(restored.get("human_intervention_on")).isEqualTo(1);
        assertThat(restored.get("human_intervention_minutes")).isEqualTo(25);
        assertThat(restored.get("fixed_material")).isEqualTo("material");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM xianyu_keyword_reply_rule", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT reply_text FROM xianyu_keyword_reply_content " +
                        "WHERE rule_id IN (SELECT id FROM xianyu_keyword_reply_rule " +
                        "WHERE xianyu_account_id = 7)", String.class)).isEqualTo("world");
        assertThat(jdbc.queryForObject(
                "SELECT keyword FROM xianyu_keyword_reply_rule WHERE id = 99", String.class))
                .isEqualTo("keep-me");
        assertThat(jdbc.queryForObject(
                "SELECT reply_text FROM xianyu_keyword_reply_content WHERE rule_id = 99", String.class))
                .isEqualTo("other-account-content");
    }

    @Test
    void emptyRuleListClearsOnlyTheGoodsScopesDeclaredByReplyConfigs() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configs = (List<Map<String, Object>>)
                handler.exportData().get("autoReplyConfigs");
        Map<String, Object> data = Map.of(
                "autoReplyConfigs", configs,
                "keywordReplyRules", List.of()
        );
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("unbToAccountId", Map.of("account-1", 7L));
        context.put(DataBackupHandler.BACKUP_VERSION_CONTEXT_KEY, "2.0");

        handler.importData(data, context);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM xianyu_keyword_reply_rule WHERE xianyu_account_id = 7",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM xianyu_keyword_reply_content",
                Integer.class)).isZero();
    }
}
