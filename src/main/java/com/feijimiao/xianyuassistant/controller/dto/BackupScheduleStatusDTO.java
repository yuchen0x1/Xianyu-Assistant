package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class BackupScheduleStatusDTO {
    private Boolean enabled;
    private Long intervalMillis;
    private Integer retentionCount;
    private String directory;
    private String latestBackupFile;
    private Instant latestBackupAt;
    private Instant nextBackupAt;
    private String lastError;
}
