import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The jar serves whatever lands in src/main/resources/static, so build straight into it.
// During `npm run dev`, /api is proxied to the running backend.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: { outDir: '../src/main/resources/static', emptyOutDir: true },
  server: { proxy: { '/api': 'http://127.0.0.1:8765' } },
})
