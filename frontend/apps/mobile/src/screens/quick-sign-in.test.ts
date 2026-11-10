import { quickSignInConfig } from './quick-sign-in';

const VARIABLES = ['EXPO_PUBLIC_QUICK_SIGN_IN', 'EXPO_PUBLIC_DEMO_PASSWORD'];
const original = Object.fromEntries(VARIABLES.map((name) => [name, process.env[name]]));

// Expo reads the env through a reference to `process.env`: change its keys, never replace it.
afterEach(() => {
  VARIABLES.forEach((name) => setEnv(name, original[name]));
});

function setEnv(name: string, value: string | undefined) {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}

describe('quickSignInConfig', () => {
  it.each([undefined, '', 'false', 'TRUE', '1'])(
    'is off unless the flag is exactly "true" (%s)',
    (flag) => {
      setEnv('EXPO_PUBLIC_QUICK_SIGN_IN', flag);
      setEnv('EXPO_PUBLIC_DEMO_PASSWORD', 'demo-password');

      expect(quickSignInConfig()).toEqual({ state: 'off' });
    },
  );

  it('gives the password with the flag on', () => {
    setEnv('EXPO_PUBLIC_QUICK_SIGN_IN', 'true');
    setEnv('EXPO_PUBLIC_DEMO_PASSWORD', 'demo-password');

    expect(quickSignInConfig()).toEqual({ state: 'on', password: 'demo-password' });
  });

  it.each([undefined, ''])('reports a missing password instead of an empty one (%s)', (value) => {
    setEnv('EXPO_PUBLIC_QUICK_SIGN_IN', 'true');
    setEnv('EXPO_PUBLIC_DEMO_PASSWORD', value);

    expect(quickSignInConfig()).toEqual({ state: 'missing-password' });
  });
});
