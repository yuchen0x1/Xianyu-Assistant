import { createApp } from 'vue'

import App from './App.vue'
import router from './router'
import { initTheme } from '@/composables/useTheme'

import '@/styles/base.css'
import '@/styles/components.css'
import '@/styles/header-selectors.css'

initTheme()

const app = createApp(App)

app.use(router)

app.mount('#app')