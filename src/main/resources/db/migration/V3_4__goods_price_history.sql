-- ============================================================
-- V3_4：v3.2.0 商品价格历史（F6.1）
-- 对应版本：v3.2.0（应用版本）→ schema 版本 V3_4
-- 幂等：可安全重复执行（CREATE TABLE IF NOT EXISTS）
-- ============================================================

-- 商品价格历史表：商品刷新/保存时记录价格快照，供前端价格趋势曲线使用
-- 去重策略：同一商品同一天内最多一条快照（由 Service 层控制），避免商品刷新产生大量重复数据
CREATE TABLE IF NOT EXISTS xianyu_goods_price_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xy_good_id VARCHAR(100) NOT NULL,              -- 闲鱼商品ID
    xianyu_account_id BIGINT,                      -- 关联账号
    price DECIMAL(10, 2),                          -- 快照价格
    snapshot_time DATETIME DEFAULT (datetime('now', 'localtime'))  -- 快照时间
);

CREATE INDEX IF NOT EXISTS idx_price_history_good
    ON xianyu_goods_price_history(xy_good_id, snapshot_time);
