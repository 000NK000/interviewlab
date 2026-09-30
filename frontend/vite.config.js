import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: { port: 15173, strictPort: true, proxy: { '/api': process.env.INTERVIEWLAB_API_TARGET || 'http://localhost:18080', '/ws': { target: (process.env.INTERVIEWLAB_API_TARGET || 'http://localhost:18080').replace(/^http/, 'ws'), ws: true } } },
  build: { chunkSizeWarningLimit: 2500 }
})
