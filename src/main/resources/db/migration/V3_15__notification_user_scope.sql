-- 通知用户隔离：为通知补充收件人、账号归属和广播标识
-- 历史通知的 recipient_user_id 为空，查询时按广播通知处理。
CREATE TABLE IF NOT EXISTS xianyu_notification (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    notify_type VARCHAR(50),
    level VARCHAR(20) DEFAULT 'INFO',
    read_status TINYINT DEFAULT 0,
    created_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

ALTER TABLE xianyu_notification ADD COLUMN recipient_user_id BIGINT;
ALTER TABLE xianyu_notification ADD COLUMN account_id BIGINT;
ALTER TABLE xianyu_notification ADD COLUMN broadcast TINYINT DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_notification_recipient_user
    ON xianyu_notification(recipient_user_id);
CREATE INDEX IF NOT EXISTS idx_notification_account
    ON xianyu_notification(account_id);
CREATE INDEX IF NOT EXISTS idx_notification_broadcast_recipient
    ON xianyu_notification(broadcast, recipient_user_id);

-- 广播通知按用户记录已读回执，避免共享通知行的 read_status 串用户。
CREATE TABLE IF NOT EXISTS notification_read_receipt (
    notification_id INTEGER NOT NULL,
    user_id BIGINT NOT NULL,
    read_time DATETIME DEFAULT (datetime('now', 'localtime')),
    PRIMARY KEY (notification_id, user_id),
    FOREIGN KEY (notification_id) REFERENCES xianyu_notification(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_notification_receipt_user ON notification_read_receipt(user_id);
