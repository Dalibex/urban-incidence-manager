import type { Role } from '@urbanpulse/domain';
import type { ReactNode } from 'react';

import { PlaceholderPage } from '../pages/PlaceholderPage';
import { PATHS } from './access';

/** A screen of the signed-in area. The navbar links to the sections the user's role can open. */
export interface Section {
  path: string;
  label: string;
  roles: readonly Role[];
  element: ReactNode;
}

// Each stream replaces its placeholder and widens `roles` when the permission matrix
// (docs/security.md) lets more roles use the screen.
export const SECTIONS: readonly Section[] = [
  {
    path: PATHS.inbox,
    label: 'Inbox',
    roles: ['MUNICIPAL_OPERATOR'],
    element: <PlaceholderPage title="Inbox" summary="Incidents waiting for validation." />,
  },
  {
    path: PATHS.workQueue,
    label: 'Work queue',
    roles: ['TECHNICIAN'],
    element: <PlaceholderPage title="Work queue" summary="Incidents assigned to you." />,
  },
  {
    path: PATHS.users,
    label: 'Users',
    roles: ['ADMINISTRATOR'],
    element: <PlaceholderPage title="Users" summary="Accounts, roles and access." />,
  },
  {
    path: PATHS.reports,
    label: 'Reports',
    roles: ['ANALYST'],
    element: <PlaceholderPage title="Reports" summary="Indicators and exportable reports." />,
  },
];
