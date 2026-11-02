package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

/**
 * 仅暴露本机服务可用性，不包含账号、Cookie、连接地址等敏感运行信息。
 */
@Data
public class SystemHealthRespDTO {
    private String status;
    private String version;
    private long timestamp;
}
