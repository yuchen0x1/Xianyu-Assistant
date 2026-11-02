-- 系统用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,              -- 用户名
    password VARCHAR(200) NOT NULL,                    -- 密码（BCrypt加密）
    status TINYINT DEFAULT 1,                          -- 状态 1:正常 0:禁用
    last_login_time DATETIME,                          -- 最后登录时间
    last_login_ip VARCHAR(50),                         -- 最后登录IP
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),  -- 创建时间
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))   -- 更新时间
);

-- 登录Token表
CREATE TABLE IF NOT EXISTS sys_login_token (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id BIGINT NOT NULL,                           -- 关联用户ID
    token VARCHAR(500) NOT NULL,                       -- JWT Token
    device_id VARCHAR(100),                            -- 设备标识（User-Agent哈希）
    login_ip VARCHAR(50),                              -- 登录IP
    expire_time DATETIME NOT NULL,                     -- 过期时间
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),  -- 创建时间
    updated_time DATETIME DEFAULT (datetime('now', 'localtime')),  -- 更新时间
    FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_sys_user_username ON sys_user(username);
CREATE INDEX IF NOT EXISTS idx_sys_login_token_user_id ON sys_login_token(user_id);
CREATE INDEX IF NOT EXISTS idx_sys_login_token_token ON sys_login_token(token);
CREATE INDEX IF NOT EXISTS idx_sys_login_token_expire_time ON sys_login_token(expire_time);

-- 触发器
CREATE TRIGGER IF NOT EXISTS update_sys_user_time
AFTER UPDATE ON sys_user
BEGIN
    UPDATE sys_user SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;
CREATE TRIGGER IF NOT EXISTS update_sys_login_token_time
AFTER UPDATE ON sys_login_token
BEGIN
    UPDATE sys_login_token SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;
-- 闲鱼账号表
CREATE TABLE IF NOT EXISTS xianyu_account (
    id INTEGER PRIMARY KEY AUTOINCREMENT,         -- 删除账号时同步清理sqlite_sequence使ID可回收
    account_note VARCHAR(100),                    -- 闲鱼账号备注
    unb VARCHAR(100),                             -- UNB标识
    device_id VARCHAR(100),                       -- 设备ID（UUID格式-用户ID，用于WebSocket连接）
    status TINYINT DEFAULT 1,                     -- 账号状态 1:正常 -1:需要手机号验证
    websocket_enabled TINYINT NOT NULL DEFAULT 1, -- 是否允许后台自动保持WebSocket连接
    nickname VARCHAR(200),                        -- 闲鱼昵称（扫码后自动获取）
    avatar_url VARCHAR(500),                      -- 闲鱼头像URL
    credit_score INTEGER,                         -- 闲鱼信用分
    sold_count INTEGER,                           -- 卖出件数
    followers INTEGER,                            -- 粉丝数
    following INTEGER,                            -- 关注数
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),  -- 创建时间
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))   -- 更新时间
);

-- 闲鱼Cookie表
CREATE TABLE IF NOT EXISTS xianyu_cookie (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,            -- 关联的闲鱼账号ID
    cookie_text TEXT,                             -- 完整的Cookie字符串
    m_h5_tk VARCHAR(500),                         -- _m_h5_tk token（用于API签名）
    cookie_status TINYINT DEFAULT 1,              -- Cookie状态 1:有效 2:过期 3:失效
    expire_time DATETIME,                         -- 过期时间
    websocket_token TEXT,                         -- WebSocket accessToken
    token_expire_time INTEGER,                    -- Token过期时间戳（毫秒）
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),  -- 创建时间
    updated_time DATETIME DEFAULT (datetime('now', 'localtime')),  -- 更新时间
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

-- 创建索引
CREATE INDEX IF NOT EXISTS idx_account_unb ON xianyu_account(unb);
CREATE INDEX IF NOT EXISTS idx_cookie_account_id ON xianyu_cookie(xianyu_account_id);
CREATE INDEX IF NOT EXISTS idx_cookie_status ON xianyu_cookie(cookie_status);
CREATE INDEX IF NOT EXISTS idx_token_expire_time ON xianyu_cookie(token_expire_time);

-- 创建更新时间触发器（SQLite不支持ON UPDATE datetime('now', 'localtime')，需要用触发器）
-- 注意：触发器使用特殊分隔符 $$
CREATE TRIGGER IF NOT EXISTS update_xianyu_account_time 
AFTER UPDATE ON xianyu_account
BEGIN
    UPDATE xianyu_account SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;
CREATE TRIGGER IF NOT EXISTS update_xianyu_cookie_time 
AFTER UPDATE ON xianyu_cookie
BEGIN
    UPDATE xianyu_cookie SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;
-- 闲鱼商品信息表
CREATE TABLE IF NOT EXISTS xianyu_goods (
    id BIGINT PRIMARY KEY,                        -- 表ID（使用雪花ID）
    xy_good_id VARCHAR(100) NOT NULL,             -- 闲鱼商品ID
    xianyu_account_id BIGINT,                     -- 关联的闲鱼账号ID
    title VARCHAR(500),                           -- 商品标题
    cover_pic TEXT,                               -- 封面图片URL
    info_pic TEXT,                                -- 商品详情图片（JSON数组）
    detail_info TEXT,                             -- 商品详情信息（预留字段）
    detail_url TEXT,                              -- 商品详情页URL
    sold_price VARCHAR(50),                           -- 商品价格
    sku_count INTEGER DEFAULT 0,                     -- SKU数量
    status TINYINT DEFAULT 0,                         -- 商品状态 0:在售 1:已下架 2:已售出
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),  -- 创建时间
    updated_time DATETIME DEFAULT (datetime('now', 'localtime')),  -- 更新时间
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

