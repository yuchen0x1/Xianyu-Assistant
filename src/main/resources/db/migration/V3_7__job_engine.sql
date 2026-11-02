-- ============================================================
-- V3_7：v3.3.0 统一任务引擎 + 幂等重试 + 死信队列（P0-3）
-- 对应版本：v3.3.0（应用版本）→ schema 版本 V3_7
-- 幂等：所有 DDL 使用 IF NOT EXISTS，INSERT 使用 OR IGNORE
-- ============================================================

-- 1. 任务定义表（每个任务一条定义，可多次调度）
CREATE TABLE IF NOT EXISTS job_definition (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_type VARCHAR(50) NOT NULL,                    -- 任务类型: DELIVERY / AUTO_REPLY / TOKEN_REFRESH / PENDING_ORDER_POLL / SYNC_GOODS / OFF_SHELF / CUSTOM
    idempotency_key VARCHAR(100) NOT NULL,             -- 幂等键: {type}:{accountId}:{entityId}:{action}
    priority INTEGER DEFAULT 0,                       -- 优先级: 越大越优先
    status VARCHAR(20) DEFAULT 'PENDING',             -- PENDING / RUNNING / SUCCESS / FAILED / CANCELLED / DEAD_LETTER
    params_json TEXT,                                  -- 任务参数（JSON）
    account_id BIGINT,                                 -- 关联账号ID（可为空）
    max_retries INTEGER DEFAULT 3,                    -- 最大重试次数
    retry_count INTEGER DEFAULT 0,                    -- 已重试次数
    last_error TEXT,                                   -- 最近一次错误信息
    next_run_time DATETIME,                           -- 下次执行时间（用于延迟和重试退避）
    schedule_cron VARCHAR(100),                        -- Cron表达式（定时任务时使用）
    created_by BIGINT,                                 -- 创建人用户ID
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

-- 2. 任务执行记录表（每次执行一条记录）
CREATE TABLE IF NOT EXISTS job_execution (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL,                           -- 关联任务定义ID
    attempt INTEGER DEFAULT 1,                         -- 第几次执行
    status VARCHAR(20) DEFAULT 'RUNNING',              -- RUNNING / SUCCESS / FAILED
    started_at DATETIME,                               -- 开始时间
    finished_at DATETIME,                              -- 完成时间
    error_message TEXT,                                -- 错误信息
    result_json TEXT,                                  -- 执行结果（JSON）
    worker_id VARCHAR(100),                            -- 执行者标识
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (job_id) REFERENCES job_definition(id) ON DELETE CASCADE
);

-- 3. 死信表（超出最大重试次数的任务归档至此）
CREATE TABLE IF NOT EXISTS job_dead_letter (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL,                           -- 原任务ID
    job_type VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    params_json TEXT,
    account_id BIGINT,
    max_retries INTEGER,
    final_retry_count INTEGER,
    last_error TEXT,
    dead_at DATETIME DEFAULT (datetime('now', 'localtime')),
    resolved TINYINT DEFAULT 0,                        -- 0:未处理 1:已人工处理
    resolved_by BIGINT,                                -- 处理人
    resolved_at DATETIME,                              -- 处理时间
    resolution_note TEXT                                -- 处理备注
);

-- 4. 发货补偿表（结果未知的订单记录，用于核对和补偿）
CREATE TABLE IF NOT EXISTS delivery_compensation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id VARCHAR(100) NOT NULL,                    -- 闲鱼订单ID
    xianyu_account_id BIGINT NOT NULL,
    xy_goods_id VARCHAR(100) NOT NULL,
    delivery_type VARCHAR(50),                         -- 发货类型: TEXT / IMAGE / KAMI / CUSTOM
    delivery_content TEXT,                             -- 发货内容快照
    phase VARCHAR(30) DEFAULT 'UNKNOWN',               -- 阶段: PRE_COMMIT / COMMITTED / CONFIRMED / UNKNOWN / RESOLVED
    idempotency_key VARCHAR(100) UNIQUE,               -- 幂等键
    external_request_id VARCHAR(100),                  -- 外部请求ID（闲鱼API请求ID）
    unknown_since DATETIME,                            -- 进入未知状态的时间
    resolved_by VARCHAR(50),                           -- 解决方式: MANUAL / AUTO_RETRY / CANCELLED
    resolved_at DATETIME,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

-- 5. 为 xianyu_goods_order 增加幂等字段
ALTER TABLE xianyu_goods_order ADD COLUMN idempotency_key VARCHAR(100);
ALTER TABLE xianyu_goods_order ADD COLUMN delivery_phase VARCHAR(30) DEFAULT 'NONE';
ALTER TABLE xianyu_goods_order ADD COLUMN unknown_since DATETIME;

-- 6. 索引
CREATE INDEX IF NOT EXISTS idx_job_def_type ON job_definition(job_type);
CREATE INDEX IF NOT EXISTS idx_job_def_status ON job_definition(status);
CREATE INDEX IF NOT EXISTS idx_job_def_next_run ON job_definition(next_run_time);
CREATE INDEX IF NOT EXISTS idx_job_def_idempotency ON job_definition(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_job_def_account ON job_definition(account_id);
CREATE INDEX IF NOT EXISTS idx_job_exec_job_id ON job_execution(job_id);
CREATE INDEX IF NOT EXISTS idx_job_dead_letter_resolved ON job_dead_letter(resolved);
CREATE INDEX IF NOT EXISTS idx_delivery_comp_order ON delivery_compensation(order_id);
CREATE INDEX IF NOT EXISTS idx_delivery_comp_phase ON delivery_compensation(phase);
CREATE INDEX IF NOT EXISTS idx_goods_order_idempotency ON xianyu_goods_order(idempotency_key);

-- 7. 触发器
CREATE TRIGGER IF NOT EXISTS update_job_definition_time
AFTER UPDATE ON job_definition
BEGIN
    UPDATE job_definition SET updated_time = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;
CREATE TRIGGER IF NOT EXISTS update_delivery_compensation_time
AFTER UPDATE ON delivery_compensation
BEGIN
    UPDATE delivery_compensation SET updated_time = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;

