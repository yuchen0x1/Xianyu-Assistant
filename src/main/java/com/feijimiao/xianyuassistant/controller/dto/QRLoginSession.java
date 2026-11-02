package com.feijimiao.xianyuassistant.controller.dto;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;

/**
 * 二维码登录会话
 */
@Data
public class QRLoginSession {
    
    private String sessionId;
    private String status;
    private String qrCodeUrl;
    private String qrContent;
    private Map<String, String> cookies = new HashMap<>();
    private String unb;
    private long createdTime;
    private long expireTime = 300000; // 5分钟过期（毫秒）
    private Map<String, String> params = new HashMap<>();
    private String verificationUrl;
    private Long targetAccountId;

    /**
     * 凭据保存成功后的账号ID（仅在 success 状态下有值，供状态接口回传前端）。
     */
    private Long persistedAccountId;

    /**
     * 凭据保存成功后的账号名。
     */
    private String persistedAccountNote;

    /**
     * 本次扫码是否为新添加账号（false 表示对既有账号更新凭据）。
     */
    private Boolean newAccount;
    
    public QRLoginSession(String sessionId) {
        this.sessionId = sessionId;
        this.status = "waiting";
        this.createdTime = System.currentTimeMillis();
    }

    public QRLoginSession(String sessionId, Long targetAccountId) {
        this(sessionId);
        this.targetAccountId = targetAccountId;
    }
    
    /**
     * 检查是否过期
     */
    public boolean isExpired() {
        return System.currentTimeMillis() - createdTime > expireTime;
    }
}