-- 创建商品表索引
CREATE UNIQUE INDEX IF NOT EXISTS idx_goods_xy_good_id ON xianyu_goods(xy_good_id);
CREATE INDEX IF NOT EXISTS idx_goods_status ON xianyu_goods(status);
CREATE INDEX IF NOT EXISTS idx_goods_account_id ON xianyu_goods(xianyu_account_id);

-- 创建商品表更新时间触发器
CREATE TRIGGER IF NOT EXISTS update_xianyu_goods_time
AFTER UPDATE ON xianyu_goods
BEGIN
    UPDATE xianyu_goods SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;
-- 闲鱼聊天消息表
CREATE TABLE IF NOT EXISTS xianyu_chat_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    
    -- 关联信息
    xianyu_account_id BIGINT NOT NULL,            -- 关联的闲鱼账号ID
    
    -- WebSocket消息字段
    lwp VARCHAR(50),                              -- websocket消息类型，比如："/s/para"
    pnm_id VARCHAR(100) NOT NULL,                 -- 对应的消息pnmid，比如："3813496236127.PNM"（字段1.3）
    s_id VARCHAR(100),                            -- 消息聊天框id，比如："55435931514@goofish"（字段1.2）
    
    -- 消息内容
    content_type INTEGER,                         -- 消息类别，contentType=1用户消息，32系统消息（字段1.6.3.5中的contentType）
    msg_content TEXT,                             -- 消息内容，对应1.10.reminderContent
    
    -- 发送者信息
    sender_user_name VARCHAR(200),                -- 发送者用户名称，对应1.10.reminderTitle
    sender_user_id VARCHAR(100),                  -- 发送者用户id，对应1.10.senderUserId
    sender_app_v VARCHAR(50),                     -- 发送者app版本，对应1.10._appVersion
    sender_os_type VARCHAR(20),                   -- 发送者系统版本，对应1.10._platform
    
    -- 消息链接
    reminder_url TEXT,                            -- 消息链接，对应1.10.reminderUrl
    xy_goods_id VARCHAR(100),                     -- 闲鱼商品ID，从reminder_url中的itemId参数解析
    
    -- 完整消息体
    complete_msg TEXT NOT NULL,                   -- 完整的消息体JSON
    
    -- 时间信息
    message_time BIGINT,                          -- 消息时间戳（毫秒，字段1.5）
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),  -- 创建时间
    
    -- 外键约束
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

