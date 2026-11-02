-- 触发器时间基准修复：CURRENT_TIMESTAMP 在 SQLite 中是 UTC，
-- 触发器把每次 UPDATE 后的 updated_time 覆盖成 UTC，导致界面时间整体慢 8 小时。
-- 统一重建为 datetime('now','localtime')，与 JVM(-Duser.timezone=Asia/Shanghai) 及列默认值一致。

DROP TRIGGER IF EXISTS update_sys_user_time;
CREATE TRIGGER update_sys_user_time
AFTER UPDATE ON sys_user
BEGIN
    UPDATE sys_user SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_sys_login_token_time;
CREATE TRIGGER update_sys_login_token_time
AFTER UPDATE ON sys_login_token
BEGIN
    UPDATE sys_login_token SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_xianyu_account_time;
CREATE TRIGGER update_xianyu_account_time
AFTER UPDATE ON xianyu_account
BEGIN
    UPDATE xianyu_account SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_xianyu_cookie_time;
CREATE TRIGGER update_xianyu_cookie_time
AFTER UPDATE ON xianyu_cookie
BEGIN
    UPDATE xianyu_cookie SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_xianyu_goods_time;
CREATE TRIGGER update_xianyu_goods_time
AFTER UPDATE ON xianyu_goods
BEGIN
    UPDATE xianyu_goods SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_xianyu_goods_config_time;
CREATE TRIGGER update_xianyu_goods_config_time
AFTER UPDATE ON xianyu_goods_config
BEGIN
    UPDATE xianyu_goods_config SET update_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_xianyu_delivery_block_rule_time;
CREATE TRIGGER update_xianyu_delivery_block_rule_time
AFTER UPDATE ON xianyu_delivery_block_rule
BEGIN
    UPDATE xianyu_delivery_block_rule SET update_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_xianyu_goods_auto_delivery_config_time;
CREATE TRIGGER update_xianyu_goods_auto_delivery_config_time
AFTER UPDATE ON xianyu_goods_auto_delivery_config
BEGIN
    UPDATE xianyu_goods_auto_delivery_config SET update_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_xianyu_sys_setting_time;
CREATE TRIGGER update_xianyu_sys_setting_time
AFTER UPDATE ON xianyu_sys_setting
BEGIN
    UPDATE xianyu_sys_setting SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_xianyu_kami_config_time;
CREATE TRIGGER update_xianyu_kami_config_time
AFTER UPDATE ON xianyu_kami_config
BEGIN
    UPDATE xianyu_kami_config SET update_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_xianyu_goods_sku_time;
CREATE TRIGGER update_xianyu_goods_sku_time
AFTER UPDATE ON xianyu_goods_sku
BEGIN
    UPDATE xianyu_goods_sku SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_xianyu_goods_sku_property_time;
CREATE TRIGGER update_xianyu_goods_sku_property_time
AFTER UPDATE ON xianyu_goods_sku_property
BEGIN
    UPDATE xianyu_goods_sku_property SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_webhook_config_time;
CREATE TRIGGER update_webhook_config_time
AFTER UPDATE ON webhook_config
BEGIN
    UPDATE webhook_config SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_job_definition_time;
CREATE TRIGGER update_job_definition_time
AFTER UPDATE ON job_definition
BEGIN
    UPDATE job_definition SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_delivery_compensation_time;
CREATE TRIGGER update_delivery_compensation_time
AFTER UPDATE ON delivery_compensation
BEGIN
    UPDATE delivery_compensation SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;
DROP TRIGGER IF EXISTS update_sys_role_time;
CREATE TRIGGER update_sys_role_time
AFTER UPDATE ON sys_role
BEGIN
    UPDATE sys_role SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

DROP TRIGGER IF EXISTS update_sys_permission_time;
CREATE TRIGGER update_sys_permission_time
AFTER UPDATE ON sys_permission
BEGIN
    UPDATE sys_permission SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;
