<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listCommands, executeCommand, type CommandDescriptor } from '@/api/commands'
import UiPageShell from '@/components/ui/UiPageShell.vue'
import EmptyState from '@/components/EmptyState.vue'
import IconRobot from '@/components/icons/IconRobot.vue'
import IconChevronDown from '@/components/icons/IconChevronDown.vue'
import IconPlay from '@/components/icons/IconPlay.vue'
import { toast } from '@/utils/toast'
import './commands.css'

const loading = ref(true)
const commands = ref<CommandDescriptor[]>([])
const expanded = ref<Record<string, boolean>>({})
const executing = ref<Record<string, boolean>>({})
// 每个命令的参数值（key: 命令名 -> 参数名 -> 值）
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const argsMap = reactive<Record<string, Record<string, any>>>({})
// 每个命令的执行结果（key: 命令名）
const resultMap = reactive<Record<string, { ok: boolean; text: string }>>({})

const loadCommands = async () => {
  loading.value = true
  try {
    const res = await listCommands()
    if (res.code === 200 && res.data) {
      commands.value = res.data
      for (const cmd of res.data) {
        argsMap[cmd.name] = {}
        for (const p of cmd.params) {
          argsMap[cmd.name][p.name] = p.type === 'boolean' ? false : ''
        }
        resultMap[cmd.name] = { ok: true, text: '' }
      }
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : '加载命令列表失败'
    toast.error(msg)
  } finally {
    loading.value = false
  }
}

const toggle = (name: string) => {
  expanded.value[name] = !expanded.value[name]
}

const handleExecute = async (cmd: CommandDescriptor) => {
  const args: Record<string, unknown> = {}
  for (const p of cmd.params) {
    const v = argsMap[cmd.name][p.name]
    if (p.type === 'boolean') {
      if (v) args[p.name] = true
      continue
    }
    if (v === '' || v === undefined || v === null) {
      if (p.required) {
        toast.warning(`请填写必填参数: ${p.name}`)
        return
      }
      continue
    }
    if (p.type === 'list') {
      const items = String(v).split(',')
        .map((s) => s.trim())
        .filter((s) => s)
      if (items.length > 0) args[p.name] = items
      continue
    }
    if (p.type === 'number') {
      const num = Number(v)
      if (Number.isNaN(num)) {
        toast.warning(`参数 ${p.name} 不是合法数字`)
        return
      }
      args[p.name] = num
      continue
    }
    args[p.name] = v
  }

  executing.value[cmd.name] = true
  try {
    const res = await executeCommand(cmd.name, args)
    resultMap[cmd.name] = { ok: true, text: JSON.stringify(res.data ?? {}, null, 2) }
  } catch (e) {
    const msg = e instanceof Error ? e.message : '命令执行失败'
    resultMap[cmd.name] = { ok: false, text: msg }
  } finally {
    executing.value[cmd.name] = false
  }
}

onMounted(loadCommands)
</script>

<template>
  <UiPageShell title="命令中心" subtitle="结构化运营命令，可被本系统页面或外部 AI 工具（MCP）调用">
    <div v-if="loading" class="cmd-loading">
      <div class="cmd-loading__spinner" aria-hidden="true"></div>
      <span>正在加载命令列表…</span>
    </div>
    <EmptyState
      v-else-if="!commands.length"
      title="暂无可用命令"
      desc="命令注册表为空，请确认后端服务已启动"
    />
    <div v-else class="cmd-list">
      <section
        v-for="cmd in commands"
        :key="cmd.name"
        class="cmd-card"
        :class="{ 'cmd-card--expanded': expanded[cmd.name] }"
      >
        <button class="cmd-card__head" type="button" @click="toggle(cmd.name)">
          <span class="cmd-card__icon" aria-hidden="true"><IconRobot /></span>
          <span class="cmd-card__info">
            <span class="cmd-card__name">{{ cmd.name }}</span>
            <span class="cmd-card__desc">{{ cmd.description }}</span>
          </span>
          <span v-if="cmd.needAccount" class="cmd-card__badge">需账号</span>
          <IconChevronDown class="cmd-card__chevron" aria-hidden="true" />
        </button>

        <div v-show="expanded[cmd.name]" class="cmd-card__body">
          <table v-if="cmd.params.length" class="cmd-params">
            <thead>
              <tr>
                <th>参数名</th>
                <th>类型</th>
                <th>必填</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in cmd.params" :key="p.name">
                <td class="cmd-params__name">{{ p.name }}</td>
                <td><code class="cmd-params__type">{{ p.type }}</code></td>
                <td>
                  <span :class="['cmd-params__req', p.required ? 'is-required' : 'is-optional']">
                    {{ p.required ? '必填' : '选填' }}
                  </span>
                </td>
                <td class="cmd-params__desc">{{ p.description || '—' }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="cmd-card__none">该命令无需参数</p>

          <div v-if="cmd.params.length" class="cmd-form">
            <label v-for="p in cmd.params" :key="p.name" class="cmd-field">
              <span class="cmd-field__label">
                {{ p.name }}
                <i v-if="p.required" class="cmd-field__required">*</i>
              </span>
              <input
                v-if="p.type === 'string'"
                v-model="argsMap[cmd.name][p.name]"
                type="text"
                :placeholder="p.description"
              />
              <input
                v-else-if="p.type === 'number'"
                v-model="argsMap[cmd.name][p.name]"
                type="number"
                step="any"
                :placeholder="p.description"
              />
              <textarea
                v-else-if="p.type === 'list'"
                v-model="argsMap[cmd.name][p.name]"
                rows="2"
                placeholder="多个值用英文逗号分隔"
              ></textarea>
              <span v-else-if="p.type === 'boolean'" class="cmd-field__checkbox">
                <input
                  type="checkbox"
                  :checked="Boolean(argsMap[cmd.name][p.name])"
                  @change="(e: Event) => (argsMap[cmd.name][p.name] = (e.target as HTMLInputElement).checked)"
                />
              </span>
            </label>
          </div>

          <div class="cmd-run-row">
            <button
              class="cmd-run"
              type="button"
              :disabled="executing[cmd.name]"
              @click="handleExecute(cmd)"
            >
              <IconPlay aria-hidden="true" />
              {{ executing[cmd.name] ? '执行中…' : '执行' }}
            </button>
          </div>

          <div
            v-if="resultMap[cmd.name] && resultMap[cmd.name].text"
            class="cmd-result"
            :class="{ 'cmd-result--error': !resultMap[cmd.name].ok }"
          >
            <div class="cmd-result__label">
              {{ resultMap[cmd.name].ok ? '执行结果' : '执行失败' }}
            </div>
            <pre>{{ resultMap[cmd.name].text }}</pre>
          </div>
        </div>
      </section>
    </div>
  </UiPageShell>
</template>
