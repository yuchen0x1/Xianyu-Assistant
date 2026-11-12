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
     * 更新阶段：IDLE / DOWNLOADING / VERIFYING / SWAPPED / RESTARTING / FAILED / CANCELLED
     */
    private String phase;

    /**
     * 下载进度 0-100
     */
    private int percent;

    private long downloadedBytes;

    private long totalBytes;

    /** 最近一次可计算出的下载速度（字节/秒）。 */
    private long downloadSpeedBytesPerSecond;

    /** 按最近下载速度估算的剩余秒数；无法估算时为 0。 */
    private long estimatedSecondsRemaining;

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
