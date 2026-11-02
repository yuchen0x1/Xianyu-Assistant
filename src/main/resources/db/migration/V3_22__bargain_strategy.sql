-- 智能议价：商品级议价策略配置 + 会话级议价轮次跟踪
ALTER TABLE xianyu_goods_config ADD COLUMN bargain_on TINYINT DEFAULT 0;
ALTER TABLE xianyu_goods_config ADD COLUMN bargain_floor_price TEXT;
ALTER TABLE xianyu_goods_config ADD COLUMN bargain_step_price TEXT;
ALTER TABLE xianyu_goods_config ADD COLUMN bargain_max_rounds INTEGER DEFAULT 3;

CREATE TABLE IF NOT EXISTS bargain_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,                 -- 闲鱼账号ID
    s_id VARCHAR(200) NOT NULL,                        -- 会话ID（账号内唯一标识一场对话）
    xy_goods_id VARCHAR(100) NOT NULL,                 -- 闲鱼商品ID
    buyer_user_id VARCHAR(100),                        -- 买家用户ID
    rounds_used INTEGER DEFAULT 0,                     -- 已使用的议价轮数
    last_offer_price TEXT,                             -- 买家最近出价（元）
    last_reply_content TEXT,                           -- 卖方最近议价回复
    status VARCHAR(20) DEFAULT 'ACTIVE',               -- 状态：ACTIVE-议价中 / DEAL-已接受 / ENDED-已结束
    last_bargain_at DATETIME,                          -- 最近一次议价时间（超时自动重置轮次）
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time DATETIME DEFAULT (datetime('now', 'localtime')),
    UNIQUE (xianyu_account_id, s_id)
);

CREATE INDEX IF NOT EXISTS idx_bargain_session_goods ON bargain_session(xianyu_account_id, xy_goods_id);
