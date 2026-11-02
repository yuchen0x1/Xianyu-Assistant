package com.feijimiao.xianyuassistant.backup.handler;

import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AutoReplyBackupHandler implements DataBackupHandler {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public String getModuleKey() {
        return "autoReply";
    }

    @Override
    public String getModuleName() {
        return "自动回复";
    }

    @Override
    public Set<String> getRequiredImportListKeys() {
        return Set.of("autoReplyConfigs");
    }

    @Override
    public Set<String> getAdditionalRequiredImportListKeys(String backupVersion) {
        return "2.0".equals(backupVersion) ? Set.of("keywordReplyRules") : Set.of();
    }

    @Override
    public Map<String, Object> exportData() {
        List<Map<String, Object>> configs = jdbcTemplate.queryForList(
                "SELECT c.xy_goods_id, c.xianyu_auto_reply_on, " +
                        "c.xianyu_auto_reply_context_on, c.xianyu_keyword_reply_on, " +
                        "c.human_intervention_on, c.human_intervention_minutes, c.reply_manual_confirm_on, " +
                        "c.fixed_material, a.unb " +
                        "FROM xianyu_goods_config c " +
                        "LEFT JOIN xianyu_account a ON c.xianyu_account_id = a.id");

        List<Map<String, Object>> configResult = new ArrayList<>();
        for (Map<String, Object> config : configs) {
            if (!(config.get("unb") instanceof String unb) || unb.isBlank()) {
                throw new IllegalStateException(
                        "自动回复配置存在无法关联账号的商品: " + config.get("xy_goods_id"));
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", unb);
            map.put("xyGoodsId", config.get("xy_goods_id"));
            map.put("autoReplyOn", config.get("xianyu_auto_reply_on"));
            map.put("autoReplyContextOn", config.get("xianyu_auto_reply_context_on"));
            map.put("keywordReplyOn", config.get("xianyu_keyword_reply_on"));
            map.put("humanInterventionOn", config.get("human_intervention_on"));
            map.put("humanInterventionMinutes", config.get("human_intervention_minutes"));
            map.put("replyManualConfirmOn", config.get("reply_manual_confirm_on"));
            map.put("fixedMaterial", config.get("fixed_material"));
            configResult.add(map);
        }

        List<Map<String, Object>> ruleRows = jdbcTemplate.queryForList(
                "SELECT r.id, r.xy_goods_id, r.keyword, r.match_mode, r.is_fallback, a.unb " +
                        "FROM xianyu_keyword_reply_rule r " +
                        "LEFT JOIN xianyu_account a ON r.xianyu_account_id = a.id " +
                        "ORDER BY r.id");
        List<Map<String, Object>> ruleResult = new ArrayList<>();
        for (Map<String, Object> rule : ruleRows) {
            if (!(rule.get("unb") instanceof String unb) || unb.isBlank()) {
                throw new IllegalStateException(
                        "关键词回复规则存在无法关联账号的商品: " + rule.get("xy_goods_id"));
            }
            Number ruleId = (Number) rule.get("id");
            List<Map<String, Object>> contentRows = jdbcTemplate.queryForList(
                    "SELECT reply_text, reply_image_url FROM xianyu_keyword_reply_content " +
                            "WHERE rule_id = ? ORDER BY id",
                    ruleId.longValue());
            List<Map<String, Object>> contents = new ArrayList<>();
            for (Map<String, Object> content : contentRows) {
                Map<String, Object> contentMap = new LinkedHashMap<>();
                contentMap.put("replyText", content.get("reply_text"));
                contentMap.put("replyImageUrl", content.get("reply_image_url"));
                contents.add(contentMap);
            }

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", unb);
            map.put("xyGoodsId", rule.get("xy_goods_id"));
            map.put("keyword", rule.get("keyword"));
            map.put("matchMode", rule.get("match_mode"));
            map.put("isFallback", rule.get("is_fallback"));
            map.put("contents", contents);
            ruleResult.add(map);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("autoReplyConfigs", configResult);
        data.put("keywordReplyRules", ruleResult);
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Long> unbToAccountId = context.get("unbToAccountId") != null
                ? (Map<String, Long>) context.get("unbToAccountId")
                : Collections.emptyMap();
        boolean version2 = "2.0".equals(context.get(BACKUP_VERSION_CONTEXT_KEY));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configMaps = (List<Map<String, Object>>) data.get("autoReplyConfigs");
        if (configMaps != null) {
            for (Map<String, Object> map : configMaps) {
                importConfig(map, unbToAccountId, version2);
            }
        }

        if (data.containsKey("keywordReplyRules")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ruleMaps = (List<Map<String, Object>>) data.get("keywordReplyRules");
            replaceKeywordRules(ruleMaps, configMaps, unbToAccountId);
        }
    }

    private void importConfig(Map<String, Object> map,
                              Map<String, Long> unbToAccountId,
                              boolean version2) {
        try {
            String unb = (String) map.get("unb");
            String xyGoodsId = (String) map.get("xyGoodsId");
            if (unb == null || xyGoodsId == null) {
                throw new IllegalArgumentException("自动回复配置缺少账号或商品标识");
            }
            Long accountId = unbToAccountId.get(unb);
            if (accountId == null) {
                throw new IllegalStateException("找不到自动回复配置关联账号: " + unb);
            }

            List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM xianyu_goods_config WHERE xianyu_account_id = ? AND xy_goods_id = ?",
                    accountId, xyGoodsId);
            if (version2) {
                Object[] values = {
                        numberValue(map.get("autoReplyOn")),
                        numberValue(map.get("autoReplyContextOn")),
                        numberValue(map.get("keywordReplyOn")),
                        numberValue(map.get("humanInterventionOn")),
                        numberValue(map.get("humanInterventionMinutes")),
                        map.containsKey("replyManualConfirmOn") ? numberValue(map.get("replyManualConfirmOn")) : 0,
                        map.get("fixedMaterial")
                };
                if (existing.isEmpty()) {
                    jdbcTemplate.update(
                            "INSERT INTO xianyu_goods_config " +
                                    "(xianyu_account_id, xy_goods_id, xianyu_auto_reply_on, " +
                                    "xianyu_auto_reply_context_on, xianyu_keyword_reply_on, " +
                                    "human_intervention_on, human_intervention_minutes, reply_manual_confirm_on, fixed_material) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            accountId, xyGoodsId, values[0], values[1], values[2], values[3], values[4], values[5], values[6]);
                } else {
                    jdbcTemplate.update(
                            "UPDATE xianyu_goods_config SET xianyu_auto_reply_on = ?, " +
                                    "xianyu_auto_reply_context_on = ?, xianyu_keyword_reply_on = ?, " +
                                    "human_intervention_on = ?, human_intervention_minutes = ?, reply_manual_confirm_on = ?, fixed_material = ? " +
                                    "WHERE xianyu_account_id = ? AND xy_goods_id = ?",
                            values[0], values[1], values[2], values[3], values[4], values[5], values[6],
                            accountId, xyGoodsId);
                }
            } else {
                Integer autoReplyOn = numberValue(map.get("autoReplyOn"));
                Integer autoReplyContextOn = numberValue(map.get("autoReplyContextOn"));
                String fixedMaterial = (String) map.get("fixedMaterial");
                if (existing.isEmpty()) {
                    jdbcTemplate.update(
                            "INSERT INTO xianyu_goods_config " +
                                    "(xianyu_account_id, xy_goods_id, xianyu_auto_reply_on, " +
                                    "xianyu_auto_reply_context_on, fixed_material) VALUES (?, ?, ?, ?, ?)",
                            accountId, xyGoodsId, autoReplyOn, autoReplyContextOn, fixedMaterial);
                } else {
                    jdbcTemplate.update(
                            "UPDATE xianyu_goods_config SET xianyu_auto_reply_on = ?, " +
                                    "xianyu_auto_reply_context_on = ?, fixed_material = ? " +
                                    "WHERE xianyu_account_id = ? AND xy_goods_id = ?",
                            autoReplyOn, autoReplyContextOn, fixedMaterial, accountId, xyGoodsId);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("导入单条自动回复配置失败", e);
        }
    }

    private void replaceKeywordRules(List<Map<String, Object>> ruleMaps,
                                     List<Map<String, Object>> configMaps,
                                     Map<String, Long> unbToAccountId) {
        if (ruleMaps == null) {
            throw new IllegalArgumentException("keywordReplyRules 必须是数组");
        }

        Set<ReplyScope> scopes = new LinkedHashSet<>();
        if (configMaps != null) {
            for (Map<String, Object> configMap : configMaps) {
                scopes.add(resolveScope(configMap, unbToAccountId, "自动回复配置"));
            }
        }

        List<RuleImport> rules = new ArrayList<>();
        for (Map<String, Object> map : ruleMaps) {
            try {
                String keyword = (String) map.get("keyword");
                if (keyword == null) {
                    throw new IllegalArgumentException("关键词回复规则缺少关键词");
                }
                requireKeys(map, Set.of("matchMode", "isFallback", "contents"));
                if (!(map.get("contents") instanceof List<?> rawContents)) {
                    throw new IllegalArgumentException("关键词回复规则 contents 必须是数组");
                }
                for (Object rawContent : rawContents) {
                    if (!(rawContent instanceof Map<?, ?>)) {
                        throw new IllegalArgumentException("关键词回复内容必须是对象");
                    }
                }
                ReplyScope scope = resolveScope(map, unbToAccountId, "关键词回复规则");
                scopes.add(scope);
                rules.add(new RuleImport(map, scope, rawContents));
            } catch (Exception e) {
                throw new IllegalStateException("校验单条关键词回复规则失败", e);
            }
        }

        for (ReplyScope scope : scopes) {
            jdbcTemplate.update(
                    "DELETE FROM xianyu_keyword_reply_content WHERE rule_id IN (" +
                            "SELECT id FROM xianyu_keyword_reply_rule " +
                            "WHERE xianyu_account_id = ? AND xy_goods_id = ?)",
                    scope.accountId(), scope.xyGoodsId());
            jdbcTemplate.update(
                    "DELETE FROM xianyu_keyword_reply_rule " +
                            "WHERE xianyu_account_id = ? AND xy_goods_id = ?",
                    scope.accountId(), scope.xyGoodsId());
        }

        for (RuleImport ruleImport : rules) {
            Map<String, Object> map = ruleImport.data();
            ReplyScope scope = ruleImport.scope();
            try {
                KeyHolder keyHolder = new GeneratedKeyHolder();
                jdbcTemplate.update(connection -> {
                    PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO xianyu_keyword_reply_rule " +
                                    "(xianyu_account_id, xy_goods_id, keyword, match_mode, is_fallback) " +
                            "VALUES (?, ?, ?, ?, ?)",
                            Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, scope.accountId());
                    statement.setString(2, scope.xyGoodsId());
                    statement.setString(3, (String) map.get("keyword"));
                    statement.setObject(4, numberValue(map.get("matchMode")));
                    statement.setObject(5, numberValue(map.get("isFallback")));
                    return statement;
                }, keyHolder);
                Number generatedId = keyHolder.getKey();
                if (generatedId == null) {
                    throw new IllegalStateException("数据库未返回关键词回复规则ID");
                }

                for (Object rawContent : ruleImport.contents()) {
                    Map<?, ?> content = (Map<?, ?>) rawContent;
                    jdbcTemplate.update(
                            "INSERT INTO xianyu_keyword_reply_content " +
                                    "(rule_id, reply_text, reply_image_url) VALUES (?, ?, ?)",
                            generatedId.longValue(), content.get("replyText"), content.get("replyImageUrl"));
                }
            } catch (Exception e) {
                throw new IllegalStateException("导入单条关键词回复规则失败", e);
            }
        }
    }

    private ReplyScope resolveScope(Map<String, Object> map,
                                    Map<String, Long> unbToAccountId,
                                    String dataType) {
        String unb = (String) map.get("unb");
        String xyGoodsId = (String) map.get("xyGoodsId");
        if (unb == null || xyGoodsId == null) {
            throw new IllegalArgumentException(dataType + "缺少账号或商品标识");
        }
        Long accountId = unbToAccountId.get(unb);
        if (accountId == null) {
            throw new IllegalStateException("找不到" + dataType + "关联账号: " + unb);
        }
        return new ReplyScope(accountId, xyGoodsId);
    }

    private void requireKeys(Map<String, Object> map, Set<String> keys) {
        for (String key : keys) {
            if (!map.containsKey(key)) {
                throw new IllegalArgumentException("备份数据缺少字段: " + key);
            }
        }
    }

    private Integer numberValue(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private record ReplyScope(Long accountId, String xyGoodsId) {
    }

    private record RuleImport(Map<String, Object> data, ReplyScope scope, List<?> contents) {
    }
}
