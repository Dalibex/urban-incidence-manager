import type { Role } from '@urbanpulse/domain';

/** Demo users of the web, one per staff role (docs/demo.md). Citizens use the mobile app. */
export const QUICK_SIGN_IN_USERS: readonly { role: Role; label: string; email: string }[] = [
  { role: 'MUNICIPAL_OPERATOR', label: 'Operator', email: 'demo.operator@example.com' },
  { role: 'TECHNICIAN', label: 'Technician', email: 'demo.technician@example.com' },
  { role: 'ADMINISTRATOR', label: 'Administrator', email: 'demo.admin@example.com' },
  { role: 'ANALYST', label: 'Analyst', email: 'demo.analyst@example.com' },
];

export type QuickSignInConfig =
  { state: 'off' } | { state: 'missing-password' } | { state: 'on'; password: string };

/**
 * Quick sign-in for the demo (#167), only with `VITE_QUICK_SIGN_IN=true`.
 *
 * Vite copies each `import.meta.env.VITE_*` into the bundle as a literal. The flag and the password
 * are read in one expression, so with the flag off the build folds it to `undefined` and leaves the
 * password out. Never read `import.meta.env` as a whole object here: Vite would inline every
 * variable. A build with the flag on contains the password, so it must never be deployed.
 */
export function quickSignInConfig(): QuickSignInConfig {
  const password =
    import.meta.env.VITE_QUICK_SIGN_IN === 'true'
      ? (import.meta.env.VITE_DEMO_PASSWORD ?? '')
      : null;
  if (password === null) {
    return { state: 'off' };
  }
  // An empty password would only earn a 400 and count as a failed attempt; say what is missing.
  return password === '' ? { state: 'missing-password' } : { state: 'on', password };
}
