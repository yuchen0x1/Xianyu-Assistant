-- ============================================================
-- V3_3：v3.1.0 代理池轮换配置（种子数据）
-- 对应版本：v3.1.0（应用版本）→ schema 版本 V3_3
-- 幂等：INSERT OR IGNORE，可安全重复执行
-- 说明：代理池配置复用 xianyu_sys_setting 表，无需建新表。
--       默认关闭，用户在前端"设置 → 代理池"配置后开启生效。
-- ============================================================

-- 代理池总开关：1 启用 / 0 关闭（默认关闭）
INSERT OR IGNORE INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('proxy_enabled', '0', '代理池总开关：1启用，0关闭。默认关闭，配置代理列表后开启生效，无需重启');

-- 代理列表：JSON 数组，每项 {host, port, type, username, password}
INSERT OR IGNORE INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('proxy_list', '[]', '代理列表（JSON数组）：如 [{"host":"1.2.3.4","port":8080,"type":"HTTP","username":"u","password":"p"}]；type 可选 HTTP/SOCKS');

-- 黑名单 TTL（秒）：代理失败后加入黑名单的存活时间，过期自动恢复
INSERT OR IGNORE INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('proxy_blacklist_ttl_seconds', '300', '代理黑名单存活秒数：失败代理加入黑名单后的自动恢复时间，默认 300 秒');

-- 单次请求最大尝试次数（含首次）
INSERT OR IGNORE INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('proxy_max_retries', '3', '代理池单次请求最大尝试次数（含首次）：网络层失败时切换下一个代理重试，默认 3');
