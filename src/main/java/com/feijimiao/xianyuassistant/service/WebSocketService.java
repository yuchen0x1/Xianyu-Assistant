package com.feijimiao.xianyuassistant.service;

/**
 * WebSocket服务接口
 */
public interface WebSocketService {
    
    /**
     * 启动WebSocket连接
     *
     * @param accountId 账号ID
     * @return 是否成功
     */
    boolean startWebSocket(Long accountId);

    /**
     * 确保账号进入连接或自动重连流程。
     * 该方法不会在调用线程执行网络握手，适合启动恢复和周期巡检调用。
     *
     * @param accountId 账号ID
     */
    void ensureConnected(Long accountId);

    /**
     * 设置账号是否允许后台自动保持WebSocket连接。停用时会在同一生命周期锁内关闭现有连接。
     *
     * @param accountId 账号ID
     * @param enabled true=允许自动连接，false=用户明确停用
     * @return 是否成功
     */
    boolean setAutoConnectEnabled(Long accountId, boolean enabled);

    /**
     * 启用后台保活并强制当前连接重新进入连接流程。
     * 旧连接无法立即关闭时，会保留监控并在后台继续重试。
     *
     * @param accountId 账号ID
     * @return 是否已接受恢复请求
     */
    boolean resumeAutoConnection(Long accountId);

    /**
     * 新登录凭据写入后延迟恢复连接，给平台会话同步留出时间并合并重复请求。
     */
    default boolean resumeAutoConnectionAfterCredentialUpdate(Long accountId) {
        return resumeAutoConnection(accountId);
    }

    /**
     * 用户确认完成滑块后立即探测平台状态并恢复连接。
     * 返回true表示已经取得新Token并完成WebSocket注册确认。
     */
    default boolean recoverAfterManualCaptcha(Long accountId) {
        return resumeAutoConnection(accountId);
    }
    
    /**
     * 使用手动提供的accessToken启动WebSocket连接
     *
     * @param accountId 账号ID
     * @param accessToken 手动提供的accessToken
     * @return 是否成功
     */
    boolean startWebSocketWithToken(Long accountId, String accessToken);
    
    /**
     * 停止WebSocket连接
     *
     * @param accountId 账号ID
     * @return 是否成功
     */
    boolean stopWebSocket(Long accountId);

    /**
     * 永久停止账号连接。该调用会等待同账号正在进行的启动或发送结束，取消延迟任务，
     * 并确认客户端关闭后再返回，用于删除账号前阻止后续外联。
     *
     * @param accountId 账号ID
     * @return 是否已确认停止所有连接和待执行任务
     */
    boolean stopWebSocketForAccountDeletion(Long accountId);
    
    /**
     * 检查WebSocket连接状态
     *
     * @param accountId 账号ID
     * @return 是否已连接
     */
    boolean isConnected(Long accountId);

    /** 是否存在等待执行的自动重连任务。 */
    default boolean isReconnecting(Long accountId) { return false; }

    /** 当前账号自动重连次数。 */
    default int getReconnectAttempt(Long accountId) { return 0; }

    /** 下次自动重连时间（毫秒时间戳），没有排队任务时返回null。 */
    default Long getNextReconnectAt(Long accountId) { return null; }
    
    /**
     * 停止所有WebSocket连接
     */
    void stopAllWebSockets();
    
    /**
     * 发送消息
     *
     * @param accountId 账号ID
     * @param cid 会话ID（不带@goofish后缀）
     * @param toId 接收方用户ID（不带@goofish后缀）
     * @param text 消息文本内容
     * @return 是否成功
     */
    boolean sendMessage(Long accountId, String cid, String toId, String text);
    
    /**
     * 发送消息并等待服务端响应结果
     *
     * @param accountId 账号ID
     * @param cid 会话ID
     * @param toId 接收方用户ID
     * @param text 消息文本内容
     * @return true=服务端返回200，false=失败
     */
    boolean sendMessageWithResult(Long accountId, String cid, String toId, String text);

    /**
     * 发送关键消息并区分服务端拒绝与响应超时。
     */
    MessageSendResult sendMessageWithOutcome(Long accountId, String cid, String toId, String text);
    
    /**
     * 发送图片消息
     *
     * @param accountId 账号ID
     * @param cid 会话ID（不带@goofish后缀）
     * @param toId 接收方用户ID（不带@goofish后缀）
     * @param imageUrl 图片URL
     * @param width 图片宽度
     * @param height 图片高度
     * @return 是否成功
     */
    boolean sendImageMessage(Long accountId, String cid, String toId, String imageUrl, int width, int height);

    /**
     * 发送图片消息并等待服务端响应确认
     */
    boolean sendImageMessageWithResult(Long accountId, String cid, String toId, String imageUrl, int width, int height);

    /**
     * 发送关键图片并区分服务端拒绝与响应超时。
     */
    MessageSendResult sendImageMessageWithOutcome(
            Long accountId, String cid, String toId, String imageUrl, int width, int height);
}
