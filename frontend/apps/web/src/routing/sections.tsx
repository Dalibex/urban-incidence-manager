import type { Role } from '@urbanpulse/domain';
import type { ReactNode } from 'react';

import { AuditLogPage } from '../audit/AuditLogPage';
import { InboxPage } from '../incidents/InboxPage';
import { WorkQueuePage } from '../incidents/WorkQueuePage';
import { PlaceholderPage } from '../pages/PlaceholderPage';
import { INBOX_DEFAULT_PATH, OPERATOR_ROLES, PATHS, WORK_QUEUE_DEFAULT_PATH } from './access';

/** A screen of the signed-in area. The navbar links to the sections the user's role can open. */
export interface Section {
  path: string;
  /** Where the navbar link goes, when it opens the screen with some filters; `path` otherwise. */
  link?: string;
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
    path: PATHS.users,
    label: 'Users',
    roles: ['ADMINISTRATOR'],
    element: <PlaceholderPage title="Users" summary="Accounts, roles and access." />,
  },
  {
    path: PATHS.auditLog,
    label: 'Audit log',
    roles: ['ADMINISTRATOR'],
    element: <AuditLogPage />,
  },
  {
    path: PATHS.reports,
    label: 'Reports',
    roles: ['ANALYST'],
    element: <PlaceholderPage title="Reports" summary="Indicators and exportable reports." />,
  },
];
