import { afterEach, describe, expect, it, vi } from 'vitest';

import { quickSignInConfig } from './quick-sign-in';

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('quickSignInConfig', () => {
  it.each([undefined, '', 'false', 'TRUE', '1'])(
    'is off unless the flag is exactly "true" (%s)',
    (flag) => {
      vi.stubEnv('VITE_QUICK_SIGN_IN', flag);
      vi.stubEnv('VITE_DEMO_PASSWORD', 'demo-password');

      expect(quickSignInConfig()).toEqual({ state: 'off' });
    },
  );

  it('gives the password with the flag on', () => {
    vi.stubEnv('VITE_QUICK_SIGN_IN', 'true');
    vi.stubEnv('VITE_DEMO_PASSWORD', 'demo-password');

    expect(quickSignInConfig()).toEqual({ state: 'on', password: 'demo-password' });
  });

  it('reports a missing password instead of an empty one', () => {
    vi.stubEnv('VITE_QUICK_SIGN_IN', 'true');
    vi.stubEnv('VITE_DEMO_PASSWORD', '');

    expect(quickSignInConfig()).toEqual({ state: 'missing-password' });
  });
});
