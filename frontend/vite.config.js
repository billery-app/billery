import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import { keyboardZoomDiagnosticsPlugin } from './vite-plugins/keyboardZoomDiagnostics.js'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
    keyboardZoomDiagnosticsPlugin(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080'
    }
  },
  preview: {
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
})
