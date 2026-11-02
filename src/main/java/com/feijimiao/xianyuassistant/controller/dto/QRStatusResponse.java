package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;

/**
 * 二维码状态响应
 */
@Data
public class QRStatusResponse {

    private String status;
    private String verificationUrl;
    private String message;

    /**
     * 登录成功后返回已持久化的账号ID。
     */
    private Long accountId;

    /**
     * 登录成功后返回的账号名（已尝试回填闲鱼昵称）。
     */
    private String accountNote;

    /**
     * 是否为新添加的账号；重新扫码更新凭据时为 false。
     */
    private Boolean isNewAccount;
}