-- 创建聊天消息表索引
CREATE INDEX IF NOT EXISTS idx_chat_message_account_id ON xianyu_chat_message(xianyu_account_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_pnm_id ON xianyu_chat_message(pnm_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_s_id ON xianyu_chat_message(s_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_sender_user_id ON xianyu_chat_message(sender_user_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_content_type ON xianyu_chat_message(content_type);
CREATE INDEX IF NOT EXISTS idx_chat_message_time ON xianyu_chat_message(message_time);
CREATE INDEX IF NOT EXISTS idx_chat_message_goods_id ON xianyu_chat_message(xy_goods_id);

-- 创建唯一索引，防止重复消息
CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_message_unique 
ON xianyu_chat_message(xianyu_account_id, pnm_id);

-- 商品配置表
CREATE TABLE IF NOT EXISTS xianyu_goods_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,                -- 闲鱼账号ID
    xianyu_goods_id BIGINT,                           -- 本地闲鱼商品ID
    xy_goods_id VARCHAR(100) NOT NULL,                -- 闲鱼的商品ID
    xianyu_auto_delivery_on TINYINT DEFAULT 0,        -- 自动发货开关：1-开启，0-关闭，默认关闭
    xianyu_auto_reply_on TINYINT DEFAULT 0,           -- AI回复开关：1-开启，0-关闭，默认关闭
    xianyu_auto_reply_context_on TINYINT DEFAULT 1,   -- 携带上下文开关：1-开启，0-关闭，默认开启，跟随AI回复开关
    xianyu_keyword_reply_on TINYINT DEFAULT 0,        -- 关键词回复开关：1-开启，0-关闭，默认关闭
    human_intervention_on TINYINT DEFAULT 0,          -- 人工干预开关：1-开启，0-关闭，默认关闭。开启后延时任务到期时若卖家已人工回复则取消自动回复
    human_intervention_minutes INTEGER DEFAULT 10,    -- 人工干预不回复持续时间（分钟），默认10
    reply_manual_confirm_on TINYINT DEFAULT 0,        -- AI回复人工确认模式：1-开启，0-关闭，默认关闭。开启后AI生成草稿，人工确认后再发送
    bargain_on TINYINT DEFAULT 0,                     -- 智能议价开关：1-开启，0-关闭，默认关闭
    bargain_floor_price TEXT,                         -- 议价保底价（元），买家出价不低于此价即可成交
    bargain_step_price TEXT,                          -- 每轮让价步长（元），为空时第一轮直接给到保底价
    bargain_max_rounds INTEGER DEFAULT 3,             -- 最大议价轮数，超过后坚持保底价不再让步
    fixed_material TEXT,                              -- 固定资料（用于AI自动回复）
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),   -- 创建时间
    update_time DATETIME DEFAULT (datetime('now', 'localtime')),   -- 更新时间
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

-- 创建商品配置表索引
CREATE INDEX IF NOT EXISTS idx_goods_config_account_id ON xianyu_goods_config(xianyu_account_id);
CREATE INDEX IF NOT EXISTS idx_goods_config_xy_goods_id ON xianyu_goods_config(xy_goods_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_goods_config_unique ON xianyu_goods_config(xianyu_account_id, xy_goods_id);

-- 创建商品配置表更新时间触发器
CREATE TRIGGER IF NOT EXISTS update_xianyu_goods_config_time
AFTER UPDATE ON xianyu_goods_config
BEGIN
    UPDATE xianyu_goods_config SET update_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;
-- 商品自动发货配置表
CREATE TABLE IF NOT EXISTS xianyu_goods_auto_delivery_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,                -- 闲鱼账号ID
    xianyu_goods_id BIGINT,                           -- 本地闲鱼商品ID
    xy_goods_id VARCHAR(100) NOT NULL,                -- 闲鱼的商品ID
    delivery_mode TINYINT DEFAULT 1,                  -- 发货模式：1-自动发货，2-卡密发货，3-自定义发货
    sku_id VARCHAR(32),                              -- SKU ID，NULL表示商品级别配置（无SKU或默认）
    sku_name VARCHAR(200),                            -- SKU名称，如"新号"、"30级"
    auto_delivery_content TEXT,                       -- 自动发货的文本内容
    kami_config_ids TEXT,                             -- 卡密发货：绑定的卡密配置ID列表（逗号分隔）
    kami_delivery_template TEXT,                      -- 卡密发货文案模板，使用{kmKey}占位符替换卡密内容
    auto_delivery_image_url TEXT,                     -- 自动发货图片URL
    auto_confirm_shipment TINYINT DEFAULT 0,          -- 自动确认发货开关：0-关闭，1-开启
    rag_delay_seconds INTEGER DEFAULT 15,             -- 自动回复延时秒数（RAG回复延时）
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),   -- 创建时间
    update_time DATETIME DEFAULT (datetime('now', 'localtime')),   -- 更新时间
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

-- 创建自动发货配置表索引
CREATE INDEX IF NOT EXISTS idx_auto_delivery_config_account_id ON xianyu_goods_auto_delivery_config(xianyu_account_id);
CREATE INDEX IF NOT EXISTS idx_auto_delivery_config_xy_goods_id ON xianyu_goods_auto_delivery_config(xy_goods_id);
DROP INDEX IF EXISTS idx_auto_delivery_config_unique;
UPDATE xianyu_goods_auto_delivery_config SET sku_id = NULL WHERE sku_id = '';
CREATE UNIQUE INDEX IF NOT EXISTS idx_auto_delivery_config_unique_v2 ON xianyu_goods_auto_delivery_config(xianyu_account_id, xy_goods_id, COALESCE(sku_id, ''));

-- 账号级发货风控规则
CREATE TABLE IF NOT EXISTS xianyu_delivery_block_rule (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,
    rule_code VARCHAR(50) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    priority INTEGER NOT NULL DEFAULT 0,
    block_reason VARCHAR(500),
    excluded_item_ids TEXT NOT NULL DEFAULT '[]',
    config_json TEXT NOT NULL DEFAULT '{}',
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_delivery_block_rule_account_code
ON xianyu_delivery_block_rule(xianyu_account_id, rule_code);
CREATE INDEX IF NOT EXISTS idx_delivery_block_rule_account_priority
ON xianyu_delivery_block_rule(xianyu_account_id, priority);

CREATE TRIGGER IF NOT EXISTS update_xianyu_delivery_block_rule_time
AFTER UPDATE ON xianyu_delivery_block_rule
BEGIN
    UPDATE xianyu_delivery_block_rule SET update_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

-- 创建自动发货配置表更新时间触发器
CREATE TRIGGER IF NOT EXISTS update_xianyu_goods_auto_delivery_config_time
AFTER UPDATE ON xianyu_goods_auto_delivery_config
BEGIN
    UPDATE xianyu_goods_auto_delivery_config SET update_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;
-- 商品订单表
CREATE TABLE IF NOT EXISTS xianyu_goods_order (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,                -- 闲鱼账号ID
    xianyu_goods_id BIGINT,                           -- 本地闲鱼商品ID
    xy_goods_id VARCHAR(100) NOT NULL,                -- 闲鱼的商品ID
    pnm_id VARCHAR(100) NOT NULL,                     -- 消息pnmid，用于防止重复
    order_id VARCHAR(100),                            -- 订单ID
    buyer_user_id VARCHAR(100),                       -- 买家用户ID
    buyer_user_name VARCHAR(256),                     -- 买家用户名
    sid VARCHAR(200),                                 -- 会话ID（用于WebSocket发送消息的cid）
    content TEXT,                                     -- 发货消息内容
    state TINYINT DEFAULT 0,                          -- 发货是否成功: 1-成功, 0-待发货, -1-失败
    fail_reason VARCHAR(500),                         -- 失败理由
    confirm_state TINYINT DEFAULT 0,                  -- 确认发货状态: 0-未确认, 1-已确认
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),  -- 创建时间(本地时间)
    goods_title VARCHAR(256),                        -- 商品标题
    sku_name VARCHAR(200),                           -- SKU名称，如"新号"、"30级"
    order_create_time VARCHAR(50),                   -- 下单时间
    pay_success_time VARCHAR(50),                    -- 付款时间
    consign_time VARCHAR(50),                        -- 发货时间
    total_price VARCHAR(20),                         -- 订单金额
    buy_num INTEGER,                                 -- 购买数量
    idempotency_key VARCHAR(100),                    -- 发货幂等键
    delivery_phase VARCHAR(30) DEFAULT 'NONE',       -- 发货阶段
    unknown_since DATETIME,                          -- 结果未知时间
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

CREATE INDEX IF NOT EXISTS idx_goods_order_idempotency ON xianyu_goods_order(idempotency_key);

-- 创建订单表索引
CREATE INDEX IF NOT EXISTS idx_goods_order_account_id ON xianyu_goods_order(xianyu_account_id);
CREATE INDEX IF NOT EXISTS idx_goods_order_xy_goods_id ON xianyu_goods_order(xy_goods_id);
CREATE INDEX IF NOT EXISTS idx_goods_order_state ON xianyu_goods_order(state);
CREATE INDEX IF NOT EXISTS idx_goods_order_create_time ON xianyu_goods_order(create_time);
CREATE INDEX IF NOT EXISTS idx_goods_order_pnm_id ON xianyu_goods_order(pnm_id);
CREATE INDEX IF NOT EXISTS idx_goods_order_order_id ON xianyu_goods_order(order_id);

-- 创建唯一索引，防止同一消息重复
CREATE UNIQUE INDEX IF NOT EXISTS idx_goods_order_unique 
ON xianyu_goods_order(xianyu_account_id, pnm_id);

-- 商品自动回复记录表
CREATE TABLE IF NOT EXISTS xianyu_goods_auto_reply_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,                -- 闲鱼账号ID
    xianyu_goods_id BIGINT,                           -- 本地闲鱼商品ID
    xy_goods_id VARCHAR(100) NOT NULL,                -- 闲鱼的商品ID
    s_id VARCHAR(100),                                -- 会话ID（用于延时任务去重）
    pnm_id VARCHAR(100),                              -- 触发回复的消息pnmId
    buyer_user_id VARCHAR(100),                       -- 买家用户ID
    buyer_user_name VARCHAR(200),                     -- 买家用户名
    buyer_message TEXT,                               -- 买家消息内容
    reply_content TEXT,                               -- 回复消息内容
    reply_type TINYINT DEFAULT 1,                     -- 回复类型：1-关键词匹配，2-RAG智能回复
    matched_keyword VARCHAR(200),                     -- 匹配的关键词
    trigger_context TEXT,                             -- 触发上下文JSON（包含触发消息列表和RAG命中资料列表）
    state TINYINT DEFAULT 0,                          -- 状态：0-待回复，1-成功，-1-失败
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),   -- 创建时间
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

-- 创建自动回复记录表索引
CREATE INDEX IF NOT EXISTS idx_auto_reply_record_account_id ON xianyu_goods_auto_reply_record(xianyu_account_id);
CREATE INDEX IF NOT EXISTS idx_auto_reply_record_xy_goods_id ON xianyu_goods_auto_reply_record(xy_goods_id);
CREATE INDEX IF NOT EXISTS idx_auto_reply_record_state ON xianyu_goods_auto_reply_record(state);
CREATE INDEX IF NOT EXISTS idx_auto_reply_record_create_time ON xianyu_goods_auto_reply_record(create_time);
CREATE INDEX IF NOT EXISTS idx_auto_reply_record_s_id ON xianyu_goods_auto_reply_record(s_id);
CREATE INDEX IF NOT EXISTS idx_auto_reply_record_pnm_id ON xianyu_goods_auto_reply_record(pnm_id);
-- 创建唯一索引，防止同一会话重复回复（用于延时任务去重）
CREATE UNIQUE INDEX IF NOT EXISTS idx_auto_reply_record_unique 
ON xianyu_goods_auto_reply_record(xianyu_account_id, s_id, pnm_id);


-- 操作日志表
CREATE TABLE IF NOT EXISTS xianyu_operation_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT,                        -- 账号ID
    operation_type VARCHAR(50),                      -- 操作类型
    operation_module VARCHAR(100),                   -- 操作模块
    operation_desc VARCHAR(500),                     -- 操作描述
    operation_status TINYINT,                        -- 操作状态：1-成功，0-失败，2-部分成功
    target_type VARCHAR(50),                         -- 目标类型
    target_id VARCHAR(100),                          -- 目标ID
    request_params TEXT,                             -- 请求参数（JSON格式）
    response_result TEXT,                            -- 响应结果（JSON格式）
    error_message TEXT,                              -- 错误信息
    ip_address VARCHAR(50),                          -- IP地址
    user_agent VARCHAR(500),                         -- 浏览器UA
    duration_ms INTEGER,                             -- 操作耗时（毫秒）
    create_time BIGINT,                              -- 创建时间（时间戳，毫秒）
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

-- 创建操作日志表索引
CREATE INDEX IF NOT EXISTS idx_operation_log_account_id ON xianyu_operation_log(xianyu_account_id);
CREATE INDEX IF NOT EXISTS idx_operation_log_type ON xianyu_operation_log(operation_type);
CREATE INDEX IF NOT EXISTS idx_operation_log_status ON xianyu_operation_log(operation_status);
CREATE INDEX IF NOT EXISTS idx_operation_log_create_time ON xianyu_operation_log(create_time);

-- 系统配置表
CREATE TABLE IF NOT EXISTS xianyu_sys_setting (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    setting_key VARCHAR(100) NOT NULL UNIQUE,              -- 配置键
    setting_value TEXT,                                     -- 配置值
    setting_desc VARCHAR(500),                              -- 配置描述
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),  -- 创建时间
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))   -- 更新时间
);

