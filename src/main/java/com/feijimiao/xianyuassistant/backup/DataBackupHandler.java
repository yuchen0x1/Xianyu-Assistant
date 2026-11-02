package com.feijimiao.xianyuassistant.backup;

import java.util.Map;
import java.util.Set;

public interface DataBackupHandler {

    String BACKUP_VERSION_CONTEXT_KEY = "backupVersion";
    String SELECTED_MODULES_CONTEXT_KEY = "selectedModules";
    /** 导出上下文键：是否包含明文敏感字段（Cookie/Token/密钥）。 */
    String EXPORT_INCLUDE_SECRETS_KEY = "includeSecrets";

    /**
     * 从导出上下文读取是否包含明文敏感字段。
     * 默认不导出明文秘密，仅在显式请求 includeSecrets=true 时返回 true。
     */
    static boolean exportSecrets(Map<String, Object> context) {
        return context != null && Boolean.TRUE.equals(context.get(EXPORT_INCLUDE_SECRETS_KEY));
    }

    String getModuleKey();

    String getModuleName();

    /**
     * 导入时必须存在且必须为数组的顶层字段。
     */
    default Set<String> getRequiredImportListKeys() {
        return Set.of();
    }

    /**
     * 新版格式额外要求的数组字段。旧版备份仍按基础字段校验。
     */
    default Set<String> getAdditionalRequiredImportListKeys(String backupVersion) {
        return Set.of();
    }

    Map<String, Object> exportData();

    /**
     * 是否支持流式导出。返回 true 的模块必须同时覆写
     * {@link #exportJson(com.fasterxml.jackson.core.JsonGenerator, Map)}。
     *
     * <p>之所以要一个显式开关、而不是"直接调 exportJson 然后默认实现兜底"：
     * 默认方法的兜底只在真实对象上有效，Mockito 等测试替身会把默认方法当成
     * 空实现，结果就是只写了个字段名、连值都没写，JSON 直接损坏。用开关把
     * "是否走流式"显式化，非流式模块仍走 {@code exportData()} 的原有路径，
     * 两条路都能被正常测试。</p>
     */
    default boolean supportsStreamingExport() {
        return false;
    }

    /**
     * 流式导出：把本模块的 JSON 直接写进 generator，不必先在内存里构造完整结构。
     *
     * <p>只有 {@link #supportsStreamingExport()} 返回 true 时才会被调用。
     * 大数据量模块（聊天记录）实现它可以把峰值内存压到"单页"级别。</p>
     */
    default void exportJson(com.fasterxml.jackson.core.JsonGenerator generator,
                            Map<String, Object> context) throws java.io.IOException {
        generator.writeObject(exportData(context));
    }

    /**
     * 带导出上下文的导出（如 EXPORT_INCLUDE_SECRETS_KEY）。
     * 需要按上下文调整导出内容的模块重写此方法；默认忽略上下文。
     */
    default Map<String, Object> exportData(Map<String, Object> context) {
        return exportData();
    }

    default void importData(Map<String, Object> data) {
        importData(data, new java.util.LinkedHashMap<>());
    }

    void importData(Map<String, Object> data, Map<String, Object> context);
}
