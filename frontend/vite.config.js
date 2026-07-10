import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

// Vite config: https://vite.dev/config/
export default defineConfig({
  plugins: [react()],

  // Pin the project root to THIS config file's own folder, resolved as an
  // absolute path. Without this, Vite uses process.cwd() as the root - and
  // if the dev server is ever launched from a different working directory
  // (e.g. a terminal that opened elsewhere), it would look for index.html
  // in the wrong place and return 404 for everything. Deriving the root
  // from import.meta.url makes it launch-location-independent.
  root: fileURLToPath(new URL('.', import.meta.url)),

  // Dev-only convenience: forward any request starting with /api to our
  // Spring Boot backend (running on port 8080). This lets frontend code
  // always call relative paths like "/api/auth/login" - no hardcoded
  // backend URL needed, and no CORS issues during local development.
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
