<script setup lang="ts">
import { useAccountManager } from './useAccountManager'
import AccountTable from './components/AccountTable.vue'
import AddAccountDialog from './components/AddAccountDialog.vue'
import ManualAddDialog from './components/ManualAddDialog.vue'
import QRLoginDialog from './components/QRLoginDialog.vue'
import DeleteConfirmDialog from './components/DeleteConfirmDialog.vue'
import UiButton from '@/components/ui/UiButton.vue'
import UiPageShell from '@/components/ui/UiPageShell.vue'

import IconQrCode from '@/components/icons/IconQrCode.vue'
import IconPlus from '@/components/icons/IconPlus.vue'
import IconSync from '@/components/icons/IconSync.vue'

const {
  loading,
  accounts,
  dialogs,
  currentAccount,
  deleteAccountId,
  loadAccounts,
  showManualAddDialog,
  showQRLoginDialog,
  editAccount,
  deleteAccount
} = useAccountManager();

loadAccounts();
</script>

<template>
  <UiPageShell
    class="accounts"
    title="闲鱼账号"
    subtitle="管理扫码登录与手动录入的闲鱼账号"
    compact
  >
    <template #actions>
      <div class="accounts__actions desktop-only">
        <UiButton variant="primary" @click="showQRLoginDialog">
          <IconQrCode />
          <span>扫码添加</span>
        </UiButton>
        <UiButton variant="secondary" @click="showManualAddDialog">
          <IconPlus />
          <span>手动添加</span>
        </UiButton>
        <UiButton
          variant="ghost"
          :loading="loading"
          :disabled="loading"
          @click="loadAccounts"
        >
          <IconSync />
          <span>刷新</span>
        </UiButton>
      </div>
    </template>

    <section class="accounts__content">
      <div class="accounts__table-wrap">
        <AccountTable
          :accounts="accounts"
          :loading="loading"
          @edit="editAccount"
          @delete="deleteAccount"
        />
      </div>
    </section>

    <template #footer>
      <UiButton variant="primary" block @click="showQRLoginDialog">
        <IconQrCode />
        <span>扫码添加</span>
      </UiButton>
      <UiButton variant="secondary" block @click="showManualAddDialog">
        <IconPlus />
        <span>手动添加</span>
      </UiButton>
    </template>

    <AddAccountDialog
      v-model="dialogs.add"
      :account="currentAccount"
      @success="loadAccounts"
    />
    <ManualAddDialog v-model="dialogs.manualAdd" @success="loadAccounts" />
    <QRLoginDialog v-model="dialogs.qrLogin" @success="loadAccounts" />
    <DeleteConfirmDialog
      v-model="dialogs.deleteConfirm"
      :account-id="deleteAccountId"
      @success="loadAccounts"
    />
  </UiPageShell>
</template>

<style scoped src="./accounts.css"></style>
