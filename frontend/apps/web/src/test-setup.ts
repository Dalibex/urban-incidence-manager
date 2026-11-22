import { cleanup, configure } from '@testing-library/react';
import { afterEach } from 'vitest';

// `findBy` and `waitFor` wait 1 s by default, which a busy machine misses before the screen renders
// (#313). 5 s stays below the timeout per test in `vite.config.ts`, so a missing element still
// fails with "Unable to find", not with a timeout.
configure({ asyncUtilTimeout: 5_000 });

// Testing Library only unmounts automatically when the test runner exposes globals; ours does not.
// What the app remembers per user (`localStorage`) must not leak from one test into the next.
afterEach(() => {
  cleanup();
  window.localStorage.clear();
});
