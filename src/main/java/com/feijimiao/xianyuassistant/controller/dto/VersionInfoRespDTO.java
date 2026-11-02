package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

@Data
public class VersionInfoRespDTO {
    private String currentVersion;
    private String latestVersion;
    private Boolean hasUpdate;
    private String updateContent;
    private String publishedAt;
    private String downloadUrl;
    /**
     * 更新源检查结果。旧客户端可以忽略这些可选字段。
     */
    private String checkStatus;
    private String message;
    /**
     * 更新通道：默认 GitHub；旧客户端可以忽略该可选字段。
     */
    private String channel;
}
