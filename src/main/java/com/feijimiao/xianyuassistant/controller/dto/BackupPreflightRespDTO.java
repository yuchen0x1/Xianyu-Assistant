package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入预检结果。只描述“将会发生什么”，不代表已经写入。
 */
@Data
public class BackupPreflightRespDTO {

    private boolean importable;
    private String backupVersion;
    private String exportTime;
    private Boolean includeSecrets;
    private List<String> availableModules = new ArrayList<>();
    private List<String> importOrder = new ArrayList<>();
    private List<ModulePreview> modules = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private List<String> errors = new ArrayList<>();

    @Data
    public static class ModulePreview {
        private String moduleKey;
        private String moduleName;
        private int recordCount;
        private List<String> missingFields = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }
}
