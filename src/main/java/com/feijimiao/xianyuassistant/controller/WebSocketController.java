package com.feijimiao.xianyuassistant.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.entity.XianyuAccount;
import com.feijimiao.xianyuassistant.controller.dto.UpdateCookieReqDTO;
import com.feijimiao.xianyuassistant.controller.dto.UpdateCookieRespDTO;
import com.feijimiao.xianyuassistant.service.CookieRefreshService;
import com.feijimiao.xianyuassistant.service.AutomationHealthService;
import com.feijimiao.xianyuassistant.service.ManualCaptchaBrowserService;
import com.feijimiao.xianyuassistant.service.WebSocketService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
/**
 * WebSocket控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/websocket")
@RequirePermission("account:read")
public class WebSocketController {

    @Autowired
    private WebSocketService webSocketService;
    
    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.TokenRefreshService tokenRefreshService;
    
    @Autowired
    private CookieRefreshService cookieRefreshService;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.SentMessageSaveService sentMessageSaveService;

    @Autowired
    private com.feijimiao.xianyuassistant.service.AutoReplyDelayService autoReplyDelayService;
    
    @Autowired
    private com.feijimiao.xianyuassistant.service.OperationLogService operationLogService;

    @Autowired
    private AutomationHealthService automationHealthService;

    @Autowired
    private ManualCaptchaBrowserService manualCaptchaBrowserService;

    /**
     * 启动WebSocket连接
     */
    @PostMapping("/start")
    @RequirePermission("account:write")
    public ResultObject<CaptchaInfoDTO> startWebSocket(@Valid @RequestBody StartWebSocketReqDTO reqDTO) {
        try {
            log.info("启动WebSocket请求: xianyuAccountId={}, 手动Token={}", 
                    reqDTO.getXianyuAccountId(), 
                    reqDTO.getAccessToken() != null ? "已提供" : "未提供");
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            if (!webSocketService.setAutoConnectEnabled(reqDTO.getXianyuAccountId(), true)) {
                return ResultObject.failed("账号不存在或无法启用自动连接");
            }
            
            boolean success;
            if (reqDTO.getAccessToken() != null && !reqDTO.getAccessToken().isEmpty()) {
                // 使用手动提供的 accessToken
                success = webSocketService.startWebSocketWithToken(
                        reqDTO.getXianyuAccountId(), 
                        reqDTO.getAccessToken()
                );
            } else {
                // 自动获取 accessToken
                success = webSocketService.startWebSocket(reqDTO.getXianyuAccountId());
            }
            
            if (success) {
                return ResultObject.success(null, "WebSocket连接已启动");
            } else {
                // 检查具体失败原因
                String errorMessage = getDetailedErrorMessage(reqDTO.getXianyuAccountId());
                return ResultObject.failed(errorMessage);
            }
            
        } catch (com.feijimiao.xianyuassistant.exception.CaptchaRequiredException e) {
            boolean captchaUrlPresent = e.getCaptchaUrl() != null && !e.getCaptchaUrl().isBlank();
            log.warn("⚠️ 需要滑块验证: accountId={}, captchaUrlPresent={}",
                    reqDTO.getXianyuAccountId(), captchaUrlPresent);
            CaptchaInfoDTO captchaInfo = new CaptchaInfoDTO();
            captchaInfo.setNeedCaptcha(true);
            captchaInfo.setCaptchaUrl(e.getCaptchaUrl());
            captchaInfo.setMessage("平台要求完成官方安全验证。请先在闲鱼 App 完成验证，再使用“扫码更新”；系统会等待登录态同步后自动恢复连接。");
            
            log.info("📋 滑块验证信息:");
            log.info("   - 账号ID: {}", reqDTO.getXianyuAccountId());
            log.info("   - 验证URL已提供: {}", captchaUrlPresent);
            log.info("   - 提示: 请访问 https://www.goofish.com/im 完成验证后手动更新Cookie和Token");
            
            ResultObject<CaptchaInfoDTO> result = new ResultObject<>(1001, "需要滑块验证", captchaInfo);
            return result;
        } catch (com.feijimiao.xianyuassistant.exception.CookieNotFoundException e) {
            log.error("Cookie未找到: accountId={}", reqDTO.getXianyuAccountId());
            return ResultObject.failed("WebSocket连接启动失败：" + e.getMessage());
        } catch (com.feijimiao.xianyuassistant.exception.CookieExpiredException e) {
            log.error("Cookie已过期: accountId={}", reqDTO.getXianyuAccountId());
            return ResultObject.failed("WebSocket连接启动失败：" + e.getMessage());
        } catch (com.feijimiao.xianyuassistant.exception.TokenInvalidException e) {
            log.error("Token无效: accountId={}", reqDTO.getXianyuAccountId());
            return ResultObject.failed("WebSocket连接启动失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("启动WebSocket失败: errorType={}", e.getClass().getSimpleName());
            return ResultObject.failed("启动WebSocket失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取详细的错误信息
     */
    private String getDetailedErrorMessage(Long xianyuAccountId) {
        try {
            com.feijimiao.xianyuassistant.service.WebSocketTokenService tokenService =
                    applicationContext.getBean(com.feijimiao.xianyuassistant.service.WebSocketTokenService.class);
            if (tokenService.getPendingCaptchaUrl(xianyuAccountId) != null) {
                return "WebSocket连接启动失败：Cookie登录状态有效，但平台仍要求官方安全验证。请先在闲鱼 App 完成验证，再使用“扫码更新”";
            }
            // 查询Cookie信息
            com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper cookieMapper = 
                    applicationContext.getBean(com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper.class);
            
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.feijimiao.xianyuassistant.entity.XianyuCookie> cookieQuery = 
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            cookieQuery.eq(com.feijimiao.xianyuassistant.entity.XianyuCookie::getXianyuAccountId, xianyuAccountId)
                    .orderByDesc(com.feijimiao.xianyuassistant.entity.XianyuCookie::getCreatedTime)
                    .last("LIMIT 1");
            com.feijimiao.xianyuassistant.entity.XianyuCookie cookie = cookieMapper.selectOne(cookieQuery);
            
            if (cookie == null) {
                return "WebSocket连接启动失败：未找到账号Cookie，请先配置Cookie";
            }
            
            // 检查Cookie状态
            if (cookie.getCookieStatus() != null && cookie.getCookieStatus() == 2) {
                return "WebSocket连接启动失败：Cookie已过期，请更新Cookie后重试";
            }
            
            if (cookie.getCookieStatus() != null && cookie.getCookieStatus() == 3) {
                return "WebSocket连接启动失败：Cookie已失效，请重新获取Cookie";
            }
            
            // 检查Cookie文本是否为空
            if (cookie.getCookieText() == null || cookie.getCookieText().trim().isEmpty()) {
                return "WebSocket连接启动失败：Cookie内容为空，请重新配置Cookie";
            }
            
            // 检查WebSocket Token
            if (cookie.getWebsocketToken() != null && !cookie.getWebsocketToken().isEmpty()) {
                // 检查Token是否过期
                if (cookie.getTokenExpireTime() != null) {
                    long now = System.currentTimeMillis();
                    if (cookie.getTokenExpireTime() <= now) {
                        return "WebSocket连接启动失败：WebSocket Token已过期，系统将自动刷新Token，请稍后重试";
                    }
                }
                // Token存在且未过期，但连接失败
                return "WebSocket连接启动失败：WebSocket Token无效或连接被拒绝，请尝试更新Cookie或稍后重试";
            }
            
            // Token不存在，可能是获取Token失败
            return "WebSocket连接启动失败：无法获取WebSocket Token，请检查Cookie是否有效或稍后重试";
            
        } catch (Exception e) {
            log.error("获取详细错误信息失败: errorType={}", e.getClass().getSimpleName());
            return "WebSocket连接启动失败：系统错误，请查看日志获取详细信息";
        }
    }

    /**
     * 停止WebSocket连接
     */
    @PostMapping("/stop")
    @RequirePermission("account:write")
    public ResultObject<String> stopWebSocket(@Valid @RequestBody StopWebSocketReqDTO reqDTO) {
        try {
            log.info("停止WebSocket请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            boolean success = webSocketService.setAutoConnectEnabled(
                    reqDTO.getXianyuAccountId(), false);
            
            if (success) {
                return ResultObject.success("WebSocket连接已停止");
            } else {
                return ResultObject.failed("WebSocket连接停止失败");
            }
            
        } catch (Exception e) {
            log.error("停止WebSocket失败: errorType={}", e.getClass().getSimpleName());
            return ResultObject.failed("停止WebSocket失败: " + e.getMessage());
        }
    }

    /**
     * 发送消息
     */
    @PostMapping("/sendMessage")
    @RequirePermission("message:write")
    public ResultObject<String> sendMessage(@Valid @RequestBody SendMessageReqDTO reqDTO) {
        try {
            int textLength = reqDTO.getText() == null ? 0 : reqDTO.getText().length();
            log.info("发送消息请求: xianyuAccountId={}, cid={}, toId={}, textLength={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getCid(), reqDTO.getToId(), textLength);
            
            // 参数校验
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            if (reqDTO.getCid() == null || reqDTO.getCid().isEmpty()) {
                return ResultObject.failed("会话ID(cid)不能为空");
            }
            if (reqDTO.getToId() == null || reqDTO.getToId().isEmpty()) {
                return ResultObject.failed("接收方ID(toId)不能为空");
            }
            if (reqDTO.getText() == null || reqDTO.getText().isEmpty()) {
                return ResultObject.failed("消息内容不能为空");
            }
            
            // 检查WebSocket连接状态
            if (!webSocketService.isConnected(reqDTO.getXianyuAccountId())) {
                return ResultObject.failed("WebSocket未连接，请先启动连接");
            }
            
            // 发送消息
            boolean success = webSocketService.sendMessage(
                    reqDTO.getXianyuAccountId(),
                    reqDTO.getCid(),
                    reqDTO.getToId(),
                    reqDTO.getText()
            );
            
            if (success) {
                sentMessageSaveService.saveManualReply(
                        reqDTO.getXianyuAccountId(),
                        reqDTO.getCid(),
                        reqDTO.getToId(),
                        reqDTO.getText(),
                        reqDTO.getXyGoodsId()
                );
                String sId = reqDTO.getToId() + "@goofish";
                autoReplyDelayService.recordSellerManualReply(
                        reqDTO.getXianyuAccountId(),
                        reqDTO.getXyGoodsId(),
                        sId
                );
                return ResultObject.success("消息发送成功");
            } else {
                return ResultObject.failed("消息发送失败");
            }
            
        } catch (Exception e) {
            log.error("发送消息失败: errorType={}", e.getClass().getSimpleName());
            return ResultObject.failed("发送消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送图片消息
     */
    @PostMapping("/sendImageMessage")
    @RequirePermission("message:write")
    public ResultObject<String> sendImageMessage(@Valid @RequestBody SendImageMessageReqDTO reqDTO) {
        try {
            int imageUrlLength = reqDTO.getImageUrl() == null ? 0 : reqDTO.getImageUrl().length();
            log.info("发送图片消息请求: xianyuAccountId={}, cid={}, toId={}, imageUrlLength={}",
                    reqDTO.getXianyuAccountId(), reqDTO.getCid(), reqDTO.getToId(), imageUrlLength);
            
            // 参数校验
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            if (reqDTO.getCid() == null || reqDTO.getCid().isEmpty()) {
                return ResultObject.failed("会话ID(cid)不能为空");
            }
            if (reqDTO.getToId() == null || reqDTO.getToId().isEmpty()) {
                return ResultObject.failed("接收方ID(toId)不能为空");
            }
            if (reqDTO.getImageUrl() == null || reqDTO.getImageUrl().isEmpty()) {
                return ResultObject.failed("图片URL不能为空");
            }
            
            // 检查WebSocket连接状态
            if (!webSocketService.isConnected(reqDTO.getXianyuAccountId())) {
                return ResultObject.failed("WebSocket未连接，请先启动连接");
            }
            
            // 获取图片尺寸，默认800x600
            int width = reqDTO.getWidth() != null && reqDTO.getWidth() > 0 ? reqDTO.getWidth() : 800;
            int height = reqDTO.getHeight() != null && reqDTO.getHeight() > 0 ? reqDTO.getHeight() : 600;
            
            // 发送图片消息
            boolean success = webSocketService.sendImageMessage(
                    reqDTO.getXianyuAccountId(),
                    reqDTO.getCid(),
                    reqDTO.getToId(),
                    reqDTO.getImageUrl(),
                    width,
                    height
            );
            
            if (success) {
                sentMessageSaveService.saveManualImageReply(
                        reqDTO.getXianyuAccountId(),
                        reqDTO.getCid(),
                        reqDTO.getToId(),
                        reqDTO.getImageUrl(),
                        reqDTO.getXyGoodsId()
                );
                String sId = reqDTO.getToId() + "@goofish";
                autoReplyDelayService.recordSellerManualReply(
                        reqDTO.getXianyuAccountId(),
                        reqDTO.getXyGoodsId(),
                        sId
                );
                return ResultObject.success("图片消息发送成功");
            } else {
                return ResultObject.failed("图片消息发送失败");
            }
            
        } catch (Exception e) {
            log.error("发送图片消息失败: errorType={}", e.getClass().getSimpleName());
            return ResultObject.failed("发送图片消息失败: " + e.getMessage());
        }
    }

    /**
     * 检查WebSocket连接状态
     */
    @PostMapping("/status")
    public ResultObject<WebSocketStatusRespDTO> getWebSocketStatus(@Valid @RequestBody GetWebSocketStatusReqDTO reqDTO) {
        try {
            // 优化：将日志级别从INFO降为DEBUG，减少日志刷屏
            log.debug("查询WebSocket状态: xianyuAccountId={}", reqDTO.getXianyuAccountId());

            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }

            boolean connected = webSocketService.isConnected(reqDTO.getXianyuAccountId());

            WebSocketStatusRespDTO respDTO = new WebSocketStatusRespDTO();
            respDTO.setXianyuAccountId(reqDTO.getXianyuAccountId());
            respDTO.setConnected(connected);
            com.feijimiao.xianyuassistant.service.WebSocketTokenService tokenService =
                    applicationContext.getBean(com.feijimiao.xianyuassistant.service.WebSocketTokenService.class);
            boolean captchaPending = !connected && tokenService.isCaptchaPending(reqDTO.getXianyuAccountId());
            boolean reconnecting = !connected && webSocketService.isReconnecting(reqDTO.getXianyuAccountId());
            respDTO.setCaptchaPending(captchaPending);
            respDTO.setReconnecting(reconnecting);
            respDTO.setReconnectAttempt(reconnecting ? webSocketService.getReconnectAttempt(reqDTO.getXianyuAccountId()) : 0);
            respDTO.setNextReconnectAt(reconnecting ? webSocketService.getNextReconnectAt(reqDTO.getXianyuAccountId()) : null);
            respDTO.setStatus(connected ? "已连接" : captchaPending ? "等待人工验证" : reconnecting ? "自动重连中" : "未连接");

            // 仅返回凭证状态和长度，避免把可复用凭证暴露给浏览器页面。
            com.feijimiao.xianyuassistant.service.AccountService accountService =
                    applicationContext.getBean(com.feijimiao.xianyuassistant.service.AccountService.class);

            // 查询Cookie信息
            com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper cookieMapper =
                    applicationContext.getBean(com.feijimiao.xianyuassistant.mapper.XianyuCookieMapper.class);

            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.feijimiao.xianyuassistant.entity.XianyuCookie> cookieQuery =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
            cookieQuery.eq(com.feijimiao.xianyuassistant.entity.XianyuCookie::getXianyuAccountId, reqDTO.getXianyuAccountId())
                    .orderByDesc(com.feijimiao.xianyuassistant.entity.XianyuCookie::getCreatedTime)
                    .last("LIMIT 1");
            com.feijimiao.xianyuassistant.entity.XianyuCookie cookie = cookieMapper.selectOne(cookieQuery);

            if (cookie != null) {
                respDTO.setCookieStatus(cookie.getCookieStatus());
                respDTO.setCookieConfigured(cookie.getCookieText() != null && !cookie.getCookieText().isBlank());
                respDTO.setCookieLength(cookie.getCookieText() != null ? cookie.getCookieText().length() : 0);
                respDTO.setH5TokenConfigured(cookie.getMH5Tk() != null && !cookie.getMH5Tk().isBlank());
                respDTO.setH5TokenLength(cookie.getMH5Tk() != null ? cookie.getMH5Tk().length() : 0);
                respDTO.setWebsocketTokenConfigured(
                        cookie.getWebsocketToken() != null && !cookie.getWebsocketToken().isBlank());
                respDTO.setWebsocketTokenLength(
                        cookie.getWebsocketToken() != null ? cookie.getWebsocketToken().length() : 0);
                respDTO.setTokenExpireTime(cookie.getTokenExpireTime());

                // 构建简洁的状态信息
                StringBuilder statusInfo = new StringBuilder();
                statusInfo.append("账号ID=").append(reqDTO.getXianyuAccountId());
                statusInfo.append(", 连接=").append(connected ? "✅" : "❌");
                statusInfo.append(", Cookie=").append(getCookieStatusText(cookie.getCookieStatus()));
                statusInfo.append(", Token=").append(cookie.getWebsocketToken() != null ? "✅" : "❌");

                if (cookie.getTokenExpireTime() != null) {
                    long now = System.currentTimeMillis();
                    long remaining = cookie.getTokenExpireTime() - now;
                    if (remaining > 0) {
                        statusInfo.append(", 剩余").append(remaining / 1000).append("秒");
                    } else {
                        statusInfo.append(", Token已过期");
                    }
                }

                // 优化：将日志级别从INFO降为DEBUG，减少日志刷屏
                log.debug("✅ WebSocket状态: {}", statusInfo);
            } else {
                respDTO.setCookieStatus(null);
                respDTO.setCookieConfigured(false);
                respDTO.setCookieLength(0);
                respDTO.setH5TokenConfigured(false);
                respDTO.setH5TokenLength(0);
                respDTO.setWebsocketTokenConfigured(false);
                respDTO.setWebsocketTokenLength(0);
                respDTO.setTokenExpireTime(null);

                log.warn("⚠️ WebSocket状态: 账号ID={}, 连接={}, Cookie=未找到",
                        reqDTO.getXianyuAccountId(), connected ? "✅" : "❌");
            }

            com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper goodsConfigMapper =
                    applicationContext.getBean(com.feijimiao.xianyuassistant.mapper.XianyuGoodsConfigMapper.class);
            try {
                java.util.List<com.feijimiao.xianyuassistant.entity.XianyuGoodsConfig> configs =
                        goodsConfigMapper.selectByAccountId(reqDTO.getXianyuAccountId());
                boolean hasAutoDelivery = configs != null && configs.stream()
                        .anyMatch(c -> c.getXianyuAutoDeliveryOn() != null && c.getXianyuAutoDeliveryOn() == 1);
                boolean hasAutoReply = configs != null && configs.stream()
                        .anyMatch(c -> c.getXianyuAutoReplyOn() != null && c.getXianyuAutoReplyOn() == 1);
                respDTO.setAutoDeliveryOn(hasAutoDelivery);
                respDTO.setAutoReplyOn(hasAutoReply);
            } catch (Exception e) {
                respDTO.setAutoDeliveryOn(null);
                respDTO.setAutoReplyOn(null);
            }

            AutomationHealthService.Snapshot health =
                    automationHealthService.getSnapshot(reqDTO.getXianyuAccountId());
            respDTO.setLastWebSocketMessageAt(health.getLastWebSocketMessageAt());
            respDTO.setWebSocketMessageCount(health.getWebSocketMessageCount());
            respDTO.setLastCustomerMessageAt(health.getLastCustomerMessageAt());
            respDTO.setLastAutoReplyStatus(health.getLastAutoReplyStatus());
            respDTO.setLastAutoReplyReason(health.getLastAutoReplyReason());
            respDTO.setLastAutoReplyUpdatedAt(health.getLastAutoReplyUpdatedAt());
            respDTO.setLastSuccessfulReplyAt(health.getLastSuccessfulReplyAt());

            return ResultObject.success(respDTO);

        } catch (Exception e) {
            log.error("查询WebSocket状态失败: errorType={}", e.getClass().getSimpleName());
            return ResultObject.failed("查询WebSocket状态失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取Cookie状态文本描述
     */
    private String getCookieStatusText(Integer cookieStatus) {
        if (cookieStatus == null) {
            return "未知";
        }
        switch (cookieStatus) {
            case 1:
                return "✅ 有效";
            case 2:
                return "⚠️ 已过期";
            case 3:
                return "❌ 已失效";
            default:
                return "未知状态(" + cookieStatus + ")";
        }
    }
    
    /**
     * 清除验证等待状态
     */
    @PostMapping("/clearCaptchaWait")
    @RequirePermission("account:write")
    public ResultObject<String> clearCaptchaWait(@Valid @RequestBody ClearCaptchaWaitReqDTO reqDTO) {
        try {
            log.info("清除验证等待状态: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }

            com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper accountMapper =
                    applicationContext.getBean(com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper.class);
            if (accountMapper.selectById(reqDTO.getXianyuAccountId()) == null) {
                return ResultObject.failed("账号不存在");
            }

            if (!webSocketService.recoverAfterManualCaptcha(reqDTO.getXianyuAccountId())) {
                return ResultObject.failed(1001,
                        "平台仍要求验证。闲鱼网页与助手后台会话隔离，请先更新最新Cookie或扫码更新，再点击恢复连接");
            }

            return ResultObject.success("验证已确认，WebSocket连接已恢复");
            
        } catch (Exception e) {
            log.error("清除验证等待状态失败: errorType={}", e.getClass().getSimpleName());
            return ResultObject.failed("清除验证等待状态失败: " + e.getMessage());
        }
    }

    /**
     * 更新Cookie
     */
    @PostMapping("/updateCookie")
    @RequirePermission("account:write")
    public ResultObject<UpdateCookieRespDTO> updateCookie(@Valid @RequestBody UpdateCookieReqDTO reqDTO) {
        try {
            log.info("更新Cookie请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            if (reqDTO.getCookieText() == null || reqDTO.getCookieText().trim().isEmpty()) {
                return ResultObject.failed("Cookie不能为空");
            }
            
            // 检查账号是否存在
            com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper accountMapper = 
                    applicationContext.getBean(com.feijimiao.xianyuassistant.mapper.XianyuAccountMapper.class);
            XianyuAccount account = accountMapper.selectById(reqDTO.getXianyuAccountId());
            if (account == null) {
                return ResultObject.failed("账号不存在");
            }
            
            // 从Cookie中提取UNB
            String unb = extractUnbFromCookie(reqDTO.getCookieText());
            if (unb == null || unb.isEmpty()) {
                return ResultObject.failed("无法从Cookie中提取UNB信息，请确保Cookie包含unb字段");
            }
            if (account.getUnb() != null && !account.getUnb().isBlank()
                    && !java.util.Objects.equals(account.getUnb(), unb)) {
                return ResultObject.failed("Cookie所属账号与当前账号不一致");
            }
            
            // 更新Cookie
            com.feijimiao.xianyuassistant.service.AccountService accountService = 
                    applicationContext.getBean(com.feijimiao.xianyuassistant.service.AccountService.class);
            boolean updated = accountService.updateAccountCookie(
                    reqDTO.getXianyuAccountId(), unb, reqDTO.getCookieText());
            if (!updated) {
                return ResultObject.failed("Cookie更新失败");
            }

            com.feijimiao.xianyuassistant.service.WebSocketTokenService tokenService =
                    applicationContext.getBean(com.feijimiao.xianyuassistant.service.WebSocketTokenService.class);
            tokenService.clearCaptchaWait(reqDTO.getXianyuAccountId());
            if (!webSocketService.resumeAutoConnection(reqDTO.getXianyuAccountId())) {
                return ResultObject.failed("Cookie已更新，但无法恢复WebSocket自动连接");
            }
            
            // 记录操作日志
            operationLogService.log(reqDTO.getXianyuAccountId(),
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Type.UPDATE,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Module.COOKIE,
                    "Cookie手动更新成功",
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Status.SUCCESS,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.COOKIE,
                    String.valueOf(reqDTO.getXianyuAccountId()),
                    null, null, null, null);
            
            UpdateCookieRespDTO respDTO = new UpdateCookieRespDTO();
            respDTO.setMessage("Cookie更新成功");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("更新Cookie失败: errorType={}", e.getClass().getSimpleName());
            
            // 记录失败日志
            operationLogService.log(reqDTO.getXianyuAccountId(),
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Type.UPDATE,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Module.COOKIE,
                    "Cookie手动更新失败",
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Status.FAIL,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.COOKIE,
                    String.valueOf(reqDTO.getXianyuAccountId()),
                    null, null, "errorType=" + e.getClass().getSimpleName(), null);
            
            return ResultObject.failed("更新Cookie失败: " + e.getMessage());
        }
    }
    
    /**
     * 从Cookie字符串中提取UNB值
     *
     * @param cookie Cookie字符串
     * @return UNB值，如果未找到则返回null
     */
    /**
     * 手动刷新Token
     */
    @PostMapping("/refreshToken")
    @RequirePermission("account:write")
    public ResultObject<RefreshTokenRespDTO> refreshToken(@Valid @RequestBody RefreshTokenReqDTO reqDTO) {
        try {
            log.info("手动刷新Token请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            RefreshTokenRespDTO respDTO = new RefreshTokenRespDTO();
            
            // 刷新_m_h5_tk token
            log.info("【账号{}】开始刷新_m_h5_tk token...", reqDTO.getXianyuAccountId());
            boolean mh5tkSuccess = tokenRefreshService.refreshMh5tkToken(reqDTO.getXianyuAccountId());
            respDTO.setMh5tkRefreshed(mh5tkSuccess);
            
            // 刷新WebSocket token
            log.info("【账号{}】开始刷新WebSocket token...", reqDTO.getXianyuAccountId());
            boolean wsTokenSuccess = tokenRefreshService.refreshWebSocketToken(reqDTO.getXianyuAccountId());
            respDTO.setWsTokenRefreshed(wsTokenSuccess);
            
            if (mh5tkSuccess && wsTokenSuccess) {
                respDTO.setMessage("✅ 所有Token刷新成功");
                log.info("【账号{}】✅ 所有Token刷新成功", reqDTO.getXianyuAccountId());
                
                operationLogService.log(reqDTO.getXianyuAccountId(),
                        com.feijimiao.xianyuassistant.constants.OperationConstants.Type.REFRESH,
                        com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                        "Token手动刷新成功",
                        com.feijimiao.xianyuassistant.constants.OperationConstants.Status.SUCCESS,
                        com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                        String.valueOf(reqDTO.getXianyuAccountId()),
                        null, null, null, null);
                
                return ResultObject.success(respDTO);
            } else if (mh5tkSuccess || wsTokenSuccess) {
                respDTO.setMessage("⚠️ 部分Token刷新成功");
                log.warn("【账号{}】⚠️ 部分Token刷新成功: _m_h5_tk={}, websocket_token={}", 
                        reqDTO.getXianyuAccountId(), mh5tkSuccess, wsTokenSuccess);
                
                operationLogService.log(reqDTO.getXianyuAccountId(),
                        com.feijimiao.xianyuassistant.constants.OperationConstants.Type.REFRESH,
                        com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                        "Token部分刷新成功",
                        com.feijimiao.xianyuassistant.constants.OperationConstants.Status.PARTIAL,
                        com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                        String.valueOf(reqDTO.getXianyuAccountId()),
                        null, null, "_m_h5_tk=" + mh5tkSuccess + ", ws=" + wsTokenSuccess, null);
                
                return ResultObject.success(respDTO);
            } else {
                respDTO.setMessage("❌ Token刷新失败，请检查Cookie是否有效");
                log.error("【账号{}】❌ Token刷新失败", reqDTO.getXianyuAccountId());
                
                operationLogService.log(reqDTO.getXianyuAccountId(),
                        com.feijimiao.xianyuassistant.constants.OperationConstants.Type.REFRESH,
                        com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                        "Token手动刷新失败",
                        com.feijimiao.xianyuassistant.constants.OperationConstants.Status.FAIL,
                        com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                        String.valueOf(reqDTO.getXianyuAccountId()),
                        null, null, "Cookie可能无效", null);
                
                return ResultObject.failed("Token刷新失败，请检查Cookie是否有效");
            }
            
        } catch (Exception e) {
            log.error("手动刷新Token异常: xianyuAccountId={}, errorType={}",
                    reqDTO.getXianyuAccountId(), e.getClass().getSimpleName());
            
            operationLogService.log(reqDTO.getXianyuAccountId(),
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Type.REFRESH,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                    "Token手动刷新异常",
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Status.FAIL,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                    String.valueOf(reqDTO.getXianyuAccountId()),
                    null, null, "errorType=" + e.getClass().getSimpleName(), null);
            
            return ResultObject.failed("刷新Token异常: " + e.getMessage());
        }
    }
    
    /**
     * 手动更新WebSocket Token
     */
    @PostMapping("/updateToken")
    @RequirePermission("account:write")
    public ResultObject<String> updateToken(@Valid @RequestBody UpdateTokenReqDTO reqDTO) {
        try {
            log.info("手动更新Token请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            if (reqDTO.getWebsocketToken() == null || reqDTO.getWebsocketToken().trim().isEmpty()) {
                return ResultObject.failed("WebSocket Token不能为空");
            }
            
            // 获取WebSocketTokenService
            com.feijimiao.xianyuassistant.service.WebSocketTokenService tokenService = 
                    applicationContext.getBean(com.feijimiao.xianyuassistant.service.WebSocketTokenService.class);
            
            // 保存Token
            tokenService.saveToken(reqDTO.getXianyuAccountId(), reqDTO.getWebsocketToken().trim());
            
            log.info("【账号{}】✅ WebSocket Token手动更新成功", reqDTO.getXianyuAccountId());
            
            operationLogService.log(reqDTO.getXianyuAccountId(),
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Type.UPDATE,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                    "WebSocket Token手动更新成功",
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Status.SUCCESS,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                    String.valueOf(reqDTO.getXianyuAccountId()),
                    null, null, null, null);
            
            return ResultObject.success("Token更新成功");
            
        } catch (Exception e) {
            log.error("手动更新Token异常: xianyuAccountId={}, errorType={}",
                    reqDTO.getXianyuAccountId(), e.getClass().getSimpleName());
            
            operationLogService.log(reqDTO.getXianyuAccountId(),
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Type.UPDATE,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Module.TOKEN,
                    "WebSocket Token手动更新失败",
                    com.feijimiao.xianyuassistant.constants.OperationConstants.Status.FAIL,
                    com.feijimiao.xianyuassistant.constants.OperationConstants.TargetType.TOKEN,
                    String.valueOf(reqDTO.getXianyuAccountId()),
                    null, null, "errorType=" + e.getClass().getSimpleName(), null);
            
            return ResultObject.failed("更新Token异常: " + e.getMessage());
        }
    }
    
    /**
     * 刷新Cookie
     * 参考Python: 通过hasLogin接口刷新Cookie
     */
    @PostMapping("/refreshCookie")
    @RequirePermission("account:write")
    public ResultObject<String> refreshCookie(@Valid @RequestBody RefreshCookieReqDTO reqDTO) {
        try {
            log.info("手动刷新Cookie请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            // 调用Cookie刷新服务
            boolean success = cookieRefreshService.refreshCookie(reqDTO.getXianyuAccountId());
            
            if (success) {
                log.info("【账号{}】✅ Cookie刷新成功", reqDTO.getXianyuAccountId());
                return ResultObject.success("Cookie刷新成功");
            } else {
                log.error("【账号{}】❌ Cookie刷新失败，请手动更新Cookie", reqDTO.getXianyuAccountId());
                return ResultObject.failed("Cookie刷新失败，请手动更新Cookie");
            }
            
        } catch (Exception e) {
            log.error("手动刷新Cookie异常: xianyuAccountId={}, errorType={}",
                    reqDTO.getXianyuAccountId(), e.getClass().getSimpleName());
            return ResultObject.failed("刷新Cookie异常: " + e.getMessage());
        }
    }
    
    /**
     * 检查登录状态
     */
    @PostMapping("/checkLogin")
    public ResultObject<String> checkLogin(@Valid @RequestBody CheckLoginReqDTO reqDTO) {
        try {
            log.info("检查登录状态请求: xianyuAccountId={}", reqDTO.getXianyuAccountId());
            
            if (reqDTO.getXianyuAccountId() == null) {
                return ResultObject.failed("账号ID不能为空");
            }
            
            // 调用Cookie刷新服务检查登录状态
            boolean valid = cookieRefreshService.checkLoginStatus(reqDTO.getXianyuAccountId());
            
            if (valid) {
                log.info("【账号{}】✅ 登录状态有效", reqDTO.getXianyuAccountId());
                return ResultObject.success("登录状态有效");
            } else {
                log.warn("【账号{}】⚠️ 登录状态无效，请更新Cookie", reqDTO.getXianyuAccountId());
                return ResultObject.failed("登录状态无效，请更新Cookie");
            }
            
        } catch (Exception e) {
            log.error("检查登录状态异常: xianyuAccountId={}, errorType={}",
                    reqDTO.getXianyuAccountId(), e.getClass().getSimpleName());
            return ResultObject.failed("检查登录状态异常: " + e.getMessage());
        }
    }

    private String extractUnbFromCookie(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return null;
        }
        
        // 查找unb=后面的值
        String[] cookieParts = cookie.split(";\\s*");
        for (String part : cookieParts) {
            if (part.startsWith("unb=")) {
                return part.substring(4); // "unb=".length() = 4
            }
        }
        
        return null;
    }

    /**
     * 启动WebSocket连接请求DTO
     */
    @Data
    public static class StartWebSocketReqDTO {
        private Long xianyuAccountId;  // 账号ID
        private String accessToken;    // 可选：手动提供的accessToken
    }

    /**
     * 停止WebSocket连接请求DTO
     */
    @Data
    public static class StopWebSocketReqDTO {
        private Long xianyuAccountId;  // 账号ID
    }

    /**
     * 获取WebSocket状态请求DTO
     */
    @Data
    public static class GetWebSocketStatusReqDTO {
        private Long xianyuAccountId;  // 账号ID
    }

    /**
     * 清除验证等待状态请求DTO
     */
    @Data
    public static class ClearCaptchaWaitReqDTO {
        private Long xianyuAccountId;  // 账号ID
    }

    @Data
    public static class CaptchaSessionReqDTO {
        private Long xianyuAccountId;
        private String sessionId;
    }

    @Data
    public static class CaptchaMouseReqDTO extends CaptchaSessionReqDTO {
        private String eventType;
        private double x;
        private double y;
    }
    
    /**
     * 手动刷新Token请求DTO
     */
    @Data
    public static class RefreshTokenReqDTO {
        private Long xianyuAccountId;  // 账号ID
    }
    
    /**
     * 手动更新Token请求DTO
     */
    @Data
    public static class UpdateTokenReqDTO {
        private Long xianyuAccountId;    // 账号ID
        private String websocketToken;   // WebSocket Token
    }
    
    /**
     * 手动刷新Token响应DTO
     */
    @Data
    public static class RefreshTokenRespDTO {
        private Boolean mh5tkRefreshed;   // _m_h5_tk是否刷新成功
        private Boolean wsTokenRefreshed; // websocket_token是否刷新成功
        private String message;           // 提示信息
    }

    /**
     * WebSocket状态响应DTO
     */
    @Data
    public static class WebSocketStatusRespDTO {
        private Long xianyuAccountId;  // 账号ID
        private Boolean connected;     // 是否已连接
        private String status;         // 连接状态描述
        private Integer cookieStatus;  // Cookie状态 1:有效 2:过期 3:失效
        private Boolean cookieConfigured; // 是否已配置Cookie
        private Integer cookieLength;      // Cookie长度
        private Boolean h5TokenConfigured; // 是否已配置H5 Token
        private Integer h5TokenLength;      // H5 Token长度
        private Boolean websocketTokenConfigured; // 是否已配置WebSocket Token
        private Integer websocketTokenLength;      // WebSocket Token长度
        private Long tokenExpireTime;  // Token过期时间戳（毫秒）
        private Boolean reconnecting;  // 是否有排队中的自动重连任务
        private Integer reconnectAttempt; // 当前自动重连次数
        private Long nextReconnectAt; // 下次自动重连时间戳（毫秒）
        private Boolean captchaPending; // 是否等待人工滑块验证
        private Boolean autoDeliveryOn; // 是否有商品开启了自动发货
        private Boolean autoReplyOn;     // 是否有商品开启了自动回复
        private Long lastWebSocketMessageAt; // 最近收到WebSocket同步消息的时间
        private Long webSocketMessageCount; // 本次运行累计收到的同步消息数
        private Long lastCustomerMessageAt; // 最近识别到客户消息的时间
        private String lastAutoReplyStatus; // 最近自动回复状态码
        private String lastAutoReplyReason; // 最近自动回复原因码
        private Long lastAutoReplyUpdatedAt; // 最近自动回复状态更新时间
        private Long lastSuccessfulReplyAt; // 最近成功自动回复时间
    }

    /**
     * 在与后台Cookie相同的可见浏览器会话中打开平台验证页。
     */
    @PostMapping("/openCaptchaBrowser")
    @RequirePermission("account:write")
    public ResultObject<ManualCaptchaBrowserService.LaunchResult> openCaptchaBrowser(@Valid @RequestBody ClearCaptchaWaitReqDTO reqDTO) {
        if (reqDTO.getXianyuAccountId() == null) {
            return ResultObject.failed("账号ID不能为空");
        }
        ManualCaptchaBrowserService.LaunchResult result =
                manualCaptchaBrowserService.openVerificationWindow(reqDTO.getXianyuAccountId());
        return result.accepted()
                ? ResultObject.success(result)
                : ResultObject.failed(result.message());
    }

    @PostMapping("/captchaSession")
    @RequirePermission("account:write")
    public ResultObject<ManualCaptchaBrowserService.SessionSnapshot> captchaSession(
            @Valid @RequestBody CaptchaSessionReqDTO reqDTO) {
        if (reqDTO.getXianyuAccountId() == null || reqDTO.getSessionId() == null) {
            return ResultObject.failed("验证会话参数不完整");
        }
        return ResultObject.success(manualCaptchaBrowserService.getSession(
                reqDTO.getXianyuAccountId(), reqDTO.getSessionId()));
    }

    @PostMapping("/captchaMouse")
    @RequirePermission("account:write")
    public ResultObject<Boolean> captchaMouse(@Valid @RequestBody CaptchaMouseReqDTO reqDTO) {
        if (reqDTO.getXianyuAccountId() == null || reqDTO.getSessionId() == null
                || reqDTO.getEventType() == null) {
            return ResultObject.failed("鼠标事件参数不完整");
        }
        boolean handled = manualCaptchaBrowserService.handleMouseEvent(
                reqDTO.getXianyuAccountId(), reqDTO.getSessionId(), reqDTO.getEventType(),
                reqDTO.getX(), reqDTO.getY());
        return handled ? ResultObject.success(true) : ResultObject.failed("验证会话已失效");
    }

    @PostMapping("/captchaSession/close")
    @RequirePermission("account:write")
    public ResultObject<Void> closeCaptchaSession(@Valid @RequestBody CaptchaSessionReqDTO reqDTO) {
        if (reqDTO.getXianyuAccountId() == null || reqDTO.getSessionId() == null) {
            return ResultObject.failed("验证会话参数不完整");
        }
        manualCaptchaBrowserService.closeSession(reqDTO.getXianyuAccountId(), reqDTO.getSessionId());
        return ResultObject.success(null);
    }
    
    /**
     * 滑块验证信息响应DTO
     */
    @Data
    public static class CaptchaInfoDTO {
        private Boolean needCaptcha;  // 是否需要验证
        private String captchaUrl;    // 验证链接
        private String message;       // 提示信息
    }
    
    /**
     * 发送消息请求DTO
     */
    @Data
    public static class SendMessageReqDTO {
        private Long xianyuAccountId;  // 账号ID
        private String cid;            // 会话ID（不带@goofish后缀）
        private String toId;           // 接收方用户ID（不带@goofish后缀）
        private String text;           // 消息文本内容
        private String xyGoodsId;      // 闲鱼商品ID
    }
    
    /**
     * 发送图片消息请求DTO
     */
    @Data
    public static class SendImageMessageReqDTO {
        private Long xianyuAccountId;  // 账号ID
        private String cid;            // 会话ID（不带@goofish后缀）
        private String toId;           // 接收方用户ID（不带@goofish后缀）
        private String imageUrl;       // 图片URL
        private Integer width;         // 图片宽度（可选，默认800）
        private Integer height;        // 图片高度（可选，默认600）
        private String xyGoodsId;      // 闲鱼商品ID（可选）
    }
    
    /**
     * 刷新Cookie请求DTO
     */
    @Data
    public static class RefreshCookieReqDTO {
        private Long xianyuAccountId;  // 账号ID
    }
    
    /**
     * 检查登录状态请求DTO
     */
    @Data
    public static class CheckLoginReqDTO {
        private Long xianyuAccountId;  // 账号ID
    }
}

