import type { Role } from '@urbanpulse/domain';
import { Navigate, Outlet } from 'react-router';

import { useSession } from '../session/SessionContext';
import { PATHS, landingPath, routeAccess } from './access';

interface RequireRoleProps {
  roles: readonly Role[];
}

/** Renders the child routes only for the given roles; see `routeAccess`. */
export function RequireRole({ roles }: RequireRoleProps) {
  const { user } = useSession();
  const role = user?.role ?? null;
  switch (routeAccess(role, roles)) {
    case 'sign-in':
      return <Navigate to={PATHS.signIn} replace />;
    case 'forbidden':
      // A screen of another role sends the user to their own (#62, T5).
      return <Navigate to={role ? landingPath(role) : PATHS.signIn} replace />;
    case 'allowed':
      return <Outlet />;
  }
}
