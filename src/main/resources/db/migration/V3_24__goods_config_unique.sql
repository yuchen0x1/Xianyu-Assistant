-- 商品配置表按 (账号, 商品) 唯一化：
-- saveOrUpdate 类接口为 check-then-insert，无唯一约束时并发写入会产生重复行，
-- 重复行会让 selectByAccountAndGoodsId 的单行语义失效。
-- 先清理重复行（每组保留最新一条），再建唯一索引。

DELETE FROM xianyu_goods_config
WHERE id NOT IN (
    SELECT MAX(id) FROM xianyu_goods_config
    WHERE xianyu_account_id IS NOT NULL AND xy_goods_id IS NOT NULL
    GROUP BY xianyu_account_id, xy_goods_id
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_goods_config_account_goods
    ON xianyu_goods_config(xianyu_account_id, xy_goods_id);
