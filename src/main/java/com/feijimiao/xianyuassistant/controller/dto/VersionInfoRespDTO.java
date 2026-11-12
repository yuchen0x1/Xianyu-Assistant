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
    /** 发布物名称与大小，来自同一 Release 的资产列表。 */
    private String assetName;
    private Long assetSize;
    /** Release 是否为预发布版本；稳定通道通常为 false。 */
    private Boolean prerelease;
    /** 由发布说明显式声明的策略标记；未声明时为 false。 */
    private Boolean mandatory;
    private Boolean securityFix;
    /** Release 通道：stable、beta 或 dev。 */
    private String releaseChannel;
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
