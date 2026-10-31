import { Navigate } from 'react-router';

import { PATHS, landingPath } from '../routing/access';
import { useSession } from '../session/SessionContext';

/** `/` sends each role to its own screen. */
export function LandingRedirect() {
  const { user } = useSession();
  return <Navigate to={user ? landingPath(user.role) : PATHS.signIn} replace />;
}
