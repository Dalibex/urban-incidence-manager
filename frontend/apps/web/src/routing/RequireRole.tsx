import type { Role } from '@urbanpulse/domain';
import { Navigate, Outlet } from 'react-router';

import { ForbiddenPage } from '../pages/ForbiddenPage';
import { useSession } from '../session/SessionContext';
import { PATHS, routeAccess } from './access';

interface RequireRoleProps {
  roles: readonly Role[];
}

/** Renders the child routes only for the given roles; see `routeAccess`. */
export function RequireRole({ roles }: RequireRoleProps) {
  const { user } = useSession();
  switch (routeAccess(user?.role ?? null, roles)) {
    case 'sign-in':
      return <Navigate to={PATHS.signIn} replace />;
    case 'forbidden':
      // Keeps the URL, so the user sees which address they cannot open.
      return <ForbiddenPage />;
    case 'allowed':
      return <Outlet />;
  }
}
