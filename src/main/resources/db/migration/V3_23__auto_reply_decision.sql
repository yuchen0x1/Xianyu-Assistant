-- 自动回复决策日志：记录买家消息未被自动回复的原因（人工接管/商品未同步/无策略/未生成内容），
-- 让"为什么这条消息没回"可以在控制台内追溯，而不是只能翻日志文件。
CREATE TABLE IF NOT EXISTS auto_reply_decision (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,                 -- 闲鱼账号ID
    s_id VARCHAR(200),                                 -- 会话ID
    xy_goods_id VARCHAR(100),                          -- 闲鱼商品ID
    buyer_user_id VARCHAR(100),                        -- 买家用户ID
    buyer_message TEXT,                                -- 触发消息内容（截断存储）
    reason_code VARCHAR(50) NOT NULL,                  -- HUMAN_TAKEOVER / GOODS_NOT_FOUND / NO_REPLY_STRATEGY / NO_REPLY_CONTENT
    detail VARCHAR(500),                               -- 补充说明
    create_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_auto_reply_decision_time ON auto_reply_decision(create_time DESC);
CREATE INDEX IF NOT EXISTS idx_auto_reply_decision_session ON auto_reply_decision(xianyu_account_id, s_id, reason_code, create_time);
