package com.feijimiao.xianyuassistant.backup.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.entity.XianyuKamiConfig;
import com.feijimiao.xianyuassistant.entity.XianyuKamiItem;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiConfigMapper;
import com.feijimiao.xianyuassistant.mapper.XianyuKamiItemMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class KamiBackupHandler implements DataBackupHandler {

    static final String CONFIG_ID_MAPPING_CONTEXT_KEY = "kamiConfigIdMapping";
    static final String PREEXISTING_CONFIG_IDS_CONTEXT_KEY = "preexistingKamiConfigIds";

    @Autowired
    private XianyuKamiConfigMapper kamiConfigMapper;

    @Autowired
    private XianyuKamiItemMapper kamiItemMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Override
    public String getModuleKey() {
        return "kami";
    }

    @Override
    public String getModuleName() {
        return "卡密仓库";
    }

    @Override
    public Set<String> getRequiredImportListKeys() {
        return Set.of("kamiConfigs", "kamiItems");
    }

    @Override
    public Map<String, Object> exportData() {
        List<XianyuKamiConfig> kamiConfigs = kamiConfigMapper.selectList(null);

        List<Map<String, Object>> configList = new ArrayList<>();
        Map<Long, String> configIdToUnb = new HashMap<>();
        for (XianyuKamiConfig config : kamiConfigs) {
            XianyuAccount account = accountMapper.selectById(config.getXianyuAccountId());
            if (account == null || account.getUnb() == null || account.getUnb().isBlank()) {
                throw new IllegalStateException(
                        "卡密配置找不到关联账号: " + config.getXianyuAccountId());
            }

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("configId", config.getId());
            map.put("unb", account.getUnb());
            map.put("aliasName", config.getAliasName());
            map.put("alertEnabled", config.getAlertEnabled());
            map.put("alertThresholdType", config.getAlertThresholdType());
            map.put("alertThresholdValue", config.getAlertThresholdValue());
            map.put("alertEmail", config.getAlertEmail());
            configList.add(map);
            configIdToUnb.put(config.getId(), account.getUnb());
        }

        List<Map<String, Object>> itemList = new ArrayList<>();
        for (XianyuKamiConfig config : kamiConfigs) {
            List<XianyuKamiItem> items = kamiItemMapper.findByConfigIdAndStatus(config.getId(), 0);
            String unb = configIdToUnb.get(config.getId());
            if (unb == null) {
                throw new IllegalStateException("卡密配置缺少可导出的账号: " + config.getId());
            }

            for (XianyuKamiItem item : items) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("unb", unb);
                map.put("aliasName", config.getAliasName());
                map.put("kamiContent", item.getKamiContent());
                itemList.add(map);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kamiConfigs", configList);
        data.put("kamiItems", itemList);
        return data;
    }

    @Override
    public void importData(Map<String, Object> data, Map<String, Object> context) {
        if (data == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Long> unbToAccountId = context.get("unbToAccountId") != null
                ? (Map<String, Long>) context.get("unbToAccountId")
                : Collections.emptyMap();

        Map<String, Long> configKeyToId = new HashMap<>();
        Map<Long, Long> oldToNewConfigId = new LinkedHashMap<>();
        Set<Long> preexistingConfigIds = new LinkedHashSet<>();
        boolean version2 = "2.0".equals(context.get(BACKUP_VERSION_CONTEXT_KEY));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> configMaps = (List<Map<String, Object>>) data.get("kamiConfigs");
        if (configMaps != null) {
            for (Map<String, Object> map : configMaps) {
                try {
                    String unb = (String) map.get("unb");
                    String aliasName = (String) map.get("aliasName");
                    if (unb == null || aliasName == null) {
                        throw new IllegalArgumentException("卡密配置缺少账号或别名");
                    }
                    Long oldConfigId = readOptionalConfigId(map.get("configId"));
                    if (version2 && oldConfigId == null) {
                        throw new IllegalArgumentException(
                                "2.0备份的卡密配置缺少configId，无法建立可靠引用映射");
                    }

                    Long accountId = unbToAccountId.get(unb);
                    if (accountId == null) {
                        throw new IllegalStateException("找不到卡密配置关联账号: " + unb);
                    }

                    LambdaQueryWrapper<XianyuKamiConfig> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(XianyuKamiConfig::getXianyuAccountId, accountId)
                           .eq(XianyuKamiConfig::getAliasName, aliasName);
                    XianyuKamiConfig existing = kamiConfigMapper.selectOne(wrapper);

                    XianyuKamiConfig config = new XianyuKamiConfig();
                    config.setXianyuAccountId(accountId);
                    config.setAliasName(aliasName);
                    config.setAlertEnabled(map.get("alertEnabled") != null ? ((Number) map.get("alertEnabled")).intValue() : null);
                    config.setAlertThresholdType(map.get("alertThresholdType") != null ? ((Number) map.get("alertThresholdType")).intValue() : null);
                    config.setAlertThresholdValue(map.get("alertThresholdValue") != null ? ((Number) map.get("alertThresholdValue")).intValue() : null);
                    config.setAlertEmail((String) map.get("alertEmail"));

                    if (existing == null) {
                        config.setTotalCount(0);
                        config.setUsedCount(0);
                        kamiConfigMapper.insert(config);
                    } else {
                        config.setId(existing.getId());
                        preexistingConfigIds.add(existing.getId());
                        config.setTotalCount(existing.getTotalCount());
                        config.setUsedCount(existing.getUsedCount());
                        kamiConfigMapper.updateById(config);
                    }
                    if (config.getId() == null) {
                        throw new IllegalStateException("数据库未返回导入后的卡密配置ID");
                    }
                    configKeyToId.put(unb + ":" + aliasName, config.getId());

                    if (oldConfigId != null) {
                        Long previous = oldToNewConfigId.putIfAbsent(oldConfigId, config.getId());
                        if (previous != null && !previous.equals(config.getId())) {
                            throw new IllegalStateException("卡密配置源ID重复且目标不一致: " + oldConfigId);
                        }
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("导入单条卡密配置失败", e);
                }
            }
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemMaps = (List<Map<String, Object>>) data.get("kamiItems");
        if (itemMaps != null) {
            for (Map<String, Object> map : itemMaps) {
                try {
                    String unb = (String) map.get("unb");
                    String aliasName = (String) map.get("aliasName");
                    String kamiContent = (String) map.get("kamiContent");
                    if (unb == null || aliasName == null || kamiContent == null) {
                        throw new IllegalArgumentException("卡密项缺少账号、配置或内容");
                    }

                    Long configId = configKeyToId.get(unb + ":" + aliasName);
                    if (configId == null) {
                        throw new IllegalStateException("找不到卡密项关联配置: " + unb + ":" + aliasName);
                    }

                    LambdaQueryWrapper<XianyuKamiItem> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(XianyuKamiItem::getKamiConfigId, configId)
                           .eq(XianyuKamiItem::getKamiContent, kamiContent);
                    XianyuKamiItem existing = kamiItemMapper.selectOne(wrapper);
                    if (existing != null) continue;

                    XianyuKamiItem item = new XianyuKamiItem();
                    item.setKamiConfigId(configId);
                    item.setKamiContent(kamiContent);
                    item.setStatus(0);
                    item.setSortOrder(0);
                    kamiItemMapper.insert(item);
                } catch (Exception e) {
                    throw new IllegalStateException("导入单条卡密项失败", e);
                }
            }
        }

        for (Long configId : new LinkedHashSet<>(configKeyToId.values())) {
            refreshConfigCounts(configId);
        }
        context.put(CONFIG_ID_MAPPING_CONTEXT_KEY, oldToNewConfigId);
        context.put(PREEXISTING_CONFIG_IDS_CONTEXT_KEY,
                Collections.unmodifiableSet(preexistingConfigIds));
    }

    private Long readOptionalConfigId(Object rawConfigId) {
        if (rawConfigId == null) {
            return null;
        }
        if (!(rawConfigId instanceof Number number)) {
            throw new IllegalArgumentException("卡密配置源ID格式错误: " + rawConfigId);
        }
        long configId = number.longValue();
        if (configId <= 0 || number.doubleValue() != (double) configId) {
            throw new IllegalArgumentException("卡密配置源ID格式错误: " + rawConfigId);
        }
        return configId;
    }

    private void refreshConfigCounts(Long configId) {
        XianyuKamiConfig config = kamiConfigMapper.selectById(configId);
        if (config == null) {
            throw new IllegalStateException("找不到导入后的卡密配置: " + configId);
        }
        config.setTotalCount(kamiItemMapper.countByConfigId(configId));
        config.setUsedCount(kamiItemMapper.countUsed(configId));
        kamiConfigMapper.updateById(config);
    }
}
