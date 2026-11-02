package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.controller.dto.QRLoginResponse;
import com.feijimiao.xianyuassistant.controller.dto.QRStatusResponse;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.service.QRLoginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 二维码登录控制器
 *
 * <p>扫码会为闲鱼账号生成/刷新登录凭据，属于账号写操作，
 * 与 {@code /api/account/manualAdd}、{@code /api/account/update} 同级保护。</p>
 */
@RestController
@RequestMapping("/api/qrlogin")
@RequirePermission("account:write")
public class QRLoginController {
    
    @Autowired
    private QRLoginService qrLoginService;
    
    /**
     * 生成二维码
     */
    @PostMapping("/generate")
    public ResultObject<QRLoginResponse> generateQRCode(
            @RequestParam(required = false) Long accountId) {
        return ResultObject.success(qrLoginService.generateQRCode(accountId));
    }
    
    /**
     * 获取会话状态
     */
    @PostMapping("/status/{sessionId}")
    public ResultObject<QRStatusResponse> getSessionStatus(@PathVariable String sessionId) {
        return ResultObject.success(qrLoginService.getSessionStatus(sessionId));
    }
    
    /**
     * 清理过期会话
     */
    @PostMapping("/cleanup")
    public ResultObject<Void> cleanupExpiredSessions() {
        qrLoginService.cleanupExpiredSessions();
        return ResultObject.success(null, "清理完成");
    }
}
