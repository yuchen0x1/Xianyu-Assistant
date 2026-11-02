package com.feijimiao.xianyuassistant.rbac.controller;

import com.feijimiao.xianyuassistant.common.ResultObject;
import com.feijimiao.xianyuassistant.context.UserContext;
import com.feijimiao.xianyuassistant.rbac.annotation.RequirePermission;
import com.feijimiao.xianyuassistant.rbac.entity.SysPermission;
import com.feijimiao.xianyuassistant.rbac.entity.SysRole;
import com.feijimiao.xianyuassistant.rbac.service.RbacService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RBAC 权限管理控制器
 */
@RestController
@RequestMapping("/api/rbac")
@RequiredArgsConstructor
public class RbacController {

    private final RbacService rbacService;

    // ========== 角色管理 ==========

    @GetMapping("/roles")
    @RequirePermission("user:read")
    public ResultObject<List<SysRole>> listRoles() {
        return ResultObject.success(rbacService.listAllRoles());
    }

    @GetMapping("/roles/{id}")
    @RequirePermission("user:read")
    public ResultObject<SysRole> getRole(@PathVariable Long id) {
        return ResultObject.success(rbacService.getRoleById(id));
    }

    @PostMapping("/roles")
    @RequirePermission("user:write")
    public ResultObject<SysRole> createRole(@RequestBody SysRole role) {
        return ResultObject.success(rbacService.createRole(role));
    }

    @PutMapping("/roles")
    @RequirePermission("user:write")
    public ResultObject<Void> updateRole(@RequestBody SysRole role) {
        rbacService.updateRole(role);
        return ResultObject.success(null);
    }

    @DeleteMapping("/roles/{id}")
    @RequirePermission("user:admin")
    public ResultObject<Void> deleteRole(@PathVariable Long id) {
        rbacService.deleteRole(id);
        return ResultObject.success(null);
    }

    /** 获取角色的权限 ID 列表 */
    @GetMapping("/roles/{id}/permissions")
    @RequirePermission("user:read")
    public ResultObject<List<Long>> getRolePermissions(@PathVariable Long id) {
        return ResultObject.success(rbacService.getRolePermissionIds(id));
    }

    /** 设置角色的权限 */
    @PutMapping("/roles/{id}/permissions")
    @RequirePermission("user:admin")
    public ResultObject<Void> setRolePermissions(@PathVariable Long id, @RequestBody List<Long> permissionIds) {
        rbacService.setRolePermissions(id, permissionIds);
        return ResultObject.success(null);
    }

    // ========== 权限查询 ==========

    @GetMapping("/permissions")
    @RequirePermission("user:read")
    public ResultObject<List<SysPermission>> listPermissions() {
        return ResultObject.success(rbacService.listAllPermissions());
    }

    // ========== 用户角色管理 ==========

    /** 获取用户的角色 ID 列表 */
    @GetMapping("/users/{userId}/roles")
    @RequirePermission("user:read")
    public ResultObject<List<Long>> getUserRoles(@PathVariable Long userId) {
        return ResultObject.success(rbacService.getUserRoleIds(userId));
    }

    /** 设置用户的角色 */
    @PutMapping("/users/{userId}/roles")
    @RequirePermission("user:admin")
    public ResultObject<Void> setUserRoles(@PathVariable Long userId, @RequestBody List<Long> roleIds) {
        rbacService.setUserRoles(userId, roleIds);
        return ResultObject.success(null);
    }

    // ========== 账号数据隔离（P0-1） ==========

    /** 获取用户可见账号范围 */
    @GetMapping("/users/{userId}/accounts")
    @RequirePermission("user:read")
    public ResultObject<List<Long>> getUserAccountScope(@PathVariable Long userId) {
        return ResultObject.success(rbacService.getUserAccountScopeIds(userId));
    }

    /** 设置用户可见账号范围（整体替换，空列表表示清除限制） */
    @PutMapping("/users/{userId}/accounts")
    @RequirePermission("user:admin")
    public ResultObject<Void> setUserAccountScope(@PathVariable Long userId,
                                                  @RequestBody List<Long> accountIds) {
        rbacService.setUserAccountScope(userId, accountIds);
        return ResultObject.success(null);
    }

    // ========== 当前用户权限 ==========

    /** 获取当前登录用户的权限列表（供前端动态控制） */
    @GetMapping("/my-permissions")
    public ResultObject<Map<String, Object>> getMyPermissions() {
        Long userId = UserContext.getUserId();
        List<String> perms = rbacService.getCurrentUserPermissions(userId);
        List<String> roles = rbacService.getUserRoleNames(userId);
        return ResultObject.success(Map.of(
                "permissions", perms,
                "roles", roles
        ));
    }
}


