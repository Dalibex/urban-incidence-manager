import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  // The licences of the bundled libraries (Lucide's icons among them, ISC and MIT) go with the app
  // as `licenses.md`, served beside it, as those licences ask for their notices to be kept.
  build: { license: { fileName: 'licenses.md' } },
  server: {
    // The page calls `/api/v1` on its own origin, so the browser needs no CORS. In production a
    // reverse proxy plays this role.
    proxy: { '/api': process.env.API_PROXY_TARGET ?? 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    // Vitest's 5 s default is too tight on a busy machine or with empty caches (#313). It stays
    // above the wait of `findBy` in `test-setup.ts`, so a missing element fails with its own error.
    testTimeout: 20_000,
    // Node cannot build a request from a relative URL, as the browser does with `/api/v1`.
    // Quick sign-in off, whatever the local .env says; a test turns it on with `vi.stubEnv`.
    env: {
      VITE_API_BASE_URL: 'http://localhost/api/v1',
      VITE_QUICK_SIGN_IN: 'false',
      VITE_DEMO_PASSWORD: '',
    },
  },
});
