import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import pkg from './package.json' with { type: 'json' }

const backendTarget = process.env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:12400'

export default defineConfig({
  plugins: [vue()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version)
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: backendTarget,
        changeOrigin: true
      },
      '/ai': {
        target: backendTarget,
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: '../src/main/resources/static',
    assetsDir: 'assets',
    sourcemap: false,
    emptyOutDir: true
  }
})
