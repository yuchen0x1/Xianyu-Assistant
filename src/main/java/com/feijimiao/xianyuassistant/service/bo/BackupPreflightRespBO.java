package com.feijimiao.xianyuassistant.service.bo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 备份导入预检结果：只读解析，不写库。
 */
@Data
public class BackupPreflightRespBO {

    private String backupVersion;
    private String exportTime;
    private Boolean includeSecrets;
    /** 备份文件中出现的全部模块（含当前版本无法识别的）。 */
    private List<String> availableModules = new ArrayList<>();
    /** 本次将会导入的模块，按真实导入顺序排列。 */
    private List<String> importOrder = new ArrayList<>();
    private List<ModuleDetail> modules = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private List<String> errors = new ArrayList<>();

    public boolean isImportable() {
        return errors.isEmpty();
    }

    @Data
    public static class ModuleDetail {
        private String moduleKey;
        private String moduleName;
        /** 该模块包含的记录条数（各必需数组字段长度之和）。 */
        private int recordCount;
        /** 备份中缺失或类型不对的必需字段。 */
        private List<String> missingFields = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }
}
