package com.feijimiao.xianyuassistant.service;

import com.feijimiao.xianyuassistant.controller.dto.QRLoginResponse;
import com.feijimiao.xianyuassistant.controller.dto.QRStatusResponse;

/**
 * 二维码登录服务接口
 */
public interface QRLoginService {
    
    /**
     * 生成二维码
     */
    QRLoginResponse generateQRCode(Long targetAccountId);
    
    /**
     * 获取会话状态
     */
    QRStatusResponse getSessionStatus(String sessionId);
    
    /**
     * 清理过期会话
     */
    void cleanupExpiredSessions();
}
