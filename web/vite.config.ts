import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const API_PROXY_TARGET = 'http://localhost:8080'

// Proxies API path prefixes to the Spring Boot backend so the browser sees
// everything as same-origin in dev — no backend CORS config needed.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/orders': API_PROXY_TARGET,
      '/order-types': API_PROXY_TARGET,
      '/tasks': API_PROXY_TARGET,
      '/workflow-definitions': API_PROXY_TARGET,
    },
  },
})
