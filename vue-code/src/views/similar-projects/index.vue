<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request } from '@/utils/request'
import UiPageShell from '@/components/ui/UiPageShell.vue'
import UiButton from '@/components/ui/UiButton.vue'

// 后端返回的 URL 未做 scheme 校验，绑定 :href 前先限制为 http(s)，防止 javascript: 注入
const safeUrl = (url: string) => (/^https?:\/\//i.test((url || '').trim()) ? url.trim() : '')

interface SimilarProject {
  name: string
  repo: string
  url: string
  description: string
  knownVersion: string
  knownDate: string
  latestVersion: string
  latestDate: string
  hasUpdate: boolean
  status: 'ok' | 'unavailable'
}

const projects = ref<SimilarProject[]>([])
const loading = ref(false)
const checkedAt = ref('')

const loadList = async () => {
  loading.value = true
  try {
    const res = await request<SimilarProject[]>({
      url: '/similar-projects/list',
      method: 'GET'
    })
    if (res.code === 200 && res.data) projects.value = res.data
  } finally {
    loading.value = false
  }
}

const checkUpdates = async () => {
  loading.value = true
  try {
    const res = await request<SimilarProject[]>({
      url: '/similar-projects/check',
      method: 'GET'
    })
    if (res.code === 200 && res.data) {
      projects.value = res.data
      checkedAt.value = new Date().toLocaleString('zh-CN')
    }
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadList()
  await checkUpdates()
})
</script>

<template>
  <UiPageShell
    title="同类资源"
    description="收录与本系统定位相近的开源项目，一键检测上游更新。发现更新后可对照其改动评估是否值得吸收进本项目。"
  >
    <section class="sp__toolbar">
      <span class="sp__meta">
        {{ checkedAt ? `上次检测：${checkedAt}（结果缓存 10 分钟）` : '正在检测上游更新...' }}
      </span>
      <UiButton :loading="loading" :disabled="loading" @click="checkUpdates">
        检查更新
      </UiButton>
    </section>

    <section class="sp__list">
      <div v-for="p in projects" :key="p.repo" class="sp__card">
        <div class="sp__head">
          <a class="sp__name" :href="safeUrl(p.url)" target="_blank" rel="noopener noreferrer">{{ p.name }}</a>
          <span
            class="sp__badge"
            :class="p.status !== 'ok' ? 'sp__badge--muted' : (p.hasUpdate ? 'sp__badge--update' : 'sp__badge--ok')"
          >
            {{ p.status !== 'ok' ? '检测失败' : (p.hasUpdate ? '已有更新' : '基线一致') }}
          </span>
        </div>
        <p class="sp__desc">{{ p.description }}</p>
        <div class="sp__facts">
          <span>基线：{{ p.knownVersion || p.knownDate || '-' }}</span>
          <span>上游最新：{{ p.status === 'ok' ? (p.latestVersion || '-') : '-' }}</span>
          <span v-if="p.latestDate">更新时间：{{ p.latestDate }}</span>
        </div>
      </div>

      <p v-if="!loading && projects.length === 0" class="sp__empty">清单为空</p>
    </section>
  </UiPageShell>
</template>

<style scoped>
.sp__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.sp__meta {
  color: var(--text-3);
  font-size: 12px;
}

.sp__list {
  display: grid;
  gap: 10px;
}

.sp__card {
  padding: 12px 14px;
  border: 1px solid var(--border-1);
  border-radius: var(--radius-md);
  background: var(--glass-bg-soft);
}

.sp__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.sp__name {
  color: var(--brand-700);
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
}

.sp__name:hover {
  text-decoration: underline;
}

.sp__badge {
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.sp__badge--update {
  color: var(--warning-600, #d97706);
  background: var(--warning-50, #fef3c7);
}

.sp__badge--ok {
  color: var(--success-600, #16a34a);
  background: var(--success-50, #dcfce7);
}

.sp__badge--muted {
  color: var(--text-3);
  background: var(--bg-muted, rgba(0, 0, 0, 0.04));
}

.sp__desc {
  margin: 6px 0;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.6;
}

.sp__facts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  color: var(--text-3);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.sp__empty {
  color: var(--text-3);
  text-align: center;
  padding: 20px 0;
}
</style>
