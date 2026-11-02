-- 商品发布模板表（卡片F3：模板CRUD + CSV批量导入上架）
CREATE TABLE IF NOT EXISTS xianyu_goods_template (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title VARCHAR(300) NOT NULL,
    description TEXT,
    price_placeholder VARCHAR(50),
    image_urls TEXT,
    category_id VARCHAR(100),
    remark VARCHAR(500),
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time DATETIME DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_goods_template_title ON xianyu_goods_template(title);
