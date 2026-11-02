-- 账号资料字段 + ID 回收
-- 1) 新增闲鱼资料字段（昵称/头像/信用分），扫码登录与手动刷新时回填。
--
-- 2) ID 回收：SQLite 的 AUTOINCREMENT 把历史最大 ID 永久记入 sqlite_sequence，
--    删除账号后新账号 ID 不会复用。曾尝试重建父表去掉 AUTOINCREMENT，但多张子表
--    通过 FOREIGN KEY(NO ACTION) 引用本表，DROP 父表的隐式 DELETE 在
--    foreign_keys=on 下必然违规，重建方案不可行。改为清理计数器：删除该行后，
--    AUTOINCREMENT 的"历史最大 ID"记忆被重置为当前 max(id)，删除最大 ID 的账号后
--    新账号即可复用其 ID。应用层在删除账号时也会执行同样的清理。

ALTER TABLE xianyu_account ADD COLUMN nickname VARCHAR(200);
ALTER TABLE xianyu_account ADD COLUMN avatar_url VARCHAR(500);
ALTER TABLE xianyu_account ADD COLUMN credit_score INTEGER;

DELETE FROM sqlite_sequence WHERE name = 'xianyu_account';
