-- ============================================================
-- V3_17：告警事件持久化 + 监控中心权限 + Webhook 投递记录(outbox)（P0 告警治理）
-- 幂等：所有 DDL 使用 IF NOT EXISTS，INSERT 使用 OR IGNORE
-- ============================================================

-- 1. 告警事件表
-- alert_key 即告警去重键（如 delivery_failure）。account_id 为空表示全局告警。
-- status: ACTIVE / ACKED / SILENCED / RESOLVED
CREATE TABLE IF NOT EXISTS alert_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    alert_key VARCHAR(200) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message TEXT,
    level VARCHAR(20) DEFAULT 'WARNING',           -- INFO / WARNING / ERROR
    account_id BIGINT,
    status VARCHAR(20) DEFAULT 'ACTIVE',           -- ACTIVE / ACKED / SILENCED / RESOLVED
    first_seen_at DATETIME DEFAULT (datetime('now', 'localtime')),
    last_seen_at DATETIME DEFAULT (datetime('now', 'localtime')),
    occurrences INTEGER DEFAULT 1,
    last_sent_at DATETIME,
    next_send_at DATETIME,
    send_attempts INTEGER DEFAULT 0,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

-- 唯一键：同一告警在去重窗口内只存在一条事件（account_id 为空时全局唯一）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_alert_event_key
    ON alert_event(alert_key);

CREATE INDEX IF NOT EXISTS idx_alert_event_status ON alert_event(status);
CREATE INDEX IF NOT EXISTS idx_alert_event_account ON alert_event(account_id);
CREATE INDEX IF NOT EXISTS idx_alert_event_next_send ON alert_event(next_send_at);

CREATE TRIGGER IF NOT EXISTS update_alert_event_time
AFTER UPDATE ON alert_event
BEGIN
    UPDATE alert_event SET updated_time = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;

-- 2. 监控中心权限码
INSERT OR IGNORE INTO sys_permission(code, name, module, action, description) VALUES
('monitoring:read', '查看监控中心', 'monitoring', 'read', '查看告警列表与监控概览'),
('monitoring:write', '处理告警', 'monitoring', 'write', '确认/静默/恢复告警');

-- 3. 角色授权：ADMIN 全部；OPERATOR 读+写；VIEWER 仅读
INSERT OR IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.name = 'ADMIN' AND p.code IN ('monitoring:read', 'monitoring:write');

INSERT OR IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.name = 'OPERATOR' AND p.code IN ('monitoring:read', 'monitoring:write');

INSERT OR IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.name = 'VIEWER' AND p.code = 'monitoring:read';

-- 4. Webhook 投递记录(outbox) + 可重试投递
CREATE TABLE IF NOT EXISTS webhook_delivery (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    webhook_id INTEGER NOT NULL,                      -- 关联 Webhook 配置
    event VARCHAR(50) NOT NULL,                       -- 事件类型
    payload_json TEXT,                                -- 事件载荷（只含业务最小字段，禁止 Cookie/Token/密钥）
    status VARCHAR(20) DEFAULT 'PENDING',             -- PENDING / SUCCESS / FAILED / UNKNOWN
    http_status INTEGER,                              -- 最近一次 HTTP 状态码
    attempts INTEGER DEFAULT 0,                       -- 已尝试次数
    next_attempt_at DATETIME,                         -- 下次重试时间
    last_error TEXT,                                  -- 最近一次错误信息
    idempotency_key VARCHAR(100) UNIQUE,              -- 幂等键：event:{webhookId}:{业务ID}:{时间戳}
    delivered_at DATETIME,                            -- 成功投递时间
    created_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_webhook_delivery_hook ON webhook_delivery(webhook_id);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_status ON webhook_delivery(status);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_next ON webhook_delivery(next_attempt_at);