-- ============================================================
-- V3_11：账号数据隔离（P0-1 sys_user_account_scope）
-- 对应版本：v3.3.0（应用版本）→ schema 版本 V3_11
-- 语义：ADMIN 始终可见全部账号；非管理员配置了范围时仅可见范围内账号；
--       未配置范围（无记录）时保持向后兼容，可见全部账号。
-- 幂等：所有 DDL 使用 IF NOT EXISTS，INSERT 使用 OR IGNORE
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_user_account_scope (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    account_id INTEGER NOT NULL,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
    UNIQUE(user_id, account_id)
);
CREATE INDEX IF NOT EXISTS idx_user_account_scope_user ON sys_user_account_scope(user_id);
CREATE INDEX IF NOT EXISTS idx_user_account_scope_account ON sys_user_account_scope(account_id);
