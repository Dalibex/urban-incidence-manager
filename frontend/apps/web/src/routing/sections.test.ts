import { describe, expect, it } from 'vitest';

import { rememberFilters } from '../incidents/filters/remembered-filters';
import { userWith } from '../test-utils';
import { INBOX_DEFAULT_PATH, PATHS, WORK_QUEUE_DEFAULT_PATH } from './access';
import { SECTIONS } from './sections';

function linkOf(path: string, role: Parameters<typeof userWith>[0], userId = 'user-1') {
  const section = SECTIONS.find((each) => each.path === path)!;
  return section.link!({ ...userWith(role), id: userId });
}

describe('the menu links of the lists (#263)', () => {
  it('open the defaults the first time, as the sign-in does', () => {
    expect(linkOf(PATHS.inbox, 'MUNICIPAL_OPERATOR')).toBe(INBOX_DEFAULT_PATH);
    expect(linkOf(PATHS.workQueue, 'TECHNICIAN')).toBe(WORK_QUEUE_DEFAULT_PATH);
    expect(linkOf(PATHS.map, 'TECHNICIAN')).toBe(`${PATHS.map}?status=ASSIGNED&status=IN_PROGRESS`);
    expect(linkOf(PATHS.map, 'MUNICIPAL_OPERATOR')).toContain('status=REPORTED&status=VALIDATED');
  });

  it('open the filters the user last used, or none after Clear filters', () => {
    rememberFilters('inbox', 'user-1', { priority: 'HIGH' });
    rememberFilters('map', 'user-1', {});

    expect(linkOf(PATHS.inbox, 'MUNICIPAL_OPERATOR')).toBe(`${PATHS.inbox}?priority=HIGH`);
    expect(linkOf(PATHS.map, 'MUNICIPAL_OPERATOR')).toBe(PATHS.map);
  });

  it('give another user of the browser their own filters or the defaults', () => {
    rememberFilters('inbox', 'user-1', { priority: 'HIGH' });

    expect(linkOf(PATHS.inbox, 'MUNICIPAL_OPERATOR', 'user-2')).toBe(INBOX_DEFAULT_PATH);
  });
});
