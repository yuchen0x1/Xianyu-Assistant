package com.feijimiao.xianyuassistant.service;

/**
 * WebSocket Token服务接口
 * 用于获取WebSocket连接所需的accessToken
 */
public interface WebSocketTokenService {
    
    /**
     * 获取accessToken
     * 参考Python的refresh_token方法
     * 内部会自动从数据库读取最新的Cookie和deviceId
     *
     * @param accountId 账号ID
     * @return accessToken，失败返回null
     */
    String getAccessToken(Long accountId);
    
    /**
     * 保存Token到数据库
     * 
     * @param accountId 账号ID
     * @param token accessToken
     */
    void saveToken(Long accountId, String token);
    
    /**
     * 清除Token缓存（强制刷新）
     * 
     * @param accountId 账号ID
     */
    void clearToken(Long accountId);
    
    /**
     * 清除验证等待状态
     */
    void clearCaptchaWait(Long accountId);

    /** 连接恢复失败时重新保留人工验证告警。 */
    default void restoreCaptchaWait(Long accountId) { }

    /** 是否正在等待人工完成滑块验证。 */
    default boolean isCaptchaPending(Long accountId) { return false; }

    /** 返回当前账号最近一次由平台下发的验证地址。 */
    default String getPendingCaptchaUrl(Long accountId) { return null; }
    
    /**
     * 刷新WebSocket token
     * 
     * @param accountId 账号ID
     * @return 新的token，失败返回null
     */
    String refreshToken(Long accountId);

    /**
     * 单次探测验证状态，不执行内部重试或hasLogin循环。
     */
    default String probeAccessToken(Long accountId) {
        return refreshToken(accountId);
    }

    /**
     * 用户确认已完成平台滑块后的单次探测。
     * 该探测允许绕过本地短期滑块保持状态，直接向平台确认是否已经放行。
     */
    default String probeAccessTokenAfterManualVerification(Long accountId) {
        return probeAccessToken(accountId);
    }

    /**
     * 账号删除后清理该账号的内存状态（验证码等待、锁表等）。
     * 账号 ID 会被自增计数器复用，残留状态会毒化复用同 ID 的新账号。
     */
    void removeAccountState(Long accountId);
}
