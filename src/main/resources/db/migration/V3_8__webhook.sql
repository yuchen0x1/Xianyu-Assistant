-- ============================================================
-- V3_8：v3.3.0 Webhook 外部集成（P0-6）
-- 对应版本：v3.3.0（应用版本）→ schema 版本 V3_8
-- 幂等：所有 DDL 使用 IF NOT EXISTS
-- ============================================================

CREATE TABLE IF NOT EXISTS webhook_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(100) NOT NULL,                       -- Webhook 名称
    url VARCHAR(500) NOT NULL,                        -- 回调 URL
    events TEXT NOT NULL,                              -- 监听事件列表（JSON数组），如 ["delivery.completed","order.created","connection.lost","alert.triggered"]
    enabled TINYINT DEFAULT 1,                        -- 是否启用
    secret VARCHAR(200),                              -- HMAC 签名密钥（可选）
    retry_count INTEGER DEFAULT 3,                    -- 失败重试次数
    timeout_seconds INTEGER DEFAULT 10,               -- 超时时间
    last_trigger_at DATETIME,                         -- 最近一次触发时间
    last_trigger_status VARCHAR(20),                  -- 最近一次触发结果: SUCCESS / FAILED
    last_error TEXT,                                  -- 最近一次错误信息
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_webhook_enabled ON webhook_config(enabled);

CREATE TRIGGER IF NOT EXISTS update_webhook_config_time
AFTER UPDATE ON webhook_config
BEGIN
    UPDATE webhook_config SET updated_time = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;
