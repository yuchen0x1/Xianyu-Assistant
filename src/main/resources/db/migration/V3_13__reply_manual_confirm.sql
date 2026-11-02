-- AI回复人工确认模式：AI 生成草稿后不自动发送，等待人工在界面确认/编辑后再发送
ALTER TABLE xianyu_goods_config ADD COLUMN reply_manual_confirm_on TINYINT DEFAULT 0;