-- 创建系统配置表索引
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_setting_key ON xianyu_sys_setting(setting_key);

-- 创建系统配置表更新时间触发器
CREATE TRIGGER IF NOT EXISTS update_xianyu_sys_setting_time
AFTER UPDATE ON xianyu_sys_setting
BEGIN
    UPDATE xianyu_sys_setting SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;
-- 初始化系统配置数据
INSERT OR IGNORE INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('sys_prompt', '你是一个闲鱼卖家，你叫肥极喵，不要回复的像AI，简短回答
参考相关信息回答,不要乱回答,不知道就换不同语气回复提示用户详细点询问', 'AI智能回复的系统提示词');

-- AI API Key配置（初始为空，用户在前端设置页面配置后生效）
INSERT OR IGNORE INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('ai_api_key', '', 'AI服务的API Key（配置后立即生效，无需重启）');

-- AI API Base URL配置
INSERT OR IGNORE INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('ai_base_url', 'https://dashscope.aliyuncs.com/compatible-mode', 'AI服务的API Base URL');

-- AI 模型配置
INSERT OR IGNORE INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('ai_model', 'deepseek-v3', 'AI对话模型名称');

-- 邮件通知开关配置
INSERT OR IGNORE INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('email_notify_ws_disconnect_enabled', '0', 'WebSocket断连邮件通知开关：0-关闭，1-开启');

INSERT OR IGNORE INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('email_notify_cookie_expire_enabled', '0', 'Cookie过期邮件通知开关：0-关闭，1-开启');

INSERT OR IGNORE INTO xianyu_sys_setting (setting_key, setting_value, setting_desc)
VALUES ('email_notify_captcha_enabled', '0', '滑块及风控验证邮件通知开关：0-关闭，1-开启');

-- 卡密配置表
CREATE TABLE IF NOT EXISTS xianyu_kami_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,                -- 闲鱼账号ID
    alias_name VARCHAR(200),                          -- 别名
    alert_enabled TINYINT DEFAULT 0,                  -- 预警开关：0-关闭，1-开启
    alert_threshold_type TINYINT DEFAULT 1,           -- 预警阈值类型：1-数量，2-百分比
    alert_threshold_value INTEGER DEFAULT 10,         -- 预警阈值数值
    alert_email VARCHAR(200),                         -- 预警接收邮箱
    total_count INTEGER DEFAULT 0,                    -- 卡密总数（冗余计数，方便查询）
    used_count INTEGER DEFAULT 0,                     -- 已使用数量（冗余计数）
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),
    update_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

