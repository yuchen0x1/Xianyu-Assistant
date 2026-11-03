package com.feijimiao.xianyuassistant.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feijimiao.xianyuassistant.service.MessageSendResult;
import com.feijimiao.xianyuassistant.utils.AccountDisplayNameUtils;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 闲鱼WebSocket客户端
 * 用于监听闲鱼消息
 * 参考Python代码的WebSocketClient和消息处理机制
 */
@Slf4j
public class XianyuWebSocketClient extends WebSocketClient {

    private static final long FORCE_CLOSE_GRACE_MILLIS = 250L;
    private static final AtomicInteger CLOSE_HELPER_SEQUENCE = new AtomicInteger();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String accountId;
    private final AccountDisplayNameUtils displayNameUtils;
    private final AtomicBoolean gracefulCloseRunning = new AtomicBoolean();
    private final AtomicBoolean socketCloseRunning = new AtomicBoolean();
    private final AtomicBoolean stateCloseRunning = new AtomicBoolean();
    private volatile boolean isConnected = false;
    
    // 当前用户ID（从Cookie的unb字段获取）
    private String myUserId = null;
    
    // 消息处理并发控制（参考Python的_handle_message_with_semaphore）
    private final Semaphore messageSemaphore = new Semaphore(100); // 最多100个并发消息处理（参考Python）
    // 消息按到达顺序串行处理（对齐Python单事件循环）：同一账号的消息完成顺序与到达顺序
    // 一致，避免交易状态（如"拍下"→"付款"）被乱序处理。慢业务（AI回复/发货/落库）
    // 由 @Async 事件监听器与任务引擎异步执行，不会阻塞本线程。
    private static final AtomicInteger MESSAGE_THREAD_SEQUENCE = new AtomicInteger();
    private final ExecutorService messageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "xianyu-ws-msg-" + MESSAGE_THREAD_SEQUENCE.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    
    // 消息处理器
    private WebSocketMessageHandler messageHandler;
    
    // 消息统计
    private long messageCount = 0;
    private long lastMessageTime = 0;
    
    // 会话信息
    private String sessionId = null;  // 保存注册后的sid

    // 等待响应的Future（mid -> CompletableFuture<code>）
    private final ConcurrentHashMap<String, CompletableFuture<Integer>> pendingResponses = new ConcurrentHashMap<>();
    
    // 注册成功回调
    private Runnable onRegistrationSuccess;

    // WebSocket握手完成后仍需等待应用层reg-sid确认，才能接收和发送业务消息。
    private final CompletableFuture<Boolean> registrationResult = new CompletableFuture<>();
    
    // Token失效回调
    private Runnable onTokenExpired;
    
    // 心跳响应回调
    private Runnable onHeartbeatResponse;
    
    // 连接关闭回调（参考Python的finally重连逻辑）
    private Runnable onConnectionClosed;
    
    // 是否为主动关闭（防止主动关闭时触发重连）
    private volatile boolean intentionalClose = false;

    // java-websocket 某些网络故障只回调 onError，另一些会随后再回调 onClose；同一故障只通知一次。
    private final AtomicBoolean connectionLossNotified = new AtomicBoolean(false);

    public XianyuWebSocketClient(URI serverUri, Map<String, String> headers, String accountId, AccountDisplayNameUtils displayNameUtils) {
        super(serverUri, headers);
        this.accountId = accountId;
        this.displayNameUtils = displayNameUtils;
    }
    
    /**
     * 获取账号显示名称
     */
    private String getDisplayName() {
        return displayNameUtils != null ? displayNameUtils.getDisplayName(accountId) : "账号" + accountId;
    }
    
    /**
     * 格式化日志前缀
     */
    private String logPrefix() {
        return "【" + getDisplayName() + "】";
    }
    
    /**
     * 设置消息处理器
     */
    public void setMessageHandler(WebSocketMessageHandler handler) {
        this.messageHandler = handler;
    }
    
    /**
     * 设置注册成功回调
     */
    public void setOnRegistrationSuccess(Runnable callback) {
        this.onRegistrationSuccess = callback;
    }
    
    /**
     * 设置当前用户ID
     */
    public void setMyUserId(String userId) {
        this.myUserId = userId;
    }
    
    /**
     * 设置Token失效回调
     */
    public void setOnTokenExpired(Runnable callback) {
        this.onTokenExpired = callback;
    }
    
    /**
     * 设置心跳响应回调
     */
    public void setOnHeartbeatResponse(Runnable callback) {
        this.onHeartbeatResponse = callback;
    }
    
    /**
     * 设置连接关闭回调
     * 参考Python的finally块中的重连逻辑
     */
    public void setOnConnectionClosed(Runnable callback) {
        this.onConnectionClosed = callback;
    }
    
    /**
     * 标记为主动关闭（防止关闭时触发重连回调）
     */
    public void setIntentionalClose(boolean intentional) {
        this.intentionalClose = intentional;
    }

