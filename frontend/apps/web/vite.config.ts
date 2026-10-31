import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: {
    // The page calls `/api/v1` on its own origin, so the browser needs no CORS. In production a
    // reverse proxy plays this role.
    proxy: { '/api': process.env.API_PROXY_TARGET ?? 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    // Node cannot build a request from a relative URL, as the browser does with `/api/v1`.
    env: { VITE_API_BASE_URL: 'http://localhost/api/v1' },
  },
});
