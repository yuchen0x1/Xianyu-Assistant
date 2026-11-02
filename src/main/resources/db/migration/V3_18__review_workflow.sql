-- ============================================================
-- V3_18：自动评价与求好评（P1-1）
-- 幂等：所有 DDL 使用 IF NOT EXISTS，权限写入使用 INSERT OR IGNORE
-- ============================================================

CREATE TABLE IF NOT EXISTS review_rule (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id BIGINT NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    delay_seconds INTEGER NOT NULL DEFAULT 86400,
    template TEXT NOT NULL,
    require_manual_confirm TINYINT NOT NULL DEFAULT 1,
    max_retries INTEGER NOT NULL DEFAULT 3,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime')),
    UNIQUE(account_id)
);

CREATE TABLE IF NOT EXISTS review_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id BIGINT NOT NULL,
    order_id VARCHAR(100) NOT NULL,
    buyer_user_id VARCHAR(100),
    rule_id INTEGER,
    review_text TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    scheduled_at DATETIME,
    submitted_at DATETIME,
    retry_count INTEGER NOT NULL DEFAULT 0,
    job_id INTEGER,
    last_error TEXT,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime')),
    UNIQUE(account_id, order_id)
);

CREATE INDEX IF NOT EXISTS idx_review_record_account_status
    ON review_record(account_id, status);
CREATE INDEX IF NOT EXISTS idx_review_record_scheduled_at
    ON review_record(scheduled_at);

INSERT OR IGNORE INTO sys_permission(code, name, module, action, description) VALUES
('review:read', '查看评价', 'review', 'read', '查看评价规则与评价记录'),
('review:write', '管理评价', 'review', 'write', '配置评价规则、确认/取消/补发评价');

INSERT OR IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.name IN ('ADMIN', 'OPERATOR') AND p.code IN ('review:read', 'review:write');

INSERT OR IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.name = 'VIEWER' AND p.code = 'review:read';
