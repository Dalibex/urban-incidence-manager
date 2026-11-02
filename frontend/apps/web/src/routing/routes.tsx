import { ROLES } from '@urbanpulse/domain';
import type { RouteObject } from 'react-router';

import { IncidentDetailPage } from '../incidents/IncidentDetailPage';
import { AppLayout } from '../layout/AppLayout';
import { LandingRedirect } from '../pages/LandingRedirect';
import { NotFoundPage } from '../pages/NotFoundPage';
import { SignInPage } from '../pages/SignInPage';
import { UseMobilePage } from '../pages/UseMobilePage';
import { OPERATOR_ROLES, PATHS } from './access';
import { RequireRole } from './RequireRole';
import { SECTIONS } from './sections';

export const routes: RouteObject[] = [
  { path: PATHS.signIn, element: <SignInPage /> },
  {
    element: <RequireRole roles={ROLES} />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <LandingRedirect /> },
          ...SECTIONS.map(({ path, roles, element }) => ({
            element: <RequireRole roles={roles} />,
            children: [{ path, element }],
          })),
          // The detail of an incident opens from the inbox, so it is not a section of the navbar.
          {
            element: <RequireRole roles={OPERATOR_ROLES} />,
            children: [{ path: PATHS.incident, element: <IncidentDetailPage /> }],
          },
          {
            element: <RequireRole roles={['CITIZEN']} />,
            children: [{ path: PATHS.useMobile, element: <UseMobilePage /> }],
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
];
