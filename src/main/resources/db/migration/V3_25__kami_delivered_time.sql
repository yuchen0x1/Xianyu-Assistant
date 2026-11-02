-- 卡密三态：区分「预占待确认」与「已交付」。
-- status=0                       -> 可用
-- status=1 AND delivered_time NULL      -> 预占中（发货结果未确认，可被巡检回收或人工核对）
-- status=1 AND delivered_time NOT NULL  -> 已交付（终态）
ALTER TABLE xianyu_kami_item ADD COLUMN delivered_time DATETIME;

-- 存量数据全部按「已交付」回填：升级时正在进行的预占宁可判为已交付，也不能被巡检误释放。
UPDATE xianyu_kami_item
SET delivered_time = COALESCE(used_time, create_time)
WHERE status = 1 AND delivered_time IS NULL;

CREATE INDEX IF NOT EXISTS idx_kami_item_pending
    ON xianyu_kami_item(status, delivered_time, used_time);
