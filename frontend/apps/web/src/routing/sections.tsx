import type { Role } from '@urbanpulse/domain';
import type { ReactNode } from 'react';

import { AuditLogPage } from '../audit/AuditLogPage';
import { filtersSearch } from '../incidents/filters/incident-filters';
import { rememberedFilters, type FilterList } from '../incidents/filters/remembered-filters';
import { InboxPage } from '../incidents/InboxPage';
import { WorkQueuePage } from '../incidents/WorkQueuePage';
import { MapPage } from '../map/MapPage';
import { ReportsPage } from '../reports/ReportsPage';
import type { SessionUser } from '../session/SessionContext';
import { UsersPage } from '../users/UsersPage';
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
   * user; `path` otherwise.
   */
  link?: (user: SessionUser) => string;
  label: string;
  roles: readonly Role[];
  element: ReactNode;
}

/**
 * The link of a list: the filters its user last used on it, or `firstVisit` when nothing is stored
 * (#263). Nothing stored is not the same as no filters: after *Clear filters* the list opens as is.
 */
function rememberedLink(
  path: string,
  list: FilterList,
  firstVisit: (user: SessionUser) => string,
): (user: SessionUser) => string {
  return (user) => {
    const filters = rememberedFilters(list, user.id);
    return filters ? path + filtersSearch(filters) : firstVisit(user);
  };
}

// Each stream replaces its placeholder and widens `roles` when the permission matrix
// (docs/security.md) lets more roles use the screen.
export const SECTIONS: readonly Section[] = [
  {
    path: PATHS.inbox,
    link: rememberedLink(PATHS.inbox, 'inbox', () => INBOX_DEFAULT_PATH),
    label: 'Inbox',
    roles: OPERATOR_ROLES,
    element: <InboxPage />,
  },
  {
    path: PATHS.workQueue,
    link: rememberedLink(PATHS.workQueue, 'workQueue', () => WORK_QUEUE_DEFAULT_PATH),
    label: 'Work queue',
    roles: ['TECHNICIAN'],
    element: <WorkQueuePage />,
  },
  {
    path: PATHS.map,
    link: rememberedLink(PATHS.map, 'map', ({ role }) => mapDefaultPath(role)),
    label: 'Map',
    roles: INCIDENT_DETAIL_ROLES,
    element: <MapPage />,
  },
  {
    path: PATHS.users,
    label: 'Users',
    roles: ['ADMINISTRATOR'],
    element: <UsersPage />,
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
    roles: ['ANALYST', 'ADMINISTRATOR'],
    element: <ReportsPage />,
  },
];
