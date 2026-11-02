import { request } from '@/utils/request'
import type { Account } from '@/types'

// 获取账号列表
export function getAccountList() {
  return request<{ accounts: Account[]; total?: number }>({
    url: '/account/list',
    method: 'POST',
    data: {}
  })
}

// 添加账号
export function addAccount(data: Partial<Account>) {
  return request({
    url: '/account/add',
    method: 'POST',
    data
  })
}

// 更新账号
export function updateAccount(data: Partial<Account>) {
  return request({
    url: '/account/update',
    method: 'POST',
    data
  })
}

// 刷新账号的闲鱼资料（昵称/头像/信用分）
export function refreshAccountProfile(id: number) {
  return request({
    url: '/account/refreshProfile',
    method: 'POST',
    data: { accountId: id }
  })
}

// 删除账号
export function deleteAccount(data: { id: number }) {
  return request({
    url: '/account/delete',
    method: 'POST',
    data: {
      accountId: data.id
    }
  })
}

// 手动添加账号
export function manualAddAccount(data: { accountNote: string; cookie: string }) {
  return request({
    url: '/account/manualAdd',
    method: 'POST',
    data
  })
}


