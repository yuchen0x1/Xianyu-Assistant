-- ============================================================
-- V3_1：v3.0.0 卖家运营闭环（商品发布历史）
-- 对应版本：v3.0.0（应用版本）→ schema 版本 V3_1
-- 幂等：可安全重复执行（CREATE TABLE IF NOT EXISTS）
-- ============================================================

-- 商品发布历史表：记录每次发布/下架操作的结果，供前端查看与重试
CREATE TABLE IF NOT EXISTS xianyu_publish_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id BIGINT NOT NULL,                -- 发布账号ID
    account_note VARCHAR(100),                 -- 账号备注快照
    title VARCHAR(300),                        -- 商品标题
    description TEXT,                          -- 商品描述
    price DECIMAL(10, 2),                      -- 商品价格
    quantity INTEGER DEFAULT 1,                -- 库存数量
    image_count INTEGER DEFAULT 0,             -- 图片数量
    category_id VARCHAR(100),                  -- 类目ID（AI 识别结果）
    item_id VARCHAR(100),                      -- 发布成功后返回的商品ID
    operation_type VARCHAR(20) NOT NULL,       -- PUBLISH / OFF_SHELF
    status VARCHAR(20) NOT NULL,               -- SUCCESS / FAILED
    error_message TEXT,                        -- 失败原因
    raw_response TEXT,                         -- 平台原始响应（脱敏）
    created_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_publish_history_account
    ON xianyu_publish_history(account_id);
CREATE INDEX IF NOT EXISTS idx_publish_history_created
    ON xianyu_publish_history(created_time);
