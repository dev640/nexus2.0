import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // Forward /api and the WebSockets to the Spring Boot backend (docker compose or local run)
      '/api': {
        target: process.env.BACKEND_ORIGIN || 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: process.env.BACKEND_ORIGIN || 'http://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
