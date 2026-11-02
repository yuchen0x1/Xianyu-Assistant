-- ============================================================
-- V3_9：补齐业务接口权限码（P0 安全）
-- 对应版本：v3.3.0（应用版本）→ schema 版本 V3_9
-- 幂等：INSERT 使用 OR IGNORE
-- ============================================================

-- 1. 新增权限码
INSERT OR IGNORE INTO sys_permission(code, name, module, action, description) VALUES
('webhook:read', '查看 Webhook', 'webhook', 'read', '查看 Webhook 配置和投递记录'),
('webhook:write', '管理 Webhook', 'webhook', 'write', '新增/编辑/测试/删除 Webhook'),
('jobs:read', '查看任务中心', 'jobs', 'read', '查看任务列表和详情'),
('jobs:admin', '管理任务', 'jobs', 'admin', '启停/重试/删除任务'),
('notification:read', '查看通知', 'notification', 'read', '查看通知列表'),
('notification:write', '处理通知', 'notification', 'write', '标记已读等通知操作'),
('backup:read', '查看备份', 'backup', 'read', '查看数据备份列表'),
('backup:write', '管理备份', 'backup', 'write', '创建/恢复/删除备份'),
('system:read', '查看系统信息', 'system', 'read', '查看系统运行信息'),
('log:write', '清理日志', 'log', 'write', '删除过期操作日志');

-- 2. ADMIN 分配全部新权限
INSERT OR IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.name = 'ADMIN' AND p.code IN (
    'webhook:read', 'webhook:write', 'jobs:read', 'jobs:admin',
    'notification:read', 'notification:write', 'backup:read', 'backup:write',
    'system:read', 'log:write'
);

-- 3. OPERATOR 分配读+写（jobs:admin 属于管理操作，仅 ADMIN）
INSERT OR IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.name = 'OPERATOR' AND p.code IN (
    'webhook:read', 'webhook:write', 'jobs:read',
    'notification:read', 'notification:write', 'backup:read',
    'system:read', 'log:write'
);

-- 4. VIEWER 仅读
INSERT OR IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.name = 'VIEWER' AND p.code IN (
    'webhook:read', 'jobs:read', 'notification:read', 'backup:read', 'system:read'
);
