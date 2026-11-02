package com.feijimiao.xianyuassistant.rbac.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.exception.BusinessException;
import com.feijimiao.xianyuassistant.rbac.entity.*;
import com.feijimiao.xianyuassistant.rbac.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RBAC 权限校验与角色管理核心服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RbacService {

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserAccountScopeMapper accountScopeMapper;

    // ========== 权限校验 ==========

    /**
     * 检查用户是否拥有指定权限（满足任一即可）
     */
    public boolean hasAnyPermission(Long userId, String... permissionCodes) {
        if (userId == null || permissionCodes.length == 0) return false;
        Set<String> userPerms = getUserPermissionCodes(userId);
        for (String code : permissionCodes) {
            if (userPerms.contains(code)) return true;
        }
        return false;
    }

    /**
     * 检查用户是否拥有全部指定权限
     */
    public boolean hasAllPermissions(Long userId, String... permissionCodes) {
        if (userId == null || permissionCodes.length == 0) return false;
        Set<String> userPerms = getUserPermissionCodes(userId);
        for (String code : permissionCodes) {
            if (!userPerms.contains(code)) return false;
        }
        return true;
    }

    /**
     * 获取用户的所有权限编码
     */
    public Set<String> getUserPermissionCodes(Long userId) {
        Set<String> codes = new HashSet<>();
        // 查用户关联的角色
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) return codes;
        // 查角色的所有权限
        Set<Long> assignedRoleIds = userRoles.stream()
                .map(SysUserRole::getRoleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (assignedRoleIds.isEmpty()) return codes;
        List<SysRole> activeRoles = roleMapper.selectList(
                new LambdaQueryWrapper<SysRole>()
                        .in(SysRole::getId, assignedRoleIds)
                        .eq(SysRole::getStatus, 1));
        Set<Long> roleIds = activeRoles.stream()
                .map(SysRole::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (roleIds.isEmpty()) return codes;
        List<SysRolePermission> rps = rolePermissionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysRolePermission>()
                        .in(SysRolePermission::getRoleId, roleIds));
        if (rps.isEmpty()) return codes;
        Set<Long> permIds = rps.stream().map(SysRolePermission::getPermissionId).collect(Collectors.toSet());
        List<SysPermission> perms = permissionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysPermission>()
                        .in(SysPermission::getId, permIds)
                        .eq(SysPermission::getStatus, 1));
        perms.forEach(p -> codes.add(p.getCode()));
        return codes;
    }

    /**
     * 获取用户的角色名称列表
     */
    public List<String> getUserRoleNames(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) return List.of();
        Set<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
        List<SysRole> roles = roleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysRole>()
                        .in(SysRole::getId, roleIds)
                        .eq(SysRole::getStatus, 1));
        return roles.stream().map(SysRole::getName).collect(Collectors.toList());
    }

    // ========== 角色管理 ==========

    public List<SysRole> listAllRoles() {
        return roleMapper.selectList(null);
    }

    public SysRole getRoleById(Long id) {
        return roleMapper.selectById(id);
    }

    @Transactional
    public SysRole createRole(SysRole role) {
        role.setRoleType("CUSTOM");
        role.setStatus(1);
        roleMapper.insert(role);
        return role;
    }

    @Transactional
    public void updateRole(SysRole role) {
        if (role == null || role.getId() == null) {
            throw new BusinessException("角色 ID 不能为空");
        }
        SysRole existing = roleMapper.selectById(role.getId());
        if (existing == null) {
            throw new BusinessException("角色不存在");
        }
        if ("SYSTEM".equals(existing.getRoleType())) {
            // 内置角色承载权限体系基线：改名会让 ADMIN 全量可见判定失效，禁用会让管理员失去权限
            if (role.getName() != null && !role.getName().equals(existing.getName())) {
                throw new BusinessException("内置角色不允许修改名称");
            }
            if (role.getStatus() != null && role.getStatus() != existing.getStatus()) {
                throw new BusinessException("内置角色不允许启用/禁用");
            }
            role.setRoleType(existing.getRoleType());
        }
        roleMapper.updateById(role);
    }

    @Transactional
    public void deleteRole(Long id) {
        SysRole existing = id == null ? null : roleMapper.selectById(id);
        if (existing != null && "SYSTEM".equals(existing.getRoleType())) {
            throw new BusinessException("内置角色不允许删除");
        }
        roleMapper.deleteById(id);
    }

    /** 获取角色的权限 ID 列表 */
    public List<Long> getRolePermissionIds(Long roleId) {
        List<SysRolePermission> list = rolePermissionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysRolePermission>()
                        .eq(SysRolePermission::getRoleId, roleId));
        return list.stream().map(SysRolePermission::getPermissionId).collect(Collectors.toList());
    }

    /** 设置角色的权限（全量替换） */
    @Transactional
    public void setRolePermissions(Long roleId, List<Long> permissionIds) {
        rolePermissionMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysRolePermission>()
                        .eq(SysRolePermission::getRoleId, roleId));
        if (permissionIds != null && !permissionIds.isEmpty()) {
            for (Long permId : permissionIds) {
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleId(roleId);
                rp.setPermissionId(permId);
                rolePermissionMapper.insert(rp);
            }
        }
    }

    // ========== 用户角色管理 ==========

    /** 获取用户的角色 ID 列表 */
    public List<Long> getUserRoleIds(Long userId) {
        List<SysUserRole> list = userRoleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId));
        return list.stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
    }

    /** 为指定用户分配系统预置角色（幂等，重复调用不会重复绑定） */
    @Transactional
    public void assignSystemRoleToUser(Long userId, String roleName) {
        if (userId == null || roleName == null || roleName.isBlank()) return;
        List<SysRole> roles = roleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getName, roleName)
                        .eq(SysRole::getStatus, 1));
        if (roles.isEmpty()) {
            log.warn("系统角色不存在或已禁用，跳过角色分配: role={}", roleName);
            return;
        }
        Set<Long> existing = new HashSet<>(getUserRoleIds(userId));
        for (SysRole role : roles) {
            if (existing.contains(role.getId())) continue;
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(role.getId());
            userRoleMapper.insert(userRole);
        }
    }

    /** 设置用户的角色（全量替换） */
    @Transactional
    public void setUserRoles(Long userId, List<Long> roleIds) {
        if (userId != null && userId.equals(UserContext.getUserId())) {
            // 防止用户移除自己的角色造成自锁（管理员误操作无恢复入口）
            throw new BusinessException("不能修改自己的角色");
        }
        userRoleMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId));
        if (roleIds != null && !roleIds.isEmpty()) {
            for (Long roleId : roleIds) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
        }
    }

    // ========== 权限查询 ==========

    public List<SysPermission> listAllPermissions() {
        return permissionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysPermission>()
                        .orderByAsc(SysPermission::getModule, SysPermission::getCode));
    }

    /** 获取当前用户的所有权限编码（供前端权限指令使用） */
    // ========== 账号数据隔离（P0-1） ==========

    /**
     * 获取用户可见账号范围（未配置返回空列表）。
     */
    public List<Long> getUserAccountScopeIds(Long userId) {
        if (userId == null) return List.of();
        return accountScopeMapper.selectList(
                new LambdaQueryWrapper<SysUserAccountScope>()
                        .eq(SysUserAccountScope::getUserId, userId))
                .stream().map(SysUserAccountScope::getAccountId).toList();
    }

    /**
     * 设置用户可见账号范围（整体替换，传空列表表示清除全部范围）。
     */
    @Transactional
    public void setUserAccountScope(Long userId, List<Long> accountIds) {
        if (userId == null) return;
        accountScopeMapper.delete(new LambdaQueryWrapper<SysUserAccountScope>()
                .eq(SysUserAccountScope::getUserId, userId));
        if (accountIds == null || accountIds.isEmpty()) return;
        for (Long accountId : accountIds) {
            if (accountId == null) continue;
            SysUserAccountScope scope = new SysUserAccountScope();
            scope.setUserId(userId);
            scope.setAccountId(accountId);
            accountScopeMapper.insert(scope);
        }
        log.info("已更新用户账号可见范围: userId={}, count={}", userId, accountIds.size());
    }

    /**
     * 计算用户在账号列表上的可见性（null 表示不限制）：
     * <ul>
     *   <li>ADMIN 角色：返回 null（全部可见）</li>
     *   <li>非 ADMIN 且未配置任何范围记录：返回空列表（<b>默认拒绝</b>）</li>
     *   <li>非 ADMIN 且已配置范围：返回允许的账号ID集合</li>
     * </ul>
     *
     * <p>历史上"未配置范围"被解释为全部可见（fail-open），导致新建的 VIEWER /
     * OPERATOR 在管理员忘记配置范围时等同拥有全量只读权限。现在改为
     * fail-closed：非管理员必须先被显式授予账号范围，否则看不到任何账号数据。
     * 调用方统一用 {@code visible == null} 判断"不限"，用
     * {@code visible.isEmpty()} 表达"无任何可见账号"。</p>
     *
     * <p>{@code userId == null} 仅出现在无请求上下文的内部调用（定时任务、
     * 系统自检），此时沿用 null 表示不限制，避免后台任务被误拦。</p>
     */
    public List<Long> getVisibleAccountIds(Long userId) {
        if (userId == null) return null;
        if (getUserRoleNames(userId).contains("ADMIN")) return null;
        return getUserAccountScopeIds(userId);
    }

    /**
     * 账号范围兜底校验：非管理员访问其可见范围之外的账号数据时抛 403。
     *
     * <p>与 AccountScopeAspect 的参数名拦截互补，覆盖按间接 ID（recordId/configId）
     * 或空账号参数直达的访问形态。accountId 为 null 时对受限用户拒绝
     * （空参意味着"全部账号"，不能放行全量枚举）。</p>
     */
    public void assertAccountVisible(Long userId, Long accountId) {
        List<Long> visible = getVisibleAccountIds(userId);
        if (visible == null) {
            return; // 管理员或无上下文的内部调用：不限制
        }
        if (accountId == null) {
            throw new BusinessException(403, visible.isEmpty()
                    ? "当前账号未配置可见账号范围，请联系管理员授权"
                    : "受限用户必须指定账号");
        }
        if (!visible.contains(accountId)) {
            throw new BusinessException(403, "无权访问该账号的数据");
        }
    }

    /**
     * 返回命令网关使用的账号可见范围。
     *
     * <p>与普通业务接口的历史兼容语义不同，命令调用对非管理员默认拒绝全量查询：
     * 未配置范围时返回空列表，而不是 {@code null}。</p>
     *
     * @return {@code null} 表示管理员可见全部账号；非管理员返回明确的允许列表
     */
    public List<Long> getCommandVisibleAccountIds(Long userId) {
        if (userId == null) return List.of();
        if (getUserRoleNames(userId).contains("ADMIN")) return null;
        return getUserAccountScopeIds(userId);
    }

    public List<String> getCurrentUserPermissions(Long userId) {
        return new ArrayList<>(getUserPermissionCodes(userId));
    }
}
