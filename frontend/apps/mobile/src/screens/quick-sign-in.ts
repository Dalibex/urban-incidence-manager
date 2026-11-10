/** Demo citizen of the mobile app (docs/demo.md). Staff use the web app. */
export const QUICK_SIGN_IN_CITIZEN_EMAIL = 'demo.citizen@example.com';

export type QuickSignInConfig =
  { state: 'off' } | { state: 'missing-password' } | { state: 'on'; password: string };

/**
 * Quick sign-in for the demo (#167), only with `EXPO_PUBLIC_QUICK_SIGN_IN=true`.
 *
 * Expo copies each `process.env.EXPO_PUBLIC_*` into the bundle as a literal, and only when written
 * out in full like this, never destructured. The flag and the password are read in one expression,
 * so with the flag off the build folds it and leaves the password out. A build with the flag on
 * contains the password, so it must never be distributed.
 */
export function quickSignInConfig(): QuickSignInConfig {
  const password =
    process.env.EXPO_PUBLIC_QUICK_SIGN_IN === 'true'
      ? (process.env.EXPO_PUBLIC_DEMO_PASSWORD ?? '')
      : null;
  if (password === null) {
    return { state: 'off' };
  }
  // An empty password would only earn a 400 and count as a failed attempt; say what is missing.
  return password === '' ? { state: 'missing-password' } : { state: 'on', password };
}
