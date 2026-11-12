-- V3_27：有序多段自动发货模板与订单/买家变量
-- 旧配置继续可用；模板通过 delivery_template_id 绑定到商品/SKU 配置。

CREATE TABLE IF NOT EXISTS xianyu_delivery_template (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_delivery_template_account_name
    ON xianyu_delivery_template(xianyu_account_id, name);
CREATE INDEX IF NOT EXISTS idx_delivery_template_account
    ON xianyu_delivery_template(xianyu_account_id, enabled, update_time);

CREATE TABLE IF NOT EXISTS xianyu_delivery_template_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    template_id BIGINT NOT NULL,
    sort_order INTEGER NOT NULL,
    message_type VARCHAR(20) NOT NULL DEFAULT 'TEXT',
    content TEXT NOT NULL,
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (template_id) REFERENCES xianyu_delivery_template(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_delivery_template_message_order
    ON xianyu_delivery_template_message(template_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_delivery_template_message_template
    ON xianyu_delivery_template_message(template_id, sort_order);

ALTER TABLE xianyu_goods_auto_delivery_config
    ADD COLUMN delivery_template_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_auto_delivery_config_template
    ON xianyu_goods_auto_delivery_config(delivery_template_id);
