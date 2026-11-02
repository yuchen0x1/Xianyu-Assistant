package com.feijimiao.xianyuassistant.backup.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.controller.dto.DeliveryBlockRuleDTO;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuDeliveryBlockRule;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuDeliveryBlockRuleMapper;
import com.feijimiao.xianyuassistant.service.DeliveryBlockRuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DeliveryBlockRuleBackupHandler implements DataBackupHandler {

    @Autowired
    private XianyuDeliveryBlockRuleMapper ruleMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private DeliveryBlockRuleService ruleService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getModuleKey() {
        return "deliveryBlockRule";
    }

    @Override
    public String getModuleName() {
        return "发货风控";
    }

    @Override
    public Set<String> getRequiredImportListKeys() {
        return Set.of("deliveryBlockRules");
    }

    @Override
    public Map<String, Object> exportData() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<XianyuDeliveryBlockRule> rules = ruleMapper.selectList(null);
        if (rules == null) rules = List.of();
        for (XianyuDeliveryBlockRule rule : rules) {
            XianyuAccount account = accountMapper.selectById(rule.getXianyuAccountId());
            if (account == null || account.getUnb() == null || account.getUnb().isBlank()) {
                throw new IllegalStateException("发货风控规则找不到关联账号: " + rule.getXianyuAccountId());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("unb", account.getUnb());
            item.put("ruleCode", rule.getRuleCode());
            item.put("enabled", rule.getEnabled());
            item.put("priority", rule.getPriority());
            item.put("blockReason", rule.getBlockReason());
            item.put("excludedItemIds", parseList(rule.getExcludedItemIds()));
            item.put("config", parseMap(rule.getConfigJson()));
            result.add(item);
        }
        return Map.of("deliveryBlockRules", result);
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Long> unbToAccountId = context.get("unbToAccountId") instanceof Map<?, ?> mapping
                ? (Map<String, Long>) mapping : Collections.emptyMap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ruleMaps = (List<Map<String, Object>>) data.get("deliveryBlockRules");
        if (ruleMaps == null) return;

        Map<Long, List<DeliveryBlockRuleDTO>> rulesByAccount = new LinkedHashMap<>();
        for (Map<String, Object> item : ruleMaps) {
            String unb = item.get("unb") instanceof String value ? value : null;
            String ruleCode = item.get("ruleCode") instanceof String value ? value : null;
            if (unb == null || unb.isBlank() || ruleCode == null || ruleCode.isBlank()) {
                throw new IllegalArgumentException("发货风控规则缺少账号或规则编码");
            }
            Long accountId = unbToAccountId.get(unb);
            if (accountId == null) {
                throw new IllegalStateException("找不到发货风控规则关联账号: " + unb);
            }
            DeliveryBlockRuleDTO dto = new DeliveryBlockRuleDTO();
            dto.setRuleCode(ruleCode);
            dto.setEnabled(numberValue(item.get("enabled"), 0));
            dto.setPriority(numberValue(item.get("priority"), 0));
            dto.setBlockReason(item.get("blockReason") instanceof String value ? value : "");
            dto.setExcludedItemIds(objectMapper.convertValue(
                    item.getOrDefault("excludedItemIds", List.of()), new TypeReference<List<String>>() { }));
            dto.setConfig(objectMapper.convertValue(
                    item.getOrDefault("config", Map.of()), new TypeReference<Map<String, Object>>() { }));
            rulesByAccount.computeIfAbsent(accountId, ignored -> new ArrayList<>()).add(dto);
        }
        rulesByAccount.forEach(ruleService::saveRules);
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("发货风控排除商品配置无法导出", e);
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("发货风控规则参数无法导出", e);
        }
    }

    private int numberValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
