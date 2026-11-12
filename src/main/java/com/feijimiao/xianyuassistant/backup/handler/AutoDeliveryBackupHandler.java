package com.feijimiao.xianyuassistant.backup.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuGoodsAutoDeliveryConfig;
import com.feijimiao.xianyuassistant.entity.XianyuKamiConfig;
import com.feijimiao.xianyuassistant.entity.DeliveryTemplate;
import com.feijimiao.xianyuassistant.entity.DeliveryTemplateMessage;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuGoodsAutoDeliveryConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiConfigMapper;
import com.feijimiao.xianyuassistant.mapper.DeliveryTemplateMapper;
import com.feijimiao.xianyuassistant.mapper.DeliveryTemplateMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class AutoDeliveryBackupHandler implements DataBackupHandler {

    @Autowired
    private XianyuGoodsAutoDeliveryConfigMapper autoDeliveryConfigMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private XianyuKamiConfigMapper kamiConfigMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 模板表在 v3.27 才加入。保留可选注入，旧版数据库/聚焦备份测试仍能导入
     * 没有模板字段的旧备份；真实 v3.27 运行时 Spring 会正常注入这两个 Mapper。
     */
    @Autowired(required = false)
    private DeliveryTemplateMapper deliveryTemplateMapper;

    @Autowired(required = false)
    private DeliveryTemplateMessageMapper deliveryTemplateMessageMapper;

    /** 导入上下文中的源模板 ID -> 当前数据库模板 ID 映射。 */
    static final String DELIVERY_TEMPLATE_ID_MAPPING_CONTEXT_KEY = "deliveryTemplateIdMapping";

    @Override
    public String getModuleKey() {
        return "autoDelivery";
    }

    @Override
    public String getModuleName() {
        return "自动发货";
    }

    @Override
    public Set<String> getRequiredImportListKeys() {
        return Set.of("autoDeliveryConfigs");
    }

    @Override
    public Set<String> getAdditionalRequiredImportListKeys(String backupVersion) {
        return "2.0".equals(backupVersion) ? Set.of("autoDeliverySwitches") : Set.of();
    }

    @Override
    public Map<String, Object> exportData() {
        List<XianyuGoodsAutoDeliveryConfig> configs = autoDeliveryConfigMapper.selectList(null);

        List<Map<String, Object>> result = new ArrayList<>();
        Set<Long> accountIds = new LinkedHashSet<>();
        for (XianyuGoodsAutoDeliveryConfig config : configs) {
            if (config.getXianyuAccountId() != null) {
                accountIds.add(config.getXianyuAccountId());
            }
            XianyuAccount account = accountMapper.selectById(config.getXianyuAccountId());
            if (account == null || account.getUnb() == null || account.getUnb().isBlank()) {
                throw new IllegalStateException(
                        "自动发货配置找不到关联账号: " + config.getXianyuAccountId());
            }

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", account.getUnb());
            map.put("xyGoodsId", config.getXyGoodsId());
            map.put("skuId", config.getSkuId());
            map.put("skuName", config.getSkuName());
            map.put("deliveryMode", config.getDeliveryMode());
            map.put("autoDeliveryContent", config.getAutoDeliveryContent());
            map.put("kamiConfigIds", config.getKamiConfigIds());
            map.put("kamiDeliveryTemplate", config.getKamiDeliveryTemplate());
            map.put("autoDeliveryImageUrl", config.getAutoDeliveryImageUrl());
            map.put("deliveryTemplateId", config.getDeliveryTemplateId());
            map.put("autoConfirmShipment", config.getAutoConfirmShipment());
            map.put("ragDelaySeconds", config.getRagDelaySeconds());
            result.add(map);
        }

        if (deliveryTemplateMapper != null && deliveryTemplateMessageMapper != null) {
            List<XianyuAccount> allAccounts = accountMapper.selectList(null);
            if (allAccounts != null) {
                allAccounts.stream()
                        .map(XianyuAccount::getId)
                        .filter(Objects::nonNull)
                        .forEach(accountIds::add);
            }
        }

        // 模板不能只跟随商品配置导出：未被当前商品引用的模板也必须保留，
        // 否则恢复后用户会看到模板列表缺项。缺少 v3.27 Mapper 时保持旧备份格式。
        List<Map<String, Object>> templateResult = exportTemplates(accountIds);

        List<Map<String, Object>> switches = jdbcTemplate.queryForList(
                "SELECT a.unb, c.xy_goods_id, c.xianyu_auto_delivery_on " +
                        "FROM xianyu_goods_config c " +
                        "LEFT JOIN xianyu_account a ON c.xianyu_account_id = a.id");
        List<Map<String, Object>> switchResult = new ArrayList<>();
        for (Map<String, Object> row : switches) {
            if (!(row.get("unb") instanceof String unb) || unb.isBlank()) {
                throw new IllegalStateException(
                        "自动发货总开关存在无法关联账号的商品: " + row.get("xy_goods_id"));
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unb", unb);
            map.put("xyGoodsId", row.get("xy_goods_id"));
            map.put("autoDeliveryOn", row.get("xianyu_auto_delivery_on"));
            switchResult.add(map);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("autoDeliveryConfigs", result);
        data.put("autoDeliverySwitches", switchResult);
        if (deliveryTemplateMapper != null && deliveryTemplateMessageMapper != null) {
            data.put("deliveryTemplates", templateResult);
        }
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Long> unbToAccountId = context.get("unbToAccountId") != null
                ? (Map<String, Long>) context.get("unbToAccountId")
                : Collections.emptyMap();

        @SuppressWarnings("unchecked")
        Map<Long, Long> kamiConfigIdMapping = context.get(KamiBackupHandler.CONFIG_ID_MAPPING_CONTEXT_KEY) != null
                ? (Map<Long, Long>) context.get(KamiBackupHandler.CONFIG_ID_MAPPING_CONTEXT_KEY)
                : null;

        Map<Long, Long> deliveryTemplateIdMapping = importTemplates(data, context, unbToAccountId);
        context.put(DELIVERY_TEMPLATE_ID_MAPPING_CONTEXT_KEY, deliveryTemplateIdMapping);

        importSwitches(data, unbToAccountId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configMaps = (List<Map<String, Object>>) data.get("autoDeliveryConfigs");
        if (configMaps == null) return;
        boolean version2 = "2.0".equals(context.get(BACKUP_VERSION_CONTEXT_KEY));

        for (Map<String, Object> map : configMaps) {
            try {
                String unb = (String) map.get("unb");
                String xyGoodsId = (String) map.get("xyGoodsId");
                if (unb == null || xyGoodsId == null) {
                    throw new IllegalArgumentException("自动发货配置缺少账号或商品标识");
                }
                if (version2 && !map.containsKey("ragDelaySeconds")) {
                    throw new IllegalArgumentException("2.0备份的自动发货配置缺少ragDelaySeconds");
                }

                Long accountId = unbToAccountId.get(unb);
                if (accountId == null) {
                    throw new IllegalStateException("找不到自动发货配置关联账号: " + unb);
                }

                String mappedKamiConfigIds = remapKamiConfigIds(
                        (String) map.get("kamiConfigIds"), kamiConfigIdMapping, accountId, context);

                String skuId = (String) map.get("skuId");
                LambdaQueryWrapper<XianyuGoodsAutoDeliveryConfig> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(XianyuGoodsAutoDeliveryConfig::getXianyuAccountId, accountId)
                       .eq(XianyuGoodsAutoDeliveryConfig::getXyGoodsId, xyGoodsId);
                if (skuId == null) {
                    wrapper.isNull(XianyuGoodsAutoDeliveryConfig::getSkuId);
                } else {
                    wrapper.eq(XianyuGoodsAutoDeliveryConfig::getSkuId, skuId);
                }
                XianyuGoodsAutoDeliveryConfig existing = autoDeliveryConfigMapper.selectOne(wrapper);

                XianyuGoodsAutoDeliveryConfig config = new XianyuGoodsAutoDeliveryConfig();
                config.setXianyuAccountId(accountId);
                config.setXyGoodsId(xyGoodsId);
                config.setSkuId(skuId);
                config.setSkuName((String) map.get("skuName"));
                config.setDeliveryMode(map.get("deliveryMode") != null ? ((Number) map.get("deliveryMode")).intValue() : null);
                config.setAutoDeliveryContent((String) map.get("autoDeliveryContent"));
                config.setKamiConfigIds(mappedKamiConfigIds);
                config.setKamiDeliveryTemplate((String) map.get("kamiDeliveryTemplate"));
                config.setAutoDeliveryImageUrl((String) map.get("autoDeliveryImageUrl"));
                config.setDeliveryTemplateId(remapDeliveryTemplateId(
                        map.get("deliveryTemplateId"), deliveryTemplateIdMapping, accountId));
                config.setAutoConfirmShipment(map.get("autoConfirmShipment") != null ? ((Number) map.get("autoConfirmShipment")).intValue() : null);
                if (map.containsKey("ragDelaySeconds")) {
                    config.setRagDelaySeconds(map.get("ragDelaySeconds") != null
                            ? ((Number) map.get("ragDelaySeconds")).intValue() : null);
                } else {
                    config.setRagDelaySeconds(existing != null ? existing.getRagDelaySeconds() : 15);
                }

                if (existing == null) {
                    autoDeliveryConfigMapper.insert(config);
                } else {
                    config.setId(existing.getId());
                    autoDeliveryConfigMapper.updateById(config);
                }
            } catch (Exception e) {
                throw new IllegalStateException("导入单条自动发货配置失败", e);
            }
        }
    }

    private List<Map<String, Object>> exportTemplates(Set<Long> accountIds) {
        if (deliveryTemplateMapper == null || deliveryTemplateMessageMapper == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long accountId : accountIds) {
            XianyuAccount account = accountMapper.selectById(accountId);
            if (account == null || account.getUnb() == null || account.getUnb().isBlank()) {
                throw new IllegalStateException("发货模板找不到关联账号: " + accountId);
            }
            List<DeliveryTemplate> templates = deliveryTemplateMapper.selectByAccount(accountId);
            if (templates == null) {
                continue;
            }
            for (DeliveryTemplate template : templates) {
                Map<String, Object> templateMap = new LinkedHashMap<>();
                templateMap.put("sourceTemplateId", template.getId());
                templateMap.put("unb", account.getUnb());
                templateMap.put("name", template.getName());
                templateMap.put("enabled", template.getEnabled());
                List<Map<String, Object>> messages = new ArrayList<>();
                List<DeliveryTemplateMessage> templateMessages = deliveryTemplateMessageMapper
                        .selectByTemplateId(template.getId());
                if (templateMessages != null) {
                    for (DeliveryTemplateMessage message : templateMessages) {
                        Map<String, Object> messageMap = new LinkedHashMap<>();
                        messageMap.put("sortOrder", message.getSortOrder());
                        messageMap.put("messageType", message.getMessageType());
                        messageMap.put("content", message.getContent());
                        messages.add(messageMap);
                    }
                }
                templateMap.put("messages", messages);
                result.add(templateMap);
            }
        }
        return result;
    }

    /**
     * 先恢复模板再恢复商品配置，保证 deliveryTemplateId 永远指向当前库中的模板。
     * 模板按「账号 + 名称」幂等更新；同名模板恢复到已有模板时复用目标 ID。
     */
    private Map<Long, Long> importTemplates(Map<String, Object> data,
                                            Map<String, Object> context,
                                            Map<String, Long> unbToAccountId) {
        Map<Long, Long> mapping = new LinkedHashMap<>();
        Object raw = data.get("deliveryTemplates");
        if (raw == null) {
            return mapping;
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("发货模板备份字段必须是数组");
        }
        if (deliveryTemplateMapper == null || deliveryTemplateMessageMapper == null) {
            throw new IllegalStateException("当前版本不支持恢复发货模板，请先升级到包含 V3_27 的版本");
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> rawMap)) {
                throw new IllegalStateException("发货模板备份条目格式错误");
            }
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> item : rawMap.entrySet()) {
                if (!(item.getKey() instanceof String key)) {
                    throw new IllegalStateException("发货模板字段名必须是字符串");
                }
                map.put(key, item.getValue());
            }
            long sourceId = requiredLong(map.get("sourceTemplateId"), "sourceTemplateId");
            String unb = requiredText(map.get("unb"), "unb");
            Long accountId = unbToAccountId.get(unb);
            if (accountId == null) {
                throw new IllegalStateException("找不到发货模板关联账号: " + unb);
            }
            String name = requiredText(map.get("name"), "name");
            Integer enabled = map.get("enabled") == null ? 1 : number(map.get("enabled"), "enabled");
            List<DeliveryTemplateMessage> messages = parseTemplateMessages(map.get("messages"));

            DeliveryTemplate target = null;
            List<DeliveryTemplate> existing = deliveryTemplateMapper.selectByAccount(accountId);
            if (existing != null) {
                for (DeliveryTemplate candidate : existing) {
                    if (Objects.equals(candidate.getName(), name)) {
                        target = candidate;
                        break;
                    }
                }
            }
            if (target == null) {
                target = new DeliveryTemplate();
                target.setXianyuAccountId(accountId);
                target.setName(name);
                target.setEnabled(enabled);
                deliveryTemplateMapper.insert(target);
            } else {
                target.setEnabled(enabled);
                deliveryTemplateMapper.update(target);
                deliveryTemplateMessageMapper.deleteByTemplateId(target.getId());
            }
            if (target.getId() == null) {
                throw new IllegalStateException("发货模板写入后未返回目标ID: " + name);
            }
            for (DeliveryTemplateMessage message : messages) {
                message.setTemplateId(target.getId());
                deliveryTemplateMessageMapper.insert(message);
            }
            mapping.put(sourceId, target.getId());
        }
        return mapping;
    }

    private List<DeliveryTemplateMessage> parseTemplateMessages(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("发货模板至少需要一条消息");
        }
        List<DeliveryTemplateMessage> messages = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> rawMap)) {
                throw new IllegalStateException("发货模板消息格式错误");
            }
            Object sortOrder = rawMap.get("sortOrder");
            Object content = rawMap.get("content");
            if (sortOrder == null || content == null || content.toString().isBlank()) {
                throw new IllegalStateException("发货模板消息缺少顺序或内容");
            }
            DeliveryTemplateMessage message = new DeliveryTemplateMessage();
            message.setSortOrder(number(sortOrder, "sortOrder"));
            message.setMessageType(rawMap.get("messageType") == null
                    ? "TEXT" : rawMap.get("messageType").toString());
            message.setContent(content.toString());
            messages.add(message);
        }
        return messages;
    }

    private Long remapDeliveryTemplateId(Object rawSourceId,
                                         Map<Long, Long> mapping,
                                         Long accountId) {
        if (rawSourceId == null) {
            return null;
        }
        long sourceId = requiredLong(rawSourceId, "deliveryTemplateId");
        Long targetId = mapping.get(sourceId);
        if (targetId == null) {
            throw new IllegalStateException("自动发货配置引用了未恢复的发货模板: sourceTemplateId="
                    + sourceId + ", accountId=" + accountId);
        }
        return targetId;
    }

    private long requiredLong(Object value, String field) {
        if (value == null) {
            throw new IllegalStateException("发货模板字段缺少 " + field);
        }
        return ((Number) value).longValue();
    }

    private String requiredText(Object value, String field) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("发货模板字段缺少 " + field);
        }
        return value.toString();
    }

    private int number(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("发货模板字段 " + field + " 必须是数字");
        }
        return number.intValue();
    }

    private void importSwitches(Map<String, Object> data, Map<String, Long> unbToAccountId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> switchMaps = (List<Map<String, Object>>) data.get("autoDeliverySwitches");
        if (switchMaps == null) {
            return;
        }
        for (Map<String, Object> map : switchMaps) {
            String unb = (String) map.get("unb");
            String xyGoodsId = (String) map.get("xyGoodsId");
            if (unb == null || xyGoodsId == null || !map.containsKey("autoDeliveryOn")) {
                throw new IllegalArgumentException("自动发货总开关缺少账号、商品标识或开关值");
            }
            Long accountId = unbToAccountId.get(unb);
            if (accountId == null) {
                throw new IllegalStateException("找不到自动发货总开关关联账号: " + unb);
            }
            Integer enabled = map.get("autoDeliveryOn") != null
                    ? ((Number) map.get("autoDeliveryOn")).intValue() : null;
            jdbcTemplate.update(
                    "INSERT INTO xianyu_goods_config " +
                            "(xianyu_account_id, xy_goods_id, xianyu_auto_delivery_on) VALUES (?, ?, ?) " +
                            "ON CONFLICT(xianyu_account_id, xy_goods_id) DO UPDATE SET " +
                            "xianyu_auto_delivery_on = excluded.xianyu_auto_delivery_on",
                    accountId, xyGoodsId, enabled);
        }
    }

    private String remapKamiConfigIds(String rawConfigIds,
                                      Map<Long, Long> configIdMapping,
                                      Long accountId,
                                      Map<String, Object> context) {
        if (rawConfigIds == null || rawConfigIds.isBlank()) {
            return rawConfigIds;
        }

        List<String> mappedIds = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Set<String> selectedModules = context.get(DataBackupHandler.SELECTED_MODULES_CONTEXT_KEY)
                instanceof Set<?> selectedSet ? (Set<String>) selectedSet : Set.of();
        String version = Objects.toString(
                context.get(DataBackupHandler.BACKUP_VERSION_CONTEXT_KEY), "1.0");
        @SuppressWarnings("unchecked")
        Set<Long> preexistingConfigIds =
                context.get(KamiBackupHandler.PREEXISTING_CONFIG_IDS_CONTEXT_KEY)
                        instanceof Set<?> idSet ? (Set<Long>) idSet : Set.of();
        boolean legacyKamiImportWithoutMapping =
                "1.0".equals(version) && selectedModules.contains("kami");
        for (String rawConfigId : rawConfigIds.split(",", -1)) {
            long oldConfigId;
            try {
                oldConfigId = Long.parseLong(rawConfigId.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("卡密配置ID格式错误: " + rawConfigId, e);
            }
            Long newConfigId = configIdMapping != null ? configIdMapping.get(oldConfigId) : null;
            if (newConfigId != null) {
                mappedIds.add(newConfigId.toString());
                continue;
            }

            XianyuKamiConfig existing = kamiConfigMapper.selectById(oldConfigId);
            if (existing != null
                    && Objects.equals(existing.getXianyuAccountId(), accountId)
                    && (!legacyKamiImportWithoutMapping || preexistingConfigIds.contains(oldConfigId))) {
                mappedIds.add(Long.toString(oldConfigId));
                continue;
            }

            if (legacyKamiImportWithoutMapping) {
                throw new IllegalStateException(
                        "旧版1.0备份未记录卡密配置源ID，无法安全映射卡密配置 " + oldConfigId +
                                "；只有导入前已存在且归属正确的同ID仓库可保留，" +
                                "否则请在源端升级后重新导出2.0备份，不能按插入顺序或别名猜测绑定");
            }
            if (existing != null) {
                throw new IllegalStateException(
                        "目标卡密配置 " + oldConfigId + " 不属于当前账号；请同时导入卡密仓库模块");
            }
            throw new IllegalStateException(
                    "目标库不存在可安全复用的卡密配置 " + oldConfigId + "；请同时导入卡密仓库模块");
        }
        return String.join(",", mappedIds);
    }
}
