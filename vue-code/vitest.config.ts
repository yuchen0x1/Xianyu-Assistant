import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.ts'],
    // 各测试文件会声明不同的 vi.mock；关闭文件级并行，避免 mock 注册
    // 在共享模块缓存中互相覆盖，导致 runner/config 为空等非确定性错误。
    fileParallelism: false,
    restoreMocks: true,
  },
})
