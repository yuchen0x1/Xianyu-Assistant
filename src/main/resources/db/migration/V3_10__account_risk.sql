-- ============================================================
-- V3_10：账号级自动化风控（P0-6）
-- 对应版本：v3.3.0（应用版本）→ schema 版本 V3_10
-- 幂等：所有 DDL 使用 IF NOT EXISTS，INSERT 使用 OR IGNORE
-- ============================================================

-- 1. 风险事件记录（风控/限流/人工暂停/恢复的审计轨迹）
CREATE TABLE IF NOT EXISTS account_risk_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    event_type VARCHAR(50) NOT NULL,          -- RISK_CONTROL / RATE_LIMITED / MANUAL_PAUSE / RESUME
    reason VARCHAR(500),
    occurred_at DATETIME NOT NULL DEFAULT (datetime('now', 'localtime')),
    created_time DATETIME DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_risk_event_account_time ON account_risk_event(account_id, occurred_at);

-- 2. 账号自动化手动暂停状态（持久化，重启后仍生效）
CREATE TABLE IF NOT EXISTS account_automation_pause (
    account_id INTEGER PRIMARY KEY,
    paused INTEGER NOT NULL DEFAULT 0,          -- 1:已暂停 0:正常
    pause_reason VARCHAR(500),
    paused_at DATETIME,
    paused_by INTEGER,
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);
