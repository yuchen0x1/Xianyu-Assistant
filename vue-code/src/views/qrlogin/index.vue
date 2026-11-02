<template>
  <div class="qrlogin-page">
    <div class="qrlogin-card">
      <div class="qrlogin-badge">扫码登录</div>
      <h1>请从账号页发起扫码</h1>
      <p>独立扫码页已收敛到账号管理流程，避免出现不可用入口。</p>
      <router-link class="qrlogin-link" to="/accounts">前往闲鱼账号</router-link>
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'QrLoginView' })
</script>

<style scoped>
.qrlogin-page {
  min-height: 100%;
  display: grid;
  place-items: center;
  padding: 24px;
}

.qrlogin-card {
  width: min(100%, 480px);
  padding: 28px;
  position: relative;
  border-radius: var(--radius-md);
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  box-shadow: var(--glass-shadow);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
  overflow: hidden;
}

.qrlogin-card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  pointer-events: none;
  background: var(--glass-highlight);
}

.qrlogin-badge {
  display: inline-flex;
  min-height: 24px;
  align-items: center;
  padding: 0 10px;
  margin-bottom: 14px;
  color: var(--brand-700);
  font-size: 12px;
  font-weight: 700;
  border-radius: 999px;
  background: var(--brand-50);
}

.qrlogin-card h1 {
  margin: 0 0 8px;
  color: var(--text-1);
  font-size: 22px;
}

.qrlogin-card p {
  margin: 0 0 18px;
  color: var(--text-2);
  line-height: 1.6;
}

.qrlogin-link {
  display: inline-flex;
  align-items: center;
  min-height: 40px;
  padding: 0 14px;
  color: #fff;
  font-weight: 700;
  border-radius: var(--radius-sm);
  background: var(--brand-600);
  box-shadow: 0 8px 18px rgba(37, 99, 235, 0.22);
  text-decoration: none;
}

@supports not ((-webkit-backdrop-filter: blur(1px)) or (backdrop-filter: blur(1px))) {
  .qrlogin-card {
    background: var(--surface-solid);
  }
}

@media (prefers-reduced-transparency: reduce) {
  .qrlogin-card {
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
    background: var(--surface-solid);
  }
}
</style>
