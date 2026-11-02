-- ============================================================
-- V3_6：v3.3.0 RBAC 权限系统（P0-1）
-- 对应版本：v3.3.0（应用版本）→ schema 版本 V3_6
-- 幂等：所有 DDL 使用 IF NOT EXISTS，INSERT 使用 OR IGNORE
-- ============================================================

-- 1. 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(200),
    role_type VARCHAR(20) DEFAULT 'CUSTOM',       -- SYSTEM / CUSTOM
    status TINYINT DEFAULT 1,                      -- 1:启用 0:禁用
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

-- 2. 权限表
CREATE TABLE IF NOT EXISTS sys_permission (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    module VARCHAR(50) NOT NULL,
    action VARCHAR(50) NOT NULL,                   -- read / write / admin / execute
    description VARCHAR(200),
    status TINYINT DEFAULT 1,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    updated_time DATETIME DEFAULT (datetime('now', 'localtime'))
);

-- 3. 角色-权限关联
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    role_id INTEGER NOT NULL,
    permission_id INTEGER NOT NULL,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (role_id) REFERENCES sys_role(id) ON DELETE CASCADE,
    FOREIGN KEY (permission_id) REFERENCES sys_permission(id) ON DELETE CASCADE,
    UNIQUE(role_id, permission_id)
);

-- 4. 用户-角色关联
CREATE TABLE IF NOT EXISTS sys_user_role (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    role_id INTEGER NOT NULL,
    created_time DATETIME DEFAULT (datetime('now', 'localtime')),
    FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES sys_role(id) ON DELETE CASCADE,
    UNIQUE(user_id, role_id)
);

-- 5. sys_user 补充字段（向后兼容）
ALTER TABLE sys_user ADD COLUMN display_name VARCHAR(100);
ALTER TABLE sys_user ADD COLUMN email VARCHAR(200);

-- 6. 索引
CREATE INDEX IF NOT EXISTS idx_role_permission_role_id ON sys_role_permission(role_id);
CREATE INDEX IF NOT EXISTS idx_role_permission_permission_id ON sys_role_permission(permission_id);
CREATE INDEX IF NOT EXISTS idx_user_role_user_id ON sys_user_role(user_id);
CREATE INDEX IF NOT EXISTS idx_user_role_role_id ON sys_user_role(role_id);
CREATE INDEX IF NOT EXISTS idx_permission_module ON sys_permission(module);

-- 7. 触发器
CREATE TRIGGER IF NOT EXISTS update_sys_role_time
AFTER UPDATE ON sys_role
BEGIN
    UPDATE sys_role SET updated_time = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;
CREATE TRIGGER IF NOT EXISTS update_sys_permission_time
AFTER UPDATE ON sys_permission
BEGIN
    UPDATE sys_permission SET updated_time = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;

-- 8. 初始化系统预置权限
INSERT OR IGNORE INTO sys_permission(code, name, module, action, description) VALUES
('dashboard:read', '查看数据面板', 'dashboard', 'read', '查看仪表盘和数据概览'),
('account:read', '查看账号', 'account', 'read', '查看闲鱼账号列表和详情'),
('account:write', '管理账号', 'account', 'write', '添加/编辑/删除闲鱼账号'),
('account:admin', '账号管理', 'account', 'admin', '账号高级管理操作'),
('goods:read', '查看商品', 'goods', 'read', '查看商品列表和详情'),
('goods:write', '管理商品', 'goods', 'write', '添加/编辑商品'),
('goods:admin', '商品管理', 'goods', 'admin', '商品上架/下架等管理操作'),
('order:read', '查看订单', 'order', 'read', '查看订单和发货记录'),
('order:write', '处理订单', 'order', 'write', '确认发货等订单操作'),
('message:read', '查看消息', 'message', 'read', '查看消息会话'),
('message:write', '发送消息', 'message', 'write', '发送回复消息'),
('auto-delivery:read', '查看自动发货', 'auto-delivery', 'read', '查看自动发货配置'),
('auto-delivery:write', '配置自动发货', 'auto-delivery', 'write', '修改自动发货配置'),
('auto-reply:read', '查看自动回复', 'auto-reply', 'read', '查看自动回复规则'),
('auto-reply:write', '配置自动回复', 'auto-reply', 'write', '修改自动回复规则'),
('kami:read', '查看卡密', 'kami', 'read', '查看卡密仓库'),
('kami:write', '管理卡密', 'kami', 'write', '导入/导出/编辑卡密'),
('publish:read', '查看发布', 'publish', 'read', '查看发布记录'),
('publish:write', '发布商品', 'publish', 'write', '发布/下架商品'),
('settings:read', '查看设置', 'settings', 'read', '查看系统设置'),
('settings:write', '修改设置', 'settings', 'write', '修改系统设置'),
('settings:admin', '系统管理', 'settings', 'admin', 'AI配置、备份恢复等高级管理'),
('log:read', '查看日志', 'log', 'read', '查看操作日志'),
('commands:read', '查看命令中心', 'commands', 'read', '查看命令中心'),
('commands:execute', '执行命令', 'commands', 'execute', '执行系统命令'),
('user:read', '查看用户', 'user', 'read', '查看用户列表'),
('user:write', '管理用户', 'user', 'write', '创建/编辑/删除用户'),
('user:admin', '用户管理', 'user', 'admin', '分配角色等高级用户管理');

-- 9. 初始化系统预置角色
INSERT OR IGNORE INTO sys_role(name, description, role_type, status) VALUES
('ADMIN', '管理员 - 拥有所有权限', 'SYSTEM', 1),
('OPERATOR', '运营者 - 日常运营操作权限', 'SYSTEM', 1),
('VIEWER', '观察者 - 仅查看权限', 'SYSTEM', 1);

-- 10. ADMIN 分配所有权限
INSERT OR IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p WHERE r.name = 'ADMIN';

-- 11. OPERATOR 分配运营权限（排除管理类）
INSERT OR IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.name = 'OPERATOR' AND p.code NOT IN (
    'settings:admin', 'user:admin', 'user:write', 'user:read', 'commands:execute',
    'account:admin', 'goods:admin', 'publish:write'
);

-- 12. VIEWER 仅读权限
INSERT OR IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.name = 'VIEWER' AND p.action = 'read';

-- 13. 向后兼容：已有用户自动获得 ADMIN 角色
INSERT OR IGNORE INTO sys_user_role(user_id, role_id)
SELECT u.id, r.id FROM sys_user u, sys_role r WHERE r.name = 'ADMIN';
