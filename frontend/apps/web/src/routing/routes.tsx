import { ROLES } from '@urbanpulse/domain';
import type { RouteObject } from 'react-router';

import { AppLayout } from '../layout/AppLayout';
import { LandingRedirect } from '../pages/LandingRedirect';
import { NotFoundPage } from '../pages/NotFoundPage';
import { SignInPage } from '../pages/SignInPage';
import { UseMobilePage } from '../pages/UseMobilePage';
import { PATHS } from './access';
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
