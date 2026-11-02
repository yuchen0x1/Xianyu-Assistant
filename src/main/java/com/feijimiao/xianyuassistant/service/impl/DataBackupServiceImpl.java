package com.feijimiao.xianyuassistant.service.impl;

import com.feijimiao.xianyuassistant.exception.BusinessException;

import com.feijimiao.xianyuassistant.backup.DataBackupHandler;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper;
import com.feijimiao.xianyuassistant.service.DataBackupService;
import com.feijimiao.xianyuassistant.service.bo.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class DataBackupServiceImpl implements DataBackupService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String BACKUP_VERSION = "2.0";
    private static final Set<String> SUPPORTED_BACKUP_VERSIONS = Set.of("1.0", BACKUP_VERSION);
    private static final List<String> BACKUP_CAPABILITIES = List.of(
            "stableKamiConfigIdMapping",
            "completeAutoReplySettings",
            "completeAutoDeliverySettings",
            "deliveryBlockRules",
            "goodsSku",
            "completeOrderHistory",
            "completeChatLogs",
            "completeNotifications",
            "jobsAndMonitoring",
            "publishHistory"
    );
    private static final List<String> IMPORT_ORDER = List.of(
            "account", "deliveryBlockRule", "goods", "kami", "autoReply", "autoDelivery", "systemSetting",
            "orders", "chatLogs", "notifications", "publishHistory", "jobs"
    );

    @Autowired
    private List<DataBackupHandler> handlers;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Override
    public List<BackupModuleRespBO> getModules() {
        List<BackupModuleRespBO> result = new ArrayList<>();
        for (DataBackupHandler handler : handlers) {
            BackupModuleRespBO bo = new BackupModuleRespBO();
            bo.setModuleKey(handler.getModuleKey());
            bo.setModuleName(handler.getModuleName());
            result.add(bo);
        }
        return result;
    }

    @Override
    public BackupExportRespBO exportData(BackupExportReqBO reqBO) {
        // 保留给「确实需要字符串」的内部调用方与回归测试：内部复用流式写出，
        // 保证内容与流式下载完全一致（字段顺序、模块顺序都相同）。
        // 注意：生产 HTTP 端点与定时备份都已改用 exportDataToStream，不再经过
        // 这里——整份 JSON 会同时占 byte[] 和 String 两份内存，大账号有 OOM 风险。
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 20);
        exportDataToStream(buffer, reqBO);
        BackupExportRespBO respBO = new BackupExportRespBO();
        respBO.setJsonData(buffer.toString(StandardCharsets.UTF_8));
        return respBO;
    }

    @Override
    public void exportDataToStream(OutputStream out, BackupExportReqBO reqBO) {
        Map<String, DataBackupHandler> handlerMap = buildHandlerMap();
        LinkedHashSet<String> selectedModules = reqBO.getModules() == null
                ? new LinkedHashSet<>(handlerMap.keySet())
                : new LinkedHashSet<>(reqBO.getModules());
        if (selectedModules.isEmpty()) {
            throw new IllegalArgumentException("未选择可导出的备份模块");
        }
        for (String moduleKey : selectedModules) {
            if (!handlerMap.containsKey(moduleKey)) {
                throw new IllegalArgumentException("不支持的备份模块: " + moduleKey);
            }
        }

        Map<String, Object> moduleContext = new LinkedHashMap<>();
        moduleContext.put(DataBackupHandler.EXPORT_INCLUDE_SECRETS_KEY, reqBO.getIncludeSecrets());

        // 逐字段手写而不是先拼 Map：模块写完就能被 GC 回收，峰值只跟最大的单个
        // 模块有关。AUTO_CLOSE_TARGET 关闭，输出流由调用方（HTTP 层）负责关闭。
        JsonGenerator generator = null;
        try {
            generator = objectMapper.getFactory().createGenerator(out);
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

            generator.writeStartObject();
            generator.writeStringField("exportTime", LocalDateTime.now().format(FORMATTER));
            generator.writeStringField("version", BACKUP_VERSION);
            generator.writeArrayFieldStart("capabilities");
            for (String capability : BACKUP_CAPABILITIES) {
                generator.writeString(capability);
            }
            generator.writeEndArray();
            generator.writeBooleanField("includeSecrets", Boolean.TRUE.equals(reqBO.getIncludeSecrets()));

            generator.writeObjectFieldStart("modules");
            for (String moduleKey : selectedModules) {
                DataBackupHandler handler = handlerMap.get(moduleKey);
                generator.writeFieldName(moduleKey);
                try {
                    if (handler.supportsStreamingExport()) {
                        // 大数据量模块：自己分页写，峰值只跟单页有关
                        handler.exportJson(generator, moduleContext);
                    } else {
                        Map<String, Object> moduleData = handler.exportData(moduleContext);
                        if (moduleData == null) {
                            throw new IllegalStateException("模块未返回备份数据");
                        }
                        generator.writeObject(moduleData);
                    }
                    generator.flush();
                    log.info("[DataBackup] 导出模块 {} 成功", moduleKey);
                } catch (Exception e) {
                    log.error("[DataBackup] 导出模块 {} 失败", moduleKey, e);
                    throw new IllegalStateException("导出模块失败: " + moduleKey, e);
                }
            }
            generator.writeEndObject();
            generator.writeEndObject();
            generator.flush();
        } catch (IOException e) {
            throw new BusinessException("序列化备份数据失败: " + e.getMessage(), e);
        } finally {
            if (generator != null) {
                try {
                    generator.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响已写出的内容
                }
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BackupImportRespBO importData(BackupImportReqBO reqBO) {
        BackupImportRespBO respBO = new BackupImportRespBO();
        List<String> failedModules = new ArrayList<>();
        int totalCount = 0;
        int successCount = 0;

        Map<String, Object> allData;
        try {
            allData = objectMapper.readValue(reqBO.getJsonData(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException("解析备份数据失败: " + e.getMessage(), e);
        }

        Object version = allData.get("version");
        if (!(version instanceof String backupVersion)
                || !SUPPORTED_BACKUP_VERSIONS.contains(backupVersion)) {
            throw new IllegalArgumentException("不支持的备份版本: " + version);
        }

        Object rawModules = allData.get("modules");
        if (!(rawModules instanceof Map<?, ?> rawModulesMap)) {
            throw new BusinessException("备份数据格式错误: 缺少 modules 字段");
        }
        Map<String, Object> modulesData = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawModulesMap.entrySet()) {
            if (!(entry.getKey() instanceof String moduleKey)) {
                throw new IllegalArgumentException("备份数据格式错误: 模块键必须是字符串");
            }
            modulesData.put(moduleKey, entry.getValue());
        }

        List<String> selectedModules = reqBO.getModules();

        Map<String, DataBackupHandler> handlerMap = buildHandlerMap();

        List<String> modulesToImport = resolveModulesToImport(
                selectedModules, modulesData, handlerMap, backupVersion);
        Map<String, Object> importContext = new LinkedHashMap<>();
        importContext.put(DataBackupHandler.BACKUP_VERSION_CONTEXT_KEY, backupVersion);
        importContext.put(DataBackupHandler.SELECTED_MODULES_CONTEXT_KEY,
                Collections.unmodifiableSet(new LinkedHashSet<>(modulesToImport)));
        refreshAccountContext(importContext);

        for (String moduleKey : modulesToImport) {
            DataBackupHandler handler = handlerMap.get(moduleKey);
            totalCount++;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> moduleData = (Map<String, Object>) modulesData.get(moduleKey);
                handler.importData(moduleData, importContext);
                if ("account".equals(moduleKey)) {
                    refreshAccountContext(importContext);
                }
                successCount++;
                log.info("[DataBackup] 导入模块 {} 成功", moduleKey);
            } catch (Exception e) {
                failedModules.add(moduleKey);
                log.error("[DataBackup] 导入模块 {} 失败", moduleKey, e);
                throw new IllegalStateException("导入模块失败，已回滚: " + moduleKey, e);
            }
        }

        respBO.setTotalCount(totalCount);
        respBO.setSuccessCount(successCount);
        respBO.setFailedModules(failedModules);
        return respBO;
    }

    private List<String> resolveModulesToImport(List<String> selectedModules,
                                                Map<String, Object> modulesData,
                                                Map<String, DataBackupHandler> handlerMap,
                                                String backupVersion) {
        LinkedHashSet<String> requestedModules = selectedModules == null
                ? modulesData.keySet().stream()
                        .filter(moduleKey -> !moduleKey.startsWith("_"))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                : new LinkedHashSet<>(selectedModules);

        if (requestedModules.isEmpty()) {
            throw new IllegalArgumentException("未选择可导入的备份模块");
        }

        for (String moduleKey : requestedModules) {
            DataBackupHandler handler = handlerMap.get(moduleKey);
            if (handler == null) {
                throw new IllegalArgumentException("不支持的备份模块: " + moduleKey);
            }
            if (!modulesData.containsKey(moduleKey)) {
                throw new IllegalArgumentException("备份数据缺少所选模块: " + moduleKey);
            }

            Object rawModuleData = modulesData.get(moduleKey);
            if (!(rawModuleData instanceof Map<?, ?> rawModuleMap)) {
                throw new IllegalArgumentException("备份模块必须是对象: " + moduleKey);
            }

            Map<String, Object> moduleData = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawModuleMap.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("备份模块字段必须是字符串: " + moduleKey);
                }
                moduleData.put(key, entry.getValue());
            }
            if (moduleData.containsKey("_error")) {
                throw new IllegalArgumentException("备份模块导出不完整，拒绝导入: " + moduleKey);
            }
            Set<String> requiredKeys = new LinkedHashSet<>(handler.getRequiredImportListKeys());
            Set<String> additionalRequiredKeys =
                    handler.getAdditionalRequiredImportListKeys(backupVersion);
            if (additionalRequiredKeys != null) {
                requiredKeys.addAll(additionalRequiredKeys);
            }
            for (String requiredKey : requiredKeys) {
                if (!(moduleData.get(requiredKey) instanceof List<?>)) {
                    throw new IllegalArgumentException(
                            "备份模块缺少数组字段 " + requiredKey + ": " + moduleKey);
                }
            }
            modulesData.put(moduleKey, moduleData);
        }

        Map<String, Integer> orderByModule = new HashMap<>();
        for (int i = 0; i < IMPORT_ORDER.size(); i++) {
            orderByModule.put(IMPORT_ORDER.get(i), i);
        }
        return requestedModules.stream()
                .sorted(Comparator
                        .comparingInt((String moduleKey) -> orderByModule.getOrDefault(moduleKey, Integer.MAX_VALUE))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private Map<String, DataBackupHandler> buildHandlerMap() {
        Map<String, DataBackupHandler> handlerMap = new LinkedHashMap<>();
        for (DataBackupHandler handler : handlers) {
            DataBackupHandler previous = handlerMap.put(handler.getModuleKey(), handler);
            if (previous != null) {
                throw new IllegalStateException("存在重复的备份模块处理器: " + handler.getModuleKey());
            }
        }
        return handlerMap;
    }

    private void refreshAccountContext(Map<String, Object> importContext) {
        Map<String, Long> unbToAccountId = new LinkedHashMap<>();
        List<XianyuAccount> accounts = accountMapper.selectList(null);
        if (accounts != null) {
            for (XianyuAccount account : accounts) {
                if (account.getId() != null && account.getUnb() != null && !account.getUnb().isBlank()) {
                    unbToAccountId.put(account.getUnb(), account.getId());
                }
            }
        }
        importContext.put("unbToAccountId", unbToAccountId);
    }
}
