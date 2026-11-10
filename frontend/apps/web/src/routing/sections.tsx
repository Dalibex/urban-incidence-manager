import type { Role } from '@urbanpulse/domain';
import type { ReactNode } from 'react';

import { InboxPage } from '../incidents/InboxPage';
import { WorkQueuePage } from '../incidents/WorkQueuePage';
import { MapPage } from '../map/MapPage';
import { PlaceholderPage } from '../pages/PlaceholderPage';
import { ReportsPage } from '../reports/ReportsPage';
import {
  INBOX_DEFAULT_PATH,
  INCIDENT_DETAIL_ROLES,
  mapDefaultPath,
  OPERATOR_ROLES,
  PATHS,
  WORK_QUEUE_DEFAULT_PATH,
} from './access';

/** A screen of the signed-in area. The navbar links to the sections the user's role can open. */
export interface Section {
  path: string;
  /**
   * Where the navbar link goes, when it opens the screen with some filters, which may depend on the
   * role; `path` otherwise.
   */
  link?: string | ((role: Role) => string);
  label: string;
  roles: readonly Role[];
  element: ReactNode;
}

// Each stream replaces its placeholder and widens `roles` when the permission matrix
// (docs/security.md) lets more roles use the screen.
export const SECTIONS: readonly Section[] = [
  {
    path: PATHS.inbox,
    link: INBOX_DEFAULT_PATH,
    label: 'Inbox',
    roles: OPERATOR_ROLES,
    element: <InboxPage />,
  },
  {
    path: PATHS.workQueue,
    link: WORK_QUEUE_DEFAULT_PATH,
    label: 'Work queue',
    roles: ['TECHNICIAN'],
    element: <WorkQueuePage />,
  },
  {
    path: PATHS.map,
    link: mapDefaultPath,
    label: 'Map',
    roles: INCIDENT_DETAIL_ROLES,
    element: <MapPage />,
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
    roles: ['ANALYST', 'ADMINISTRATOR'],
    element: <ReportsPage />,
  },
];