    /**
     * 在限定时间内关闭连接，避免第三方库的无期限 closeBlocking 卡住账号生命周期锁。
     */
    public boolean closeWithin(long timeout, TimeUnit unit) {
        if (unit == null) {
            throw new IllegalArgumentException("TimeUnit不能为空");
        }

        long timeoutNanos = Math.max(0L, unit.toNanos(timeout));
        long deadline = System.nanoTime() + timeoutNanos;
        boolean interrupted = false;
        startCloseAction("graceful", gracefulCloseRunning, this::close);
        try {
            if (awaitFullyClosedUntil(deadline)) {
                return true;
            }
        } catch (InterruptedException e) {
            interrupted = true;
        }

        Socket socket = getSocket();
        // 原始Socket必须先释放；库状态收尾若卡在内部monitor，也不能阻塞生命周期锁。
        startCloseAction("socket", socketCloseRunning, () -> {
            if (socket == null || socket.isClosed()) {
                return;
            }
            try {
                socket.close();
            } catch (IOException e) {
                throw new IllegalStateException("关闭底层Socket失败", e);
            }
        });
        startCloseAction("state", stateCloseRunning, () ->
                closeConnection(CloseFrame.ABNORMAL_CLOSE, "local close timeout"));

        if (!interrupted) {
            try {
                awaitNetworkClosedUntil(System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(FORCE_CLOSE_GRACE_MILLIS), socket);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        // 底层网络已释放即可安全移除旧客户端；库状态线程允许稍后完成收尾。
        return isNetworkReleased(socket);
    }

    private void startCloseAction(
            String actionName, AtomicBoolean actionRunning, Runnable action) {
        if (!actionRunning.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable e) {
                log.warn("{}WebSocket关闭动作失败: action={}, errorType={}",
                        logPrefix(), actionName, e.getClass().getSimpleName());
            } finally {
                actionRunning.set(false);
            }
        }, "xianyu-ws-close-" + CLOSE_HELPER_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        try {
            thread.start();
        } catch (RuntimeException | Error e) {
            actionRunning.set(false);
            throw e;
        }
    }

    private boolean isSocketClosed(Socket socket) {
        return socket == null || socket.isClosed();
    }

    private boolean isNetworkReleased(Socket socket) {
        return socket == null ? isClosed() : socket.isClosed();
    }

    private boolean awaitFullyClosedUntil(long deadline) throws InterruptedException {
        while (!isClosed() || !isSocketClosed(getSocket())) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                return false;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(25)));
        }
        return true;
    }

    private void awaitNetworkClosedUntil(long deadline, Socket socket) throws InterruptedException {
        while (!isNetworkReleased(socket)) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                return;
            }
            TimeUnit.NANOSECONDS.sleep(Math.min(
                    remainingNanos, TimeUnit.MILLISECONDS.toNanos(10)));
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        isConnected = true;
        connectionLossNotified.set(false);
        String displayName = getDisplayName();
        log.info("【{}】==================== WebSocket连接建立成功 ====================", displayName);
        log.info("【{}】服务器握手状态: {}", displayName, handshakedata.getHttpStatus());
        String handshakeMessage = handshakedata.getHttpStatusMessage();
        int handshakeMessageLength = handshakeMessage == null ? 0 : handshakeMessage.length();
        log.info("【{}】服务器握手消息长度: {}", displayName, handshakeMessageLength);
        log.info("【{}】连接已就绪，等待初始化和接收消息...", displayName);
        log.info("【{}】WebSocket连接状态正常，等待服务器消息...", displayName);
        log.info("【{}】准备进入消息接收循环...", displayName);
        log.info("【{}】================================================================", displayName);
    }

    @Override
    public void onMessage(String message) {
        completeRegistrationIfPresent(message);
        // 使用信号量控制并发处理（参考Python的_handle_message_with_semaphore）
        messageExecutor.submit(() -> handleMessageWithSemaphore(message));
    }

    private void completeRegistrationIfPresent(String message) {
        if (registrationResult.isDone() || message == null || message.isEmpty()) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> messageData = objectMapper.readValue(message, Map.class);
            Object code = messageData.get("code");
            if (code == null || !(code.equals(200) || "200".equals(code.toString()))) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) messageData.get("headers");
            if (headers == null || !headers.containsKey("reg-sid")) {
                return;
            }
            Object registrationSessionId = headers.get("reg-sid");
            if (registrationSessionId != null
                    && !registrationSessionId.toString().isBlank()
                    && registrationResult.complete(true)
                    && onRegistrationSuccess != null) {
                try {
                    onRegistrationSuccess.run();
                } catch (Exception e) {
                    log.error("【账号{}】注册成功回调执行失败: errorType={}",
                            accountId, e.getClass().getSimpleName());
                }
            }
        } catch (Exception ignored) {
            // Full parsing and error logging happen in the normal async message path.
        }
    }
    
    /**
     * 带信号量的消息处理包装器
     * 参考Python的_handle_message_with_semaphore方法
     */
    private void handleMessageWithSemaphore(String message) {
        try {
            // 获取信号量许可
            messageSemaphore.acquire();
            
            try {
                // 实际处理消息
                handleMessage(message);
            } catch (Exception e) {
                // 确保即使处理失败也记录错误
                log.error("【账号{}】消息处理异常: errorType={}",
                        accountId, e.getClass().getSimpleName());
            } finally {
                // 释放信号量许可
                messageSemaphore.release();
            }
            
        } catch (InterruptedException e) {
            log.error("【账号{}】消息处理被中断: errorType={}",
                    accountId, e.getClass().getSimpleName());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("【账号{}】信号量处理异常: errorType={}",
                    accountId, e.getClass().getSimpleName());
        }
    }
    
    /**
     * 消息处理核心逻辑
     * 参考Python的handle_message方法
     */
    private void handleMessage(String message) {
        try {
            messageCount++;
            lastMessageTime = System.currentTimeMillis();
            
            if (message == null || message.isEmpty()) {
                log.warn("【账号{}】收到空消息", accountId);
                return;
            }

            // 尝试解析JSON
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> messageData = objectMapper.readValue(message, Map.class);
                
                // 识别消息类型（仅用于调试）
                Object lwpType = messageData.get("lwp");
                Object codeType = messageData.get("code");
                
                // 只记录重要的消息类型
                if (lwpType != null && !"/!".equals(lwpType.toString())) {
                    log.debug("【账号{}】收到消息: lwp={}", accountId, lwpType);
                }
                
                // 检查消息类型和解密（参考Python的handle_message）
                Object lwp = messageData.get("lwp");
                
                // 处理同步包消息 /s/para 和 /s/sync（用户消息）
                if (("/s/para".equals(lwp) || "/s/sync".equals(lwp)) && messageData.containsKey("body")) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> body = (Map<String, Object>) messageData.get("body");
                        
                        if (body != null && body.containsKey("syncPushPackage")) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> syncPushPackage = (Map<String, Object>) body.get("syncPushPackage");
                            
                            if (syncPushPackage != null && syncPushPackage.containsKey("data")) {
                                @SuppressWarnings("unchecked")
                                java.util.List<Object> dataList = (java.util.List<Object>) syncPushPackage.get("data");
                                
                                if (dataList != null && !dataList.isEmpty()) {
                                    // 处理所有 data 项（可能有多条消息）
                                    for (int i = 0; i < dataList.size(); i++) {
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> syncData = (Map<String, Object>) dataList.get(i);
                                        
                                        if (syncData != null && syncData.containsKey("data")) {
                                            String encryptedData = syncData.get("data").toString();
                                            
                                            // 解密数据
                                            String decryptedData = com.feijimiao.xianyuassistant.utils.MessageDecryptUtils.decrypt(encryptedData);
                                            
                                            if (decryptedData != null) {
                                                // 将解密后的数据放回
                                                syncData.put("decryptedData", decryptedData);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("【账号{}】解密同步包消息失败: errorType={}",
                                accountId, e.getClass().getSimpleName());
                    }
                }
                
                // 通用body解密（兼容其他消息类型）
                if (messageData.containsKey("body")) {
                    Object body = messageData.get("body");
                    if (body instanceof String) {
                        String bodyStr = (String) body;
                        String decryptedBody = com.feijimiao.xianyuassistant.utils.MessageDecryptUtils.tryDecrypt(bodyStr);
                        if (decryptedBody != null && !decryptedBody.equals(bodyStr)) {
                            messageData.put("decryptedBody", decryptedBody);
                        }
                    }
                }

                // 发送ACK确认消息（参考Python的handle_message方法）
                sendAckMessage(messageData);
                
                // 检查是否是心跳响应（参考Python的handle_heartbeat_response）
                // Python中心跳响应的判断是 code == 200
                Object code = messageData.get("code");

                // Complete acknowledgement waiters before handling special responses.
                // In particular, the 401 branch returns early below.
                completePendingResponseFromMessage(messageData, code);
                
                // 检查是否是401错误（Token失效）
                if (code != null && (code.equals(401) || "401".equals(code.toString()))) {
                    log.error("【账号{}】❌ Token失效(401)，需要重新获取Token并重连", accountId);
                    
                    // 触发Token失效回调
                    if (onTokenExpired != null) {
                        try {
                            log.info("【账号{}】触发Token失效回调，准备重新获取Token...", accountId);
                            onTokenExpired.run();
                        } catch (Exception e) {
                            log.error("【账号{}】Token失效回调执行失败: errorType={}",
                                    accountId, e.getClass().getSimpleName());
                        }
                    } else {
                        log.warn("【账号{}】未设置Token失效回调，无法自动重连", accountId);
                    }
                    return; // 不再继续处理
                }
                
                // 检查是否是心跳响应（参考Python的handle_heartbeat_response）
                // 心跳响应的特征：code=200 且 headers中有mid
                if (code != null && (code.equals(200) || "200".equals(code.toString()))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> headers = (Map<String, Object>) messageData.get("headers");
                    if (headers != null && headers.containsKey("mid")) {
                        // 这是心跳响应
                        handleHeartbeatResponse();
                    }
                    
                    // 检查是否是注册响应，保存sid
                    if (headers != null && headers.containsKey("sid")) {
                        sessionId = headers.get("sid").toString();
                        log.info("【账号{}】已保存会话ID: present={}", accountId, !sessionId.isBlank());
                    }
                    if (headers != null && headers.containsKey("reg-sid")) {
                        Object registrationSessionId = headers.get("reg-sid");
                        boolean registrationSessionIdPresent = registrationSessionId != null
                                && !registrationSessionId.toString().isBlank();
                        log.info("【账号{}】✅ 注册成功，regSidPresent={}",
                                accountId, registrationSessionIdPresent);

                        // 只接受非空reg-sid，且同一连接的注册成功回调只执行一次。
                        if (registrationSessionIdPresent
                                && registrationResult.complete(true)
                                && onRegistrationSuccess != null) {
                            try {
                                log.info("【账号{}】触发注册成功回调，准备保存Token...", accountId);
                                onRegistrationSuccess.run();
                            } catch (Exception e) {
                                log.error("【账号{}】注册成功回调执行失败: errorType={}",
                                        accountId, e.getClass().getSimpleName());
                            }
                        }
                    }
                }
                
                // 调用消息处理器
                if (messageHandler != null) {
                    messageHandler.handleMessage(accountId, messageData);
                }

            } catch (Exception e) {
                log.warn("【账号{}】消息解析失败: errorType={}",
                        accountId, e.getClass().getSimpleName());
            }

        } catch (Exception e) {
            log.error("【账号{}】消息处理失败: errorType={}",
                    accountId, e.getClass().getSimpleName());
            if (messageHandler != null) {
                messageHandler.handleError(accountId, e);
            }
        }
    }
    
    /**
     * 判断消息方向（发送还是接收）
     * 
     * @param decryptedData 解密后的JSON数据
     * @param accountId 当前账号ID
     * @return "【发】" 或 "【收】"
     */
    private String determineMessageDirection(String decryptedData, String accountId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(decryptedData, Map.class);
            
            // 检查是否是已读回执（字段2=2）
            Object type = data.get("2");
            if (type != null && "2".equals(type.toString())) {
                return "【读】"; // 已读回执
            }
            
            // 检查是否是聊天消息（有字段1且是Map）
            Object field1 = data.get("1");
            if (field1 instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> messageInfo = (Map<String, Object>) field1;
                
                // 获取发送者（字段1.1.1）
                Object senderObj = messageInfo.get("1");
                if (senderObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> senderInfo = (Map<String, Object>) senderObj;
                    String sender = (String) senderInfo.get("1");
                    
                    // 获取接收者（字段1.2）
                    String receiver = (String) messageInfo.get("2");
                    
                    // 判断方向：如果接收者包含当前账号ID，说明是收到的消息
                    if (receiver != null && receiver.contains(accountId)) {
                        return "【收】";
                    } else if (sender != null && sender.contains(accountId)) {
                        return "【发】";
                    }
                }
            }
            
            return "【?】"; // 未知类型
            
        } catch (Exception e) {
            return "【?】";
        }
    }
    
    /**
     * 发送ACK确认消息
     * 参考Python的handle_message方法中的ACK发送逻辑
     * 
     * @param messageData 收到的消息数据
     */
    private void sendAckMessage(Map<String, Object> messageData) {
        try {
            // 检查消息是否包含headers
            if (!messageData.containsKey("headers")) {
                return;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) messageData.get("headers");
            
            // 构建ACK消息
            Map<String, Object> ack = new HashMap<>();
            ack.put("code", 200);
            
            Map<String, Object> ackHeaders = new HashMap<>();
            // 复制mid
            if (headers.containsKey("mid")) {
                ackHeaders.put("mid", headers.get("mid"));
            } else {
                // 生成mid
                String mid = com.feijimiao.xianyuassistant.utils.XianyuDeviceUtils.generateMid();
                ackHeaders.put("mid", mid);
            }
            
            // 复制sid
            if (headers.containsKey("sid")) {
                ackHeaders.put("sid", headers.get("sid"));
            } else {
                ackHeaders.put("sid", "");
            }
            
            // 复制其他可选字段
            if (headers.containsKey("app-key")) {
                ackHeaders.put("app-key", headers.get("app-key"));
            }
            if (headers.containsKey("ua")) {
                ackHeaders.put("ua", headers.get("ua"));
            }
            if (headers.containsKey("dt")) {
                ackHeaders.put("dt", headers.get("dt"));
            }
            
            ack.put("headers", ackHeaders);
            
            // 发送ACK
            String ackJson = objectMapper.writeValueAsString(ack);
            send(ackJson);
            
        } catch (Exception e) {
            log.error("【账号{}】发送ACK失败: errorType={}",
                    accountId, e.getClass().getSimpleName());
            // ACK发送失败不影响消息处理，只记录日志
        }
    }
    
    /**
     * 处理心跳响应
     * 参考Python的handle_heartbeat_response方法
     */
    private void handleHeartbeatResponse() {
        log.debug("【账号{}】收到心跳响应", accountId);
        
        // 触发心跳响应回调（更新心跳响应时间）
        if (onHeartbeatResponse != null) {
            try {
                onHeartbeatResponse.run();
            } catch (Exception e) {
                log.error("【账号{}】心跳响应回调执行失败: errorType={}",
                        accountId, e.getClass().getSimpleName());
            }
        }
        
        // 调用消息处理器
        if (messageHandler != null) {
            messageHandler.handleHeartbeat(accountId);
        }
    }



    @Override
    public void onClose(int code, String reason, boolean remote) {
        isConnected = false;
        registrationResult.complete(false);
        String closeType = remote ? "服务器" : "客户端";
        int closeReasonLength = reason == null ? 0 : reason.length();
        log.info("{}WebSocket连接关闭 - 关闭方: {}, 代码: {}, reasonLength={}",
                logPrefix(), closeType, code, closeReasonLength);
        
        shutdownMessageExecutor();
        
        // 触发连接关闭回调（参考Python的finally块中重连逻辑）
        // 但如果是主动关闭（如Token刷新重连），则不触发回调，由调用方自行处理重连
        notifyConnectionLostOnce();
        if (intentionalClose) {
            log.info("{}主动关闭连接，不触发自动重连回调", logPrefix());
            // 重置标志，以便下次连接可以正常触发
            intentionalClose = false;
        }
    }

    @Override
    public void onError(Exception ex) {
        isConnected = false;
        registrationResult.complete(false);
        shutdownMessageExecutor();
        log.error("{}WebSocket发生错误: errorType={}",
                logPrefix(), ex.getClass().getSimpleName());
        if (messageHandler != null) {
            messageHandler.handleError(accountId, ex);
        }
        // 网络异常可能没有后续 onClose，必须直接进入统一重连回调。
        notifyConnectionLostOnce();
    }

    private void shutdownMessageExecutor() {
        if (!messageExecutor.isShutdown()) {
            messageExecutor.shutdownNow();
            log.debug("{}消息处理线程池已关闭", logPrefix());
        }
    }

    private void notifyConnectionLostOnce() {
        if (intentionalClose || onConnectionClosed == null
                || !connectionLossNotified.compareAndSet(false, true)) {
            return;
        }
        try {
            log.info("{}触发连接丢失回调，准备自动重连...", logPrefix());
            onConnectionClosed.run();
        } catch (Exception e) {
            log.error("{}连接丢失回调执行失败: errorType={}",
                    logPrefix(), e.getClass().getSimpleName());
        }
    }

    /**
     * 发送心跳消息
     * 参考Python的send_heartbeat方法
     */
    public void sendHeartbeat() {
        if (isConnected) {
            try {
                // 生成心跳消息（参考Python格式）
                String mid = com.feijimiao.xianyuassistant.utils.XianyuDeviceUtils.generateMid();
                String heartbeat = String.format("{\"lwp\":\"/!\",\"headers\":{\"mid\":\"%s\"}}", mid);
                send(heartbeat);
            } catch (Exception e) {
                log.error("【账号{}】发送心跳失败: errorType={}",
                        accountId, e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 检查连接状态
     * 使用底层的连接状态，而不是标志位
     */
    public boolean isConnected() {
        // 使用底层WebSocket的连接状态
        return !isClosed() && isOpen();
    }

    /**
     * 等待应用层注册确认。仅WebSocket握手成功不代表消息通道已经可用。
     */
    public boolean awaitRegistration(long timeout, TimeUnit unit) {
        try {
            return Boolean.TRUE.equals(registrationResult.get(timeout, unit));
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }
    
    /**
     * 发送消息
     * 参考Python的send_msg方法
     * 
     * @return true=帧已写入连接（不等待服务端确认）；false=未连接或本地发送失败
     */
    public boolean sendMessage(String cid, String toId, String text) {
        if (!isConnected) {
            log.error("【账号{}】WebSocket未连接，无法发送消息", accountId);
            return false;
        }
        try {
            String cleanCid = cid.replace("@goofish", "");
            String cleanToId = toId.replace("@goofish", "");
            int textLength = text == null ? 0 : text.length();
            log.info("【账号{}】准备发送消息: cleanCid={}, cleanToId={}, textLength={}",
                    accountId, cleanCid, cleanToId, textLength);

            Map<String, Object> textContent = new HashMap<>();
            textContent.put("contentType", 1);
            Map<String, String> textData = new HashMap<>();
            textData.put("text", text);
            textContent.put("text", textData);

            String textJson = objectMapper.writeValueAsString(textContent);
            String textBase64 = java.util.Base64.getEncoder().encodeToString(textJson.getBytes("UTF-8"));

            Map<String, Object> messageBody = new HashMap<>();
            messageBody.put("uuid", generateUuid());
            messageBody.put("cid", cleanCid + "@goofish");
            messageBody.put("conversationType", 1);

            Map<String, Object> content = new HashMap<>();
            content.put("contentType", 101);
            Map<String, Object> custom = new HashMap<>();
            custom.put("type", 1);
            custom.put("data", textBase64);
            content.put("custom", custom);
            messageBody.put("content", content);
            messageBody.put("redPointPolicy", 0);

            Map<String, String> extension = new HashMap<>();
            extension.put("extJson", "{}");
            messageBody.put("extension", extension);

            Map<String, String> ctx = new HashMap<>();
            ctx.put("appVersion", "1.0");
            ctx.put("platform", "web");
            messageBody.put("ctx", ctx);
            messageBody.put("mtags", new HashMap<>());
            messageBody.put("msgReadStatusSetting", 1);

            Map<String, Object> receivers = new HashMap<>();
            java.util.List<String> actualReceivers = new java.util.ArrayList<>();
            actualReceivers.add(cleanToId + "@goofish");
            String senderUserId = myUserId != null ? myUserId : accountId;
            actualReceivers.add(senderUserId + "@goofish");
            receivers.put("actualReceivers", actualReceivers);

            Map<String, Object> message = new HashMap<>();
            message.put("lwp", "/r/MessageSend/sendByReceiverScope");

            Map<String, String> headers = new HashMap<>();
            headers.put("mid", generateMid());
            if (sessionId != null) {
                headers.put("sid", sessionId);
            }
            message.put("headers", headers);

            java.util.List<Object> body = new java.util.ArrayList<>();
            body.add(messageBody);
            body.add(receivers);
            message.put("body", body);

            String messageJson = objectMapper.writeValueAsString(message);
            send(messageJson);
            log.info("【账号{}】✅ 消息已发送到WebSocket", accountId);
            return true;
        } catch (Exception e) {
            log.error("【账号{}】❌ 发送消息失败: cid={}, toId={}, errorType={}",
                    accountId, cid, toId, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 完成等待中的响应Future
     */
    public void completePendingResponse(String mid, int code) {
        CompletableFuture<Integer> future = pendingResponses.remove(mid);
        if (future != null && !future.isDone()) {
            future.complete(code);
            log.debug("【账号{}】完成pendingResponse: mid={}, code={}", accountId, mid, code);
        }
    }

    void completePendingResponseFromMessage(Map<String, Object> messageData, Object code) {
        if (code == null) {
            return;
        }
        Object headersObject = messageData.get("headers");
        if (!(headersObject instanceof Map<?, ?> headers)) {
            return;
        }
        Object mid = headers.get("mid");
        if (mid == null) {
            return;
        }
        try {
            completePendingResponse(mid.toString(), Integer.parseInt(code.toString()));
        } catch (NumberFormatException e) {
            log.debug("【账号{}】忽略无法识别的消息响应码", accountId);
        }
    }

    /**
     * 发送消息并等待响应结果
     *
     * @param cid 会话ID
     * @param toId 接收方用户ID
     * @param text 消息文本内容
     * @return true=服务端返回200，false=失败或超时
     */
    public boolean sendMessageWithResult(String cid, String toId, String text) {
        return sendMessageWithOutcome(cid, toId, text) == MessageSendResult.SUCCESS;
    }

    /**
     * 发送消息并等待服务端响应，保留超时这一不确定状态。
     */
    public MessageSendResult sendMessageWithOutcome(String cid, String toId, String text) {
        if (!isConnected) {
            log.error("【账号{}】WebSocket未连接，无法发送消息", accountId);
            return MessageSendResult.FAILED;
        }

        boolean sendAttempted = false;

        try {
            String cleanCid = cid.replace("@goofish", "");
            String cleanToId = toId.replace("@goofish", "");
            int textLength = text == null ? 0 : text.length();

            log.info("【账号{}】准备发送消息: cleanCid={}, cleanToId={}, textLength={}",
                    accountId, cleanCid, cleanToId, textLength);
            
            Map<String, Object> textContent = new HashMap<>();
            textContent.put("contentType", 1);
            Map<String, String> textData = new HashMap<>();
            textData.put("text", text);
            textContent.put("text", textData);
            
            String textJson = objectMapper.writeValueAsString(textContent);
            String textBase64 = java.util.Base64.getEncoder().encodeToString(textJson.getBytes("UTF-8"));
            
            Map<String, Object> messageBody = new HashMap<>();
            messageBody.put("uuid", generateUuid());
            messageBody.put("cid", cleanCid + "@goofish");
            messageBody.put("conversationType", 1);
            
            Map<String, Object> content = new HashMap<>();
            content.put("contentType", 101);
            Map<String, Object> custom = new HashMap<>();
            custom.put("type", 1);
            custom.put("data", textBase64);
            content.put("custom", custom);
            messageBody.put("content", content);
            
            messageBody.put("redPointPolicy", 0);
            
            Map<String, String> extension = new HashMap<>();
            extension.put("extJson", "{}");
            messageBody.put("extension", extension);
            
            Map<String, String> ctx = new HashMap<>();
            ctx.put("appVersion", "1.0");
            ctx.put("platform", "web");
            messageBody.put("ctx", ctx);
            
            messageBody.put("mtags", new HashMap<>());
            messageBody.put("msgReadStatusSetting", 1);
            
            Map<String, Object> receivers = new HashMap<>();
            java.util.List<String> actualReceivers = new java.util.ArrayList<>();
            actualReceivers.add(cleanToId + "@goofish");
            String senderUserId = myUserId != null ? myUserId : accountId;
            actualReceivers.add(senderUserId + "@goofish");
            receivers.put("actualReceivers", actualReceivers);
            
            log.info("【账号{}】消息接收者数量: {}", accountId, actualReceivers.size());
            
            Map<String, Object> message = new HashMap<>();
            message.put("lwp", "/r/MessageSend/sendByReceiverScope");
            
            String mid = generateMid();
            Map<String, String> headers = new HashMap<>();
            headers.put("mid", mid);
            if (sessionId != null) {
                headers.put("sid", sessionId);
            }
            message.put("headers", headers);
            
            java.util.List<Object> body = new java.util.ArrayList<>();
            body.add(messageBody);
            body.add(receivers);
            message.put("body", body);
            
            CompletableFuture<Integer> future = new CompletableFuture<>();
            pendingResponses.put(mid, future);

            try {
                String messageJson = objectMapper.writeValueAsString(message);
                log.debug("【账号{}】发送消息JSON: payloadLength={}", accountId, messageJson.length());
                sendAttempted = true;
                send(messageJson);
                log.info("【账号{}】消息已发送到WebSocket，等待响应: mid={}", accountId, mid);

                MessageSendResult result = awaitPendingResponse(future, 10, TimeUnit.SECONDS);
                if (result == MessageSendResult.SUCCESS) {
                    log.info("【账号{}】✅ 消息发送成功(服务端返回200): mid={}", accountId, mid);
                } else if (result == MessageSendResult.UNKNOWN) {
                    log.warn("【账号{}】消息发送结果未知(10秒未收到响应): mid={}", accountId, mid);
                } else {
                    log.error("【账号{}】❌ 消息发送失败(服务端拒绝或本地等待异常): mid={}", accountId, mid);
                }
                return result;
            } finally {
                // 序列化或 send 抛异常时也必须清理，否则条目泄漏且 map 只增不减
                pendingResponses.remove(mid);
            }

        } catch (Exception e) {
            // 序列化/组装阶段失败才能证明消息未写出；一旦进入 send()，异常可能发生在
            // 帧写出之后，此时判 FAILED 会让发货链路回滚卡密并造成二次售卖。
            log.error("【账号{}】❌ 发送消息失败: cid={}, toId={}, errorType={}, sendAttempted={}",
                    accountId, cid, toId, e.getClass().getSimpleName(), sendAttempted);
            return sendAttempted ? MessageSendResult.UNKNOWN : MessageSendResult.FAILED;
        }
    }
    
    /**
     * 发送图片消息
     * 
     * @param cid 会话ID
     * @param toId 接收方ID
     * @param imageUrl 图片URL
     * @param width 图片宽度
     * @param height 图片高度
     */
    public void sendImageMessage(String cid, String toId, String imageUrl, int width, int height) {
        if (!isConnected) {
            log.error("{}WebSocket未连接，无法发送图片消息", logPrefix());
            return;
        }
        
        try {
            String cleanCid = cid.replace("@goofish", "");
            String cleanToId = toId.replace("@goofish", "");
            int imageUrlLength = imageUrl == null ? 0 : imageUrl.length();

            log.info("{}准备发送图片消息: cid={}, toId={}, imageUrlLength={}, size={}x{}",
                    logPrefix(), cleanCid, cleanToId, imageUrlLength, width, height);
            
            // 构造图片消息内容
            Map<String, Object> imageContent = new HashMap<>();
            imageContent.put("contentType", 2);
            
            Map<String, Object> pics = new HashMap<>();
            pics.put("height", height);
            pics.put("type", 0);
            pics.put("url", imageUrl);
            pics.put("width", width);
            
            Map<String, Object> imageData = new HashMap<>();
            imageData.put("pics", java.util.Collections.singletonList(pics));
            imageContent.put("image", imageData);
            
            // Base64编码
            String imageJson = objectMapper.writeValueAsString(imageContent);
            String imageBase64 = java.util.Base64.getEncoder().encodeToString(imageJson.getBytes("UTF-8"));
            
            log.debug("{}图片内容JSON: payloadLength={}", logPrefix(), imageJson.length());
            
            // 构造消息体
            Map<String, Object> messageBody = new HashMap<>();
            messageBody.put("uuid", generateUuid());
            messageBody.put("cid", cleanCid + "@goofish");
            messageBody.put("conversationType", 1);
            
            Map<String, Object> content = new HashMap<>();
            content.put("contentType", 101);
            Map<String, Object> custom = new HashMap<>();
            custom.put("type", 1);
            custom.put("data", imageBase64);
            content.put("custom", custom);
            messageBody.put("content", content);
            
            messageBody.put("redPointPolicy", 0);
            
            Map<String, String> extension = new HashMap<>();
            extension.put("extJson", "{}");
            messageBody.put("extension", extension);
            
            Map<String, String> ctx = new HashMap<>();
            ctx.put("appVersion", "1.0");
            ctx.put("platform", "web");
            messageBody.put("ctx", ctx);
            
            messageBody.put("mtags", new HashMap<>());
            messageBody.put("msgReadStatusSetting", 1);
            
            // 接收方
            Map<String, Object> receivers = new HashMap<>();
            java.util.List<String> actualReceivers = new java.util.ArrayList<>();
            actualReceivers.add(cleanToId + "@goofish");
            if (myUserId != null) {
                actualReceivers.add(myUserId + "@goofish");
            }
            receivers.put("actualReceivers", actualReceivers);
            
            // 构造完整消息
            Map<String, Object> message = new HashMap<>();
            message.put("lwp", "/r/MessageSend/sendByReceiverScope");
            
            Map<String, String> headers = new HashMap<>();
            headers.put("mid", generateMid());
            message.put("headers", headers);
            
            java.util.List<Object> body = new java.util.ArrayList<>();
            body.add(messageBody);
            body.add(receivers);
            message.put("body", body);
            
            // 发送
            String messageJson = objectMapper.writeValueAsString(message);
            log.debug("{}发送图片消息JSON: payloadLength={}", logPrefix(), messageJson.length());
            send(messageJson);
            log.info("{}✅ 图片消息已发送: imageUrlLength={}", logPrefix(), imageUrlLength);
            
        } catch (Exception e) {
            log.error("{}❌ 发送图片消息失败: cid={}, toId={}, errorType={}",
                    logPrefix(), cid, toId, e.getClass().getSimpleName());
        }
    }

    /**
     * 发送图片消息并等待服务端响应确认
     */
    public boolean sendImageMessageWithResult(String cid, String toId, String imageUrl, int width, int height) {
        return sendImageMessageWithOutcome(cid, toId, imageUrl, width, height) == MessageSendResult.SUCCESS;
    }

    /**
     * 发送图片并等待服务端响应，保留超时这一不确定状态。
     */
    public MessageSendResult sendImageMessageWithOutcome(
            String cid, String toId, String imageUrl, int width, int height) {
        if (!isConnected) {
            log.error("{}WebSocket未连接，无法发送图片消息", logPrefix());
            return MessageSendResult.FAILED;
        }

        try {
            String cleanCid = cid.replace("@goofish", "");
            String cleanToId = toId.replace("@goofish", "");
            int imageUrlLength = imageUrl == null ? 0 : imageUrl.length();

            log.info("{}准备发送图片消息(等待结果): cid={}, toId={}, imageUrlLength={}, size={}x{}",
                    logPrefix(), cleanCid, cleanToId, imageUrlLength, width, height);

            Map<String, Object> imageContent = new HashMap<>();
            imageContent.put("contentType", 2);

            Map<String, Object> pics = new HashMap<>();
            pics.put("height", height);
            pics.put("type", 0);
            pics.put("url", imageUrl);
            pics.put("width", width);

            Map<String, Object> imageData = new HashMap<>();
            imageData.put("pics", java.util.Collections.singletonList(pics));
            imageContent.put("image", imageData);

            String imageJson = objectMapper.writeValueAsString(imageContent);
            String imageBase64 = java.util.Base64.getEncoder().encodeToString(imageJson.getBytes("UTF-8"));

            Map<String, Object> messageBody = new HashMap<>();
            messageBody.put("uuid", generateUuid());
            messageBody.put("cid", cleanCid + "@goofish");
            messageBody.put("conversationType", 1);

            Map<String, Object> content = new HashMap<>();
            content.put("contentType", 101);
            Map<String, Object> custom = new HashMap<>();
            custom.put("type", 1);
            custom.put("data", imageBase64);
            content.put("custom", custom);
            messageBody.put("content", content);

            messageBody.put("redPointPolicy", 0);

            Map<String, String> extension = new HashMap<>();
            extension.put("extJson", "{}");
            messageBody.put("extension", extension);

            Map<String, String> ctx = new HashMap<>();
            ctx.put("appVersion", "1.0");
            ctx.put("platform", "web");
            messageBody.put("ctx", ctx);

            messageBody.put("mtags", new HashMap<>());
            messageBody.put("msgReadStatusSetting", 1);

            Map<String, Object> receivers = new HashMap<>();
            java.util.List<String> actualReceivers = new java.util.ArrayList<>();
            actualReceivers.add(cleanToId + "@goofish");
            if (myUserId != null) {
                actualReceivers.add(myUserId + "@goofish");
            }
            receivers.put("actualReceivers", actualReceivers);

            Map<String, Object> message = new HashMap<>();
            message.put("lwp", "/r/MessageSend/sendByReceiverScope");

            String mid = generateMid();
            Map<String, String> headers = new HashMap<>();
            headers.put("mid", mid);
            if (sessionId != null) {
                headers.put("sid", sessionId);
            }
            message.put("headers", headers);

            java.util.List<Object> body = new java.util.ArrayList<>();
            body.add(messageBody);
            body.add(receivers);
            message.put("body", body);

            CompletableFuture<Integer> future = new CompletableFuture<>();
            pendingResponses.put(mid, future);

            try {
                String messageJson = objectMapper.writeValueAsString(message);
                send(messageJson);
                log.info("{}图片消息已发送，等待响应: mid={}", logPrefix(), mid);

                MessageSendResult result = awaitPendingResponse(future, 10, TimeUnit.SECONDS);
                if (result == MessageSendResult.SUCCESS) {
                    log.info("{}✅ 图片消息发送成功(服务端返回200): mid={}", logPrefix(), mid);
                } else if (result == MessageSendResult.UNKNOWN) {
                    log.warn("{}图片消息发送结果未知(10秒未收到响应): mid={}", logPrefix(), mid);
                } else {
                    log.error("{}❌ 图片消息发送失败(服务端拒绝或本地等待异常): mid={}", logPrefix(), mid);
                }
                return result;
            } finally {
                // 序列化或 send 抛异常时也必须清理，否则条目泄漏且 map 只增不减
                pendingResponses.remove(mid);
            }

        } catch (Exception e) {
            log.error("{}❌ 发送图片消息失败: cid={}, toId={}, errorType={}",
                    logPrefix(), cid, toId, e.getClass().getSimpleName());
            return MessageSendResult.FAILED;
        }
    }

    static MessageSendResult awaitPendingResponse(
            CompletableFuture<Integer> future, long timeout, TimeUnit unit) {
        try {
            return MessageSendResult.fromResponseCode(future.get(timeout, unit));
        } catch (TimeoutException e) {
            return MessageSendResult.UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 中断只代表本地不再等待，帧可能已经写出，不能据此判定为确定失败。
            return MessageSendResult.UNKNOWN;
        } catch (ExecutionException e) {
            // 发送已发起，帧可能已经写出；本地等待机制失败不能证明消息未送达。
            return MessageSendResult.UNKNOWN;
        }
    }
    
    /**
     * 生成消息ID (mid)
     * 格式: 随机数(0-999) + 时间戳(毫秒) + " 0"
     * 参考Python的generate_mid方法
     */
    private String generateMid() {
        return com.feijimiao.xianyuassistant.utils.XianyuDeviceUtils.generateMid();
    }
    
    /**
     * 生成UUID
     * 格式: -时间戳1
     * 参考Python的generate_uuid方法
     */
    private String generateUuid() {
        return com.feijimiao.xianyuassistant.utils.XianyuDeviceUtils.generateUuid();
    }
}
