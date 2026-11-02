package com.feijimiao.xianyuassistant.service.bo;

import lombok.Data;

import java.util.List;

@Data
public class BackupExportReqBO {
    private List<String> modules;
    /** 是否在备份包中包含明文敏感字段（Cookie/Token/密钥）。默认 false。 */
    private Boolean includeSecrets;
}
