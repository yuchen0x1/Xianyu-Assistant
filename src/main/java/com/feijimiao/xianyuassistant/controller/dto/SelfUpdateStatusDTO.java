package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

/**
 * 在线自更新状态
 */
@Data
public class SelfUpdateStatusDTO {

    /**
     * 当前部署方式是否支持在线更新（以 JAR 方式运行且功能未关闭）
     */
    private boolean available;

    /**
     * 不支持时的原因
     */
    private String unavailableReason;

    /**
     * 更新阶段：IDLE / DOWNLOADING / VERIFYING / SWAPPED / RESTARTING / FAILED
     */
    private String phase;

    /**
     * 下载进度 0-100
     */
    private int percent;

    private long downloadedBytes;

    private long totalBytes;

    private String targetVersion;

    /**
     * 补充说明（失败原因、手动重启提示等）
     */
    private String message;

    /**
     * 新包已替换完成、可以执行重启
     */
    private boolean canRestart;
}
