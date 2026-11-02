-- 账号资料补充运营统计字段：卖出件数 / 粉丝 / 关注。
-- 数据来自首页导航接口（mtop.idle.web.user.page.nav）的 module.base 区块，
-- 由 XianyuUserProfileService 在扫码登录与手动刷新时回填。

ALTER TABLE xianyu_account ADD COLUMN sold_count INTEGER;
ALTER TABLE xianyu_account ADD COLUMN followers INTEGER;
ALTER TABLE xianyu_account ADD COLUMN following INTEGER;