CREATE INDEX IF NOT EXISTS idx_kami_config_account_id ON xianyu_kami_config(xianyu_account_id);

CREATE TRIGGER IF NOT EXISTS update_xianyu_kami_config_time
AFTER UPDATE ON xianyu_kami_config
BEGIN
    UPDATE xianyu_kami_config SET update_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

-- 卡密明细表
CREATE TABLE IF NOT EXISTS xianyu_kami_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kami_config_id BIGINT NOT NULL,                   -- 关联卡密配置ID
    kami_content TEXT NOT NULL,                        -- 卡密内容
    status TINYINT DEFAULT 0,                         -- 状态：0-未使用，1-已占用（预占或已交付，看 delivered_time）
    order_id VARCHAR(100),                            -- 占用该卡密的发货动作ID
    used_time DATETIME,                               -- 占用（预占）时间
    delivered_time DATETIME,                          -- 交付确认时间；status=1 且为空表示预占待确认
    sort_order INTEGER DEFAULT 0,                     -- 排序号（顺序发货时使用）
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (kami_config_id) REFERENCES xianyu_kami_config(id)
);

CREATE INDEX IF NOT EXISTS idx_kami_item_config_id ON xianyu_kami_item(kami_config_id);
CREATE INDEX IF NOT EXISTS idx_kami_item_status ON xianyu_kami_item(status);
CREATE INDEX IF NOT EXISTS idx_kami_item_config_status ON xianyu_kami_item(kami_config_id, status);
CREATE INDEX IF NOT EXISTS idx_kami_item_pending ON xianyu_kami_item(status, delivered_time, used_time);

-- 卡密使用记录表
CREATE TABLE IF NOT EXISTS xianyu_kami_usage_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kami_config_id BIGINT NOT NULL,                   -- 关联卡密配置ID
    kami_item_id BIGINT NOT NULL,                     -- 关联卡密明细ID
    xianyu_account_id BIGINT NOT NULL,                -- 闲鱼账号ID
    xy_goods_id VARCHAR(100),                         -- 闲鱼商品ID
    order_id VARCHAR(100),                            -- 订单ID
    buyer_user_id VARCHAR(100),                       -- 买家用户ID
    buyer_user_name VARCHAR(256),                     -- 买家用户名
    kami_content TEXT NOT NULL,                        -- 发出的卡密内容
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (kami_config_id) REFERENCES xianyu_kami_config(id),
    FOREIGN KEY (kami_item_id) REFERENCES xianyu_kami_item(id)
);

