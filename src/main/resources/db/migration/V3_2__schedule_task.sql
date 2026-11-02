-- ============================================================
-- V3_2：v3.1.0 定时任务中心
-- 对应版本：v3.1.0（应用版本）→ schema 版本 V3_2
-- 幂等：可安全重复执行（CREATE TABLE IF NOT EXISTS）
-- ============================================================

-- 定时任务表：用户可配置 Cron 表达式触发定时同步/下架等操作
CREATE TABLE IF NOT EXISTS xianyu_schedule_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_name VARCHAR(100) NOT NULL,           -- 任务名称
    task_type VARCHAR(30) NOT NULL,            -- SYNC_GOODS / PUBLISH / OFF_SHELF
    cron_expr VARCHAR(100) NOT NULL,           -- Cron 表达式
    account_id BIGINT,                         -- 关联账号ID（SYNC_GOODS 必填）
    config_json TEXT,                          -- 任务配置（JSON，如下架的商品ID列表）
    enabled TINYINT DEFAULT 1,                 -- 是否启用 1启用 0禁用
    paused TINYINT DEFAULT 0,                  -- 是否因连续失败自动暂停 1暂停
    fail_count INTEGER DEFAULT 0,              -- 连续失败次数
    last_run_time DATETIME,                    -- 最近一次执行时间
    last_run_status VARCHAR(20),               -- SUCCESS / FAILED
    last_error TEXT,                           -- 最近一次失败原因
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_schedule_task_enabled
    ON xianyu_schedule_task(enabled);
CREATE INDEX IF NOT EXISTS idx_schedule_task_account
    ON xianyu_schedule_task(account_id);
