import { request } from '@/utils/request'

// RBAC 类型定义
export interface SysRole {
  id: number
  name: string
  description: string
  roleType: string   // SYSTEM / CUSTOM
  status: number
  createdTime: string
  updatedTime: string
}

export interface SysPermission {
  id: number
  code: string
  name: string
  module: string
  action: string
  description: string
  status: number
  createdTime: string
  updatedTime: string
}

export interface MyPermissions {
  permissions: string[]
  roles: string[]
}

/** 获取当前用户权限 */
export function getMyPermissions() {
  return request<MyPermissions>({
    url: '/rbac/my-permissions',
    method: 'GET'
  })
}

/** 获取角色列表 */
export function getRoleList() {
  return request<SysRole[]>({
    url: '/rbac/roles',
    method: 'GET'
  })
}

/** 获取单个角色 */
export function getRole(id: number) {
  return request<SysRole>({
    url: `/rbac/roles/${id}`,
    method: 'GET'
  })
}

/** 创建角色 */
export function createRole(data: Partial<SysRole>) {
  return request<SysRole>({
    url: '/rbac/roles',
    method: 'POST',
    data
  })
}

/** 更新角色 */
export function updateRole(data: Partial<SysRole>) {
  return request<void>({
    url: '/rbac/roles',
    method: 'PUT',
    data
  })
}

/** 删除角色 */
export function deleteRole(id: number) {
  return request<void>({
    url: `/rbac/roles/${id}`,
    method: 'DELETE'
  })
}

/** 获取角色的权限 ID 列表 */
export function getRolePermissions(roleId: number) {
  return request<number[]>({
    url: `/rbac/roles/${roleId}/permissions`,
    method: 'GET'
  })
}

/** 设置角色的权限 */
export function setRolePermissions(roleId: number, permissionIds: number[]) {
  return request<void>({
    url: `/rbac/roles/${roleId}/permissions`,
    method: 'PUT',
    data: permissionIds
  })
}

/** 获取全部权限列表 */
export function getPermissionList() {
  return request<SysPermission[]>({
    url: '/rbac/permissions',
    method: 'GET'
  })
}

/** 获取用户的角色 ID 列表 */
export function getUserRoles(userId: number) {
  return request<number[]>({
    url: `/rbac/users/${userId}/roles`,
    method: 'GET'
  })
}

/** 设置用户的角色 */
export function setUserRoles(userId: number, roleIds: number[]) {
  return request<void>({
    url: `/rbac/users/${userId}/roles`,
    method: 'PUT',
    data: roleIds
  })
}

/** 获取用户可见账号范围（P0-1 账号数据隔离） */
export function getUserAccountScope(userId: number) {
  return request<number[]>({
    url: `/rbac/users/${userId}/accounts`,
    method: 'GET'
  })
}

/** 设置用户可见账号范围（整体替换，空数组表示清除限制） */
export function setUserAccountScope(userId: number, accountIds: number[]) {
  return request<void>({
    url: `/rbac/users/${userId}/accounts`,
    method: 'PUT',
    data: accountIds
  })
}