CREATE INDEX IF NOT EXISTS idx_kami_usage_config_id ON xianyu_kami_usage_record(kami_config_id);
CREATE INDEX IF NOT EXISTS idx_kami_usage_order_id ON xianyu_kami_usage_record(order_id);
CREATE INDEX IF NOT EXISTS idx_kami_usage_account_id ON xianyu_kami_usage_record(xianyu_account_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_kami_usage_unique ON xianyu_kami_usage_record(kami_item_id, order_id);

-- 商品关键词回复规则表
CREATE TABLE IF NOT EXISTS xianyu_keyword_reply_rule (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,                -- 闲鱼账号ID
    xy_goods_id VARCHAR(100) NOT NULL,                -- 闲鱼的商品ID
    keyword VARCHAR(200) NOT NULL,                    -- 匹配关键词
    match_mode INTEGER DEFAULT 1,                     -- 匹配模式: 1=模糊匹配, 2=精准匹配
    is_fallback INTEGER DEFAULT 0,                    -- 是否为未匹配兜底规则: 0=否, 1=是
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

CREATE INDEX IF NOT EXISTS idx_keyword_rule_account_id ON xianyu_keyword_reply_rule(xianyu_account_id);
CREATE INDEX IF NOT EXISTS idx_keyword_rule_goods_id ON xianyu_keyword_reply_rule(xy_goods_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_keyword_rule_unique ON xianyu_keyword_reply_rule(xianyu_account_id, xy_goods_id, keyword) WHERE is_fallback = 0;

-- 商品关键词回复内容表
CREATE TABLE IF NOT EXISTS xianyu_keyword_reply_content (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_id BIGINT NOT NULL,                          -- 关联关键词规则ID
    reply_text TEXT,                                  -- 回复文本内容
    reply_image_url TEXT,                             -- 回复图片URL
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (rule_id) REFERENCES xianyu_keyword_reply_rule(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_keyword_content_rule_id ON xianyu_keyword_reply_content(rule_id);

-- 商品SKU表
CREATE TABLE IF NOT EXISTS xianyu_goods_sku (
    id VARCHAR(32) PRIMARY KEY,                      -- 表ID
    xy_goods_id VARCHAR(100) NOT NULL,               -- 闲鱼商品ID
    sku_id VARCHAR(32),                              -- 闲鱼SKU ID，无SKU时为NULL
    price INTEGER,                                   -- SKU价格（分）
    quantity INTEGER DEFAULT 0,                      -- SKU库存数量
    property_text VARCHAR(500),                      -- SKU属性文本，如"等级:新号"
    property_id INTEGER,                             -- 属性ID
    value_id INTEGER,                                -- 属性值ID
    value_text VARCHAR(200),                         -- 属性值文本，如"新号"、"30级"
    property_sort_order INTEGER DEFAULT 0,           -- 属性排序
    value_sort_order INTEGER DEFAULT 0,              -- 属性值排序
    features TEXT,                                   -- SKU特征JSON
    xianyu_account_id BIGINT,                        -- 关联的闲鱼账号ID
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (xianyu_account_id) REFERENCES xianyu_account(id)
);

CREATE INDEX IF NOT EXISTS idx_goods_sku_xy_goods_id ON xianyu_goods_sku(xy_goods_id);
CREATE INDEX IF NOT EXISTS idx_goods_sku_sku_id ON xianyu_goods_sku(sku_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_goods_sku_unique ON xianyu_goods_sku(xy_goods_id, sku_id);

CREATE TRIGGER IF NOT EXISTS update_xianyu_goods_sku_time
AFTER UPDATE ON xianyu_goods_sku
BEGIN
    UPDATE xianyu_goods_sku SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

-- 商品SKU属性维度表
CREATE TABLE IF NOT EXISTS xianyu_goods_sku_property (
    id VARCHAR(32) PRIMARY KEY,
    xy_goods_id VARCHAR(100) NOT NULL,
    property_id INTEGER NOT NULL,
    property_text VARCHAR(200) NOT NULL,
    property_sort_order INTEGER DEFAULT 0,
    value_id INTEGER NOT NULL,
    value_text VARCHAR(200) NOT NULL,
    value_sort_order INTEGER DEFAULT 0,
    xianyu_account_id BIGINT,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_sku_property_goods_id ON xianyu_goods_sku_property(xy_goods_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sku_property_unique ON xianyu_goods_sku_property(xy_goods_id, property_id, value_id);

CREATE TRIGGER IF NOT EXISTS update_xianyu_goods_sku_property_time
AFTER UPDATE ON xianyu_goods_sku_property
BEGIN
    UPDATE xianyu_goods_sku_property SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

-- 人工干预记录表
CREATE TABLE IF NOT EXISTS xianyu_human_intervention_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,
    xy_goods_id VARCHAR(100) NOT NULL,
    s_id VARCHAR(200) NOT NULL,
    end_time DATETIME NOT NULL,
    created_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_human_intervention_s_id ON xianyu_human_intervention_record(s_id);
CREATE INDEX IF NOT EXISTS idx_human_intervention_end_time ON xianyu_human_intervention_record(end_time);

-- 站内通知表
CREATE TABLE IF NOT EXISTS xianyu_notification (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title VARCHAR(200) NOT NULL,                    -- 通知标题
    content TEXT,                                   -- 通知内容
    notify_type VARCHAR(50),                        -- 通知类型（ws_disconnect/cookie_expire/kami_alert等）
    level VARCHAR(20) DEFAULT 'INFO',               -- 通知级别（INFO/WARNING/ERROR）
    recipient_user_id BIGINT,                       -- 收件用户ID；为空时按广播通知处理
    account_id BIGINT,                              -- 关联闲鱼账号ID
    broadcast TINYINT DEFAULT 0,                   -- 是否广播通知 0:否 1:是
    read_status TINYINT DEFAULT 0,                  -- 已读状态 0:未读 1:已读
    created_time DATETIME DEFAULT (datetime('now', 'localtime'))  -- 创建时间
);

CREATE INDEX IF NOT EXISTS idx_notification_read_status ON xianyu_notification(read_status);
CREATE INDEX IF NOT EXISTS idx_notification_created_time ON xianyu_notification(created_time);

-- 广播通知按用户记录已读回执，避免共享通知行的 read_status 串用户
CREATE TABLE IF NOT EXISTS notification_read_receipt (
    notification_id INTEGER NOT NULL,
    user_id BIGINT NOT NULL,
    read_time DATETIME DEFAULT (datetime('now', 'localtime')),
    PRIMARY KEY (notification_id, user_id),
    FOREIGN KEY (notification_id) REFERENCES xianyu_notification(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_notification_receipt_user ON notification_read_receipt(user_id);


-- 商品发布模板表（卡片F3：模板CRUD + CSV批量导入上架）
CREATE TABLE IF NOT EXISTS xianyu_goods_template (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title VARCHAR(300) NOT NULL,                -- 模板标题
    description TEXT,                           -- 模板描述
    price_placeholder VARCHAR(50),              -- 价格（数字或占位符，如 9.9 / {价格}）
    image_urls TEXT,                            -- 图片URL列表（逗号或分号分隔）
    category_id VARCHAR(100),                   -- 类目ID（可选）
    remark VARCHAR(500),                        -- 备注
    create_time DATETIME DEFAULT (datetime('now', 'localtime')),   -- 创建时间
    update_time DATETIME DEFAULT (datetime('now', 'localtime'))    -- 更新时间
);
CREATE INDEX IF NOT EXISTS idx_goods_template_title ON xianyu_goods_template(title);

-- Webhook 投递历史表（与 db/migration V3_17 一致）
CREATE TABLE IF NOT EXISTS webhook_delivery (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    webhook_id INTEGER NOT NULL,                      -- 关联 webhook_config.id
    event VARCHAR(64) NOT NULL,                       -- 事件名，如 delivery.completed
    payload_json TEXT,                                -- 事件最小 payload（禁止含 Cookie/Token/密钥）
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',    -- PENDING/SUCCESS/FAILED/UNKNOWN
    http_status INTEGER,                              -- 最近一次 HTTP 状态码
    attempts INTEGER NOT NULL DEFAULT 0,              -- 已尝试次数
    next_attempt_at DATETIME,                         -- 下一次重试时间
    last_error TEXT,                                  -- 最近一次错误（截断 500 字符）
    idempotency_key VARCHAR(200),                     -- 幂等键（webhook_id + event + 业务键）
    delivered_at DATETIME,                            -- 成功投递时间
    created_time DATETIME DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_webhook_id ON webhook_delivery(webhook_id);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_status ON webhook_delivery(status);
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_idempotency_key ON webhook_delivery(idempotency_key);


-- 告警事件表（监控中心）
CREATE TABLE IF NOT EXISTS alert_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    alert_key VARCHAR(200) NOT NULL,              -- 告警去重键
    title VARCHAR(200) NOT NULL,                  -- 告警标题
    message TEXT,                                 -- 告警正文
    level VARCHAR(20) DEFAULT 'WARNING',          -- 级别 INFO / WARNING / ERROR
    account_id BIGINT,                            -- 关联账号ID；为空表示全局告警
    status VARCHAR(20) DEFAULT 'ACTIVE',          -- ACTIVE / ACKED / SILENCED / RESOLVED
    first_seen_at DATETIME DEFAULT (datetime('now', 'localtime')),
    last_seen_at DATETIME DEFAULT (datetime('now', 'localtime')),
    occurrences INTEGER DEFAULT 1,                -- 累计触发次数
    last_sent_at DATETIME,                        -- 最近一次成功发送时间
    next_send_at DATETIME,                        -- 下次允许发送时间（发送失败不消耗窗口）
    send_attempts INTEGER DEFAULT 0,              -- 累计发送尝试次数
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_alert_event_key ON alert_event(alert_key);
CREATE INDEX IF NOT EXISTS idx_alert_event_status ON alert_event(status);
CREATE INDEX IF NOT EXISTS idx_alert_event_account ON alert_event(account_id);
CREATE INDEX IF NOT EXISTS idx_alert_event_next_send ON alert_event(next_send_at);

CREATE TABLE IF NOT EXISTS review_rule (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id BIGINT NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 0,
    delay_seconds INTEGER NOT NULL DEFAULT 86400,
    template TEXT NOT NULL,
    require_manual_confirm TINYINT NOT NULL DEFAULT 1,
    max_retries INTEGER NOT NULL DEFAULT 3,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime')),
    UNIQUE(account_id)
);
CREATE TABLE IF NOT EXISTS review_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id BIGINT NOT NULL,
    order_id VARCHAR(100) NOT NULL,
    buyer_user_id VARCHAR(100),
    rule_id INTEGER,
    review_text TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    scheduled_at DATETIME,
    submitted_at DATETIME,
    retry_count INTEGER NOT NULL DEFAULT 0,
    job_id INTEGER,
    last_error TEXT,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime')),
    UNIQUE(account_id, order_id)
);
CREATE INDEX IF NOT EXISTS idx_review_record_account_status ON review_record(account_id, status);
CREATE INDEX IF NOT EXISTS idx_review_record_scheduled_at ON review_record(scheduled_at);

-- 智能议价：会话级议价轮次跟踪（与 db/migration V3_22 一致）
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

-- 商品发布历史表（与 db/migration V3_1 一致）
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
CREATE INDEX IF NOT EXISTS idx_publish_history_account ON xianyu_publish_history(account_id);
CREATE INDEX IF NOT EXISTS idx_publish_history_created ON xianyu_publish_history(created_time);

-- 定时任务表（与 db/migration V3_2 一致）
CREATE TABLE IF NOT EXISTS xianyu_schedule_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_name VARCHAR(100) NOT NULL,           -- 任务名称
    task_type VARCHAR(30) NOT NULL,            -- SYNC_GOODS / PUBLISH / OFF_SHELF
    cron_expr VARCHAR(100) NOT NULL,           -- Cron 表达式
    account_id BIGINT,                         -- 关联账号ID
    config_json TEXT,                          -- 任务配置（JSON）
    enabled TINYINT DEFAULT 1,                 -- 是否启用
    paused TINYINT DEFAULT 0,                  -- 连续失败自动暂停
    fail_count INTEGER DEFAULT 0,              -- 连续失败次数
    last_run_time DATETIME,                    -- 最近一次执行时间
    last_run_status VARCHAR(20),               -- SUCCESS / FAILED
    last_error TEXT,                           -- 最近一次失败原因
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_schedule_task_enabled ON xianyu_schedule_task(enabled);
CREATE INDEX IF NOT EXISTS idx_schedule_task_account ON xianyu_schedule_task(account_id);

-- 商品价格历史表（与 db/migration V3_4 一致）
CREATE TABLE IF NOT EXISTS xianyu_goods_price_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xy_good_id VARCHAR(100) NOT NULL,              -- 闲鱼商品ID
    xianyu_account_id BIGINT,                      -- 关联账号
    price DECIMAL(10, 2),                          -- 快照价格
    snapshot_time DATETIME DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_price_history_good ON xianyu_goods_price_history(xy_good_id, snapshot_time);

-- Webhook 配置表（与 db/migration V3_8 一致；secret 列由加密 TypeHandler 读写）
CREATE TABLE IF NOT EXISTS webhook_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(100) NOT NULL,               -- Webhook 名称
    url VARCHAR(500) NOT NULL,                -- 回调 URL
    events TEXT NOT NULL,                     -- 监听事件列表（JSON数组）
    enabled TINYINT DEFAULT 1,                -- 是否启用
    secret VARCHAR(200),                      -- HMAC 签名密钥（AES-GCM 密文）
    retry_count INTEGER DEFAULT 3,            -- 失败重试次数
    timeout_seconds INTEGER DEFAULT 10,       -- 超时时间
    last_trigger_at DATETIME,                 -- 最近一次触发时间
    last_trigger_status VARCHAR(20),          -- SUCCESS / FAILED
    last_error TEXT,                          -- 最近一次错误信息
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_webhook_enabled ON webhook_config(enabled);
CREATE TRIGGER IF NOT EXISTS update_webhook_config_time
AFTER UPDATE ON webhook_config
BEGIN
    UPDATE webhook_config SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

-- 统一任务引擎表（与 db/migration V3_7 一致）
CREATE TABLE IF NOT EXISTS job_definition (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_type VARCHAR(50) NOT NULL,             -- 任务类型
    idempotency_key VARCHAR(100) NOT NULL,      -- 幂等键
    priority INTEGER DEFAULT 0,
    status VARCHAR(20) DEFAULT 'PENDING',       -- PENDING/RUNNING/SUCCESS/FAILED/CANCELLED/DEAD_LETTER
    params_json TEXT,                           -- 任务参数（JSON）
    account_id BIGINT,                          -- 关联账号ID
    max_retries INTEGER DEFAULT 3,
    retry_count INTEGER DEFAULT 0,
    last_error TEXT,
    next_run_time DATETIME,
    lease_until DATETIME,                       -- RUNNING lease（V3_16）
    schedule_cron VARCHAR(100),
    created_by BIGINT,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);
CREATE TABLE IF NOT EXISTS job_execution (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL,
    attempt INTEGER DEFAULT 1,
    status VARCHAR(20) DEFAULT 'RUNNING',
    started_at DATETIME,
    finished_at DATETIME,
    error_message TEXT,
    result_json TEXT,
    worker_id VARCHAR(100),
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (job_id) REFERENCES job_definition(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS job_dead_letter (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL,
    job_type VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    params_json TEXT,
    account_id BIGINT,
    max_retries INTEGER,
    final_retry_count INTEGER,
    last_error TEXT,
    dead_at DATETIME DEFAULT (datetime('now', 'localtime')),
    resolved TINYINT DEFAULT 0,
    resolved_by BIGINT,
    resolved_at DATETIME,
    resolution_note TEXT
);
CREATE TABLE IF NOT EXISTS delivery_compensation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id VARCHAR(100) NOT NULL,
    xianyu_account_id BIGINT NOT NULL,
    xy_goods_id VARCHAR(100) NOT NULL,
    delivery_type VARCHAR(50),
    delivery_content TEXT,
    phase VARCHAR(30) DEFAULT 'UNKNOWN',
    idempotency_key VARCHAR(100) UNIQUE,
    external_request_id VARCHAR(100),
    unknown_since DATETIME,
    resolved_by VARCHAR(50),
    resolved_at DATETIME,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_job_def_type ON job_definition(job_type);
CREATE INDEX IF NOT EXISTS idx_job_def_status ON job_definition(status);
CREATE INDEX IF NOT EXISTS idx_job_def_next_run ON job_definition(next_run_time);
CREATE INDEX IF NOT EXISTS idx_job_def_account ON job_definition(account_id);
CREATE INDEX IF NOT EXISTS idx_job_exec_job_id ON job_execution(job_id);
CREATE INDEX IF NOT EXISTS idx_job_dead_letter_resolved ON job_dead_letter(resolved);
CREATE INDEX IF NOT EXISTS idx_delivery_comp_order ON delivery_compensation(order_id);
CREATE INDEX IF NOT EXISTS idx_delivery_comp_phase ON delivery_compensation(phase);
CREATE TRIGGER IF NOT EXISTS update_job_definition_time
AFTER UPDATE ON job_definition
BEGIN
    UPDATE job_definition SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;
CREATE TRIGGER IF NOT EXISTS update_delivery_compensation_time
AFTER UPDATE ON delivery_compensation
BEGIN
    UPDATE delivery_compensation SET updated_time = datetime('now', 'localtime') WHERE id = NEW.id;
END;

-- 账号级风控表（与 db/migration V3_10 一致）
CREATE TABLE IF NOT EXISTS account_risk_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    event_type VARCHAR(50) NOT NULL,          -- RISK_CONTROL / RATE_LIMITED / MANUAL_PAUSE / RESUME
    reason VARCHAR(500),
    occurred_at DATETIME NOT NULL DEFAULT (datetime('now', 'localtime')),
    created_time DATETIME DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_risk_event_account_time ON account_risk_event(account_id, occurred_at);
CREATE TABLE IF NOT EXISTS account_automation_pause (
    account_id INTEGER PRIMARY KEY,
    paused INTEGER NOT NULL DEFAULT 0,
    pause_reason VARCHAR(500),
    paused_at DATETIME,
    paused_by INTEGER,
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

-- 自动回复决策日志（与 db/migration V3_23 一致）
CREATE TABLE IF NOT EXISTS auto_reply_decision (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    xianyu_account_id BIGINT NOT NULL,
    s_id VARCHAR(200),
    xy_goods_id VARCHAR(100),
    buyer_user_id VARCHAR(100),
    buyer_message TEXT,
    reason_code VARCHAR(50) NOT NULL,
    detail VARCHAR(500),
    create_time DATETIME DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_auto_reply_decision_time ON auto_reply_decision(create_time DESC);
CREATE INDEX IF NOT EXISTS idx_auto_reply_decision_session ON auto_reply_decision(xianyu_account_id, s_id, reason_code, create_time);
