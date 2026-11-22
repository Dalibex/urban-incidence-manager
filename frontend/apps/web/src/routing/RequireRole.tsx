import type { Role } from '@urbanpulse/domain';
import { useState } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';

import { useSession } from '../session/SessionContext';
import { PATHS, landingPath, routeAccess, type SignInReturn } from './access';

interface RequireRoleProps {
  roles: readonly Role[];
}

/** Renders the child routes only for the given roles; see `routeAccess`. */
export function RequireRole({ roles }: RequireRoleProps) {
  const { user } = useSession();
  const location = useLocation();
  const role = user?.role ?? null;
  // Who was signed in before a sign-out or an expiry, so another user does not inherit the address.
  const [lastUserId, setLastUserId] = useState<string>();
  if (user && user.id !== lastUserId) {
    setLastUserId(user.id);
  }
  switch (routeAccess(role, roles)) {
    case 'sign-in': {
      // The sign-in returns here afterwards; whether the role may open it is checked again then.
      const state: SignInReturn = {
        returnTo: location.pathname + location.search,
        userId: lastUserId,
      };
      return <Navigate to={PATHS.signIn} replace state={state} />;
    }
    case 'forbidden':
      // A screen of another role sends the user to their own (#62, T5).
      return <Navigate to={role ? landingPath(role, user?.id) : PATHS.signIn} replace />;
    case 'allowed':
      return <Outlet />;
  }
}
