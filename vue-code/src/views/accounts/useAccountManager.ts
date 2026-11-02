import { ref, reactive } from 'vue'
import { getAccountList, deleteAccount as deleteAccountApi, refreshAccountProfile } from '@/api/account'
import { showSuccess, showError } from '@/utils'
import type { Account } from '@/types'

// 老账号资料缺失时的自动回填只尝试一轮，避免失败后循环重试
let profileBackfillAttempted = false

export function useAccountManager() {
  const loading = ref(false)
  const accounts = ref<Account[]>([])
  const profileRefreshing = ref(false)
  
  const dialogs = reactive({
    add: false,
    manualAdd: false,
    qrLogin: false,
    deleteConfirm: false
  })
  
  const currentAccount = ref<Account | null>(null)
  const deleteAccountId = ref<number | null>(null)

  // 加载账号列表
  const loadAccounts = async () => {
    loading.value = true;
    try {
      const response = await getAccountList();
      if (response.code === 0 || response.code === 200) {
        // 后端返回格式: { code: 200, data: { accounts: [...] } }
        accounts.value = response.data?.accounts || [];
        void backfillMissingProfiles();
      } else {
        throw new Error(response.msg || '获取账号列表失败');
      }
    } catch (error) {
      // 只有在错误消息未显示过时才弹出提示（避免重复显示）
      if (!(error as Error & { messageShown?: boolean }).messageShown) {
        showError('加载账号列表失败: ' + (error as Error).message);
      }
      accounts.value = [];
    } finally {
      loading.value = false;
    }
  };

  // 老账号升级后资料为空：后台逐个拉取闲鱼资料，完成后静默刷新一次列表
  const backfillMissingProfiles = async () => {
    if (profileBackfillAttempted || profileRefreshing.value) return
    const missing = accounts.value.filter(a => !a.nickname).slice(0, 5)
    if (missing.length === 0) return
    profileBackfillAttempted = true
    profileRefreshing.value = true
    try {
      let updated = false
      for (const account of missing) {
        try {
          const response = await refreshAccountProfile(account.id)
          if (response.code === 0 || response.code === 200) updated = true
        } catch {
          // 单个账号失败不影响其余回填
        }
      }
      if (updated) {
        const response = await getAccountList()
        if (response.code === 0 || response.code === 200) {
          accounts.value = response.data?.accounts || []
        }
      }
    } finally {
      profileRefreshing.value = false
    }
  };

  // 显示添加对话框
  const showAddDialog = () => {
    currentAccount.value = null;
    dialogs.add = true;
  };

  // 显示手动添加对话框
  const showManualAddDialog = () => {
    dialogs.manualAdd = true;
  };

  // 显示扫码登录对话框
  const showQRLoginDialog = () => {
    dialogs.qrLogin = true;
  };

  // 编辑账号
  const editAccount = (account: Account) => {
    currentAccount.value = account;
    dialogs.add = true;
  };

  // 删除账号
  const deleteAccount = (id: number) => {
    deleteAccountId.value = id;
    dialogs.deleteConfirm = true;
  };

  // 确认删除账号
  const confirmDelete = async () => {
    if (!deleteAccountId.value) return;
    
    try {
      const response = await deleteAccountApi({ id: deleteAccountId.value });
      if (response.code === 0 || response.code === 200) {
        showSuccess('账号删除成功');
        dialogs.deleteConfirm = false;
        await loadAccounts();
      } else {
        throw new Error(response.msg || '删除失败');
      }
    } catch (error) {
      // 只有在错误消息未显示过时才弹出提示（避免重复显示）
      if (!(error as Error & { messageShown?: boolean }).messageShown) {
        showError('删除失败: ' + (error as Error).message);
      }
    }
  };

  return {
    loading,
    accounts,
    dialogs,
    currentAccount,
    deleteAccountId,
    loadAccounts,
    showAddDialog,
    showManualAddDialog,
    showQRLoginDialog,
    editAccount,
    deleteAccount,
    confirmDelete
  }
}