import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Tests run through the same React/Vite pipeline as the app so JSX is
// transformed identically; jsdom supplies the browser surface (DOM, events,
// URL.createObjectURL stubs live in the setup file).
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
