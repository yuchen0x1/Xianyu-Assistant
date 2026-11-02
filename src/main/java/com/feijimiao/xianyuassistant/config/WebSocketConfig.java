package com.feijimiao.xianyuassistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * WebSocket配置类
 * 参考Python代码的配置参数
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "websocket")
public class WebSocketConfig {
    
    /**
     * 心跳间隔（秒）
     * 参考Python: HEARTBEAT_INTERVAL = 15
     */
    private int heartbeatInterval = 15;
    
    /**
     * 心跳超时（秒）
     * 参考Python: HEARTBEAT_TIMEOUT = 5
     */
    private int heartbeatTimeout = 5;
    
    /**
     * Token刷新间隔（秒）
     * 参考Python: TOKEN_REFRESH_INTERVAL = 3600 (1小时)
     */
    private int tokenRefreshInterval = 3600;
    
    /**
     * Token重试间隔（秒）
     * 参考Python: TOKEN_RETRY_INTERVAL = 300 (5分钟)
     */
    private int tokenRetryInterval = 300;

    /**
     * 凭证刷新最小间隔（分钟）
     * 用于Cookie保活和_m_h5_tk兜底刷新
     */
    private int credentialRefreshMinMinutes = 15;

    /**
     * 凭证刷新最大间隔（分钟）
     * 用于Cookie保活和_m_h5_tk兜底刷新
     */
    private int credentialRefreshMaxMinutes = 20;

    /**
     * 心跳调度线程数。
     * 每个账号每秒执行一次心跳发送/超时检测，账号多时固定2线程会出现调度排队，
     * 导致心跳发送延迟甚至误判掉线。默认4，可按账号数量调整。
     */
    private int heartbeatPoolSize = 4;

    /**
     * Token到期巡检线程数。
     * 每个账号每分钟巡检一次，连接多时建议 >= 2。
     */
    private int tokenRefreshPoolSize = 2;

    /**
     * 自动重连执行线程数。
     * 多个账号同时掉线（如网络抖动/服务器重启）时并行重连，避免串行排队拖长恢复时间。
     */
    private int reconnectPoolSize = 4;

    /**
     * Cookie保活巡检并发线程数。
     * 多账号并行执行 hasLogin 保活与 _m_h5_tk 刷新；默认4，账号很多时可调大。
     * 浏览器兜底刷新始终串行执行（Playwright 单实例，避免并发压力）。
     */
    private int keepAlivePoolSize = 4;
    
    /**
     * 消息过期时间（毫秒）
     * 参考Python: MESSAGE_EXPIRE_TIME = 300000 (5分钟)
     */
    private long messageExpireTime = 300000L;
    
    /**
     * 人工接管超时（秒）
     * 参考Python: MANUAL_MODE_TIMEOUT = 3600 (1小时)
     */
    private int manualModeTimeout = 3600;
    
    /**
     * 人工接管切换关键词
     * 参考Python: TOGGLE_KEYWORDS = "。"
     */
    private String toggleKeywords = "。";
    
    /**
     * 模拟人工输入延迟
     * 参考Python: SIMULATE_HUMAN_TYPING = False
     */
    private boolean simulateHumanTyping = false;
    
    /**
     * 连接重连延迟（秒）
     * 参考Python: 5秒
     */
    private int reconnectDelay = 5;
    
    /**
     * 最大重连次数。
     * 说明：当前实现参考Python采用"无限重连 + 指数退避（5s~60s）"策略，
     * 连续失败5次会触发邮件通知但不会放弃连接；该字段保留用于兼容与文档化，
     * 如需限制最大重连次数可在此接入。
     */
    private int maxReconnectAttempts = 10;
    
    /**
     * 消息发送重试次数
     */
    private int messageRetryAttempts = 3;
    
    /**
     * 消息发送重试延迟（毫秒）
     */
    private long messageRetryDelay = 1000L;
    
    /**
     * 解密消息的脱敏诊断开关，仅记录长度，不记录正文。
     */
    private boolean printRawMessage = false;
}
