-- ============================================================
-- V3_12: 为历史注册的账号补齐 ADMIN 角色
-- 首次注册接口在 V3_6 之后注册的用户没有角色，导致登录后权限为空。
-- 幂等：已有任意角色的用户保持不变。
-- ============================================================

INSERT OR IGNORE INTO sys_user_role(user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r ON r.name = 'ADMIN'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id
);
