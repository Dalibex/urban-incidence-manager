import { act, cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

// Tests of #218 for the several statuses of the status filter (#217), one per case of the issue.
// T2 (the list shows only the ticked statuses) needs the real API, and the work queue of T4 opens
// on Assigned and In progress with #187: both are checked by hand on the pull request.

const EMPTY_PAGE = { content: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 } };

const TOKENS = {
  tokenType: 'Bearer',
  accessToken: 'access-1',
  accessTokenExpiresAt: '2026-11-02T10:15:00Z',
  refreshToken: 'refresh-1',
  refreshTokenExpiresAt: '2026-11-16T10:00:00Z',
};

const OPERATOR = {
  id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c',
  name: 'Lucía Martín',
  email: 'lucia.martin@example.com',
  role: 'MUNICIPAL_OPERATOR',
};

type ListAnswer = () => Response;

/** An API whose listing answers with `list` (an empty page by default); `requests` keeps each query. */
function inboxApi(requests: URLSearchParams[], list: ListAnswer = () => json(200, EMPTY_PAGE)) {
  return fakeApi({
    'POST /sessions': () => json(201, TOKENS),
    'GET /users/me': () => json(200, OPERATOR),
    'GET /incidents': (request) => {
      requests.push(new URL(request.url).searchParams);
      return list();
    },
    'GET /categories': () => json(200, []),
    'GET /departments': () => json(200, []),
  });
}

function statusChoice(name: string): HTMLInputElement {
  return within(screen.getByRole('group', { name: 'Status' })).getByRole('checkbox', { name });
}

/** The labels of the ticked statuses, in the order the bar shows them. */
function tickedStatuses(): (string | null | undefined)[] {
  return within(screen.getByRole('group', { name: 'Status' }))
    .getAllByRole<HTMLInputElement>('checkbox')
    .filter((checkbox) => checkbox.checked)
    .map((checkbox) => checkbox.closest('label')?.textContent);
}

async function signIn() {
  await userEvent.type(await screen.findByLabelText('Email'), OPERATOR.email);
  await userEvent.type(screen.getByLabelText('Password'), 'secret-password');
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

/**
 * The inbox API for two operators who share the browser: `/users/me` answers whoever `current.user`
 * is, and signing out works.
 */
function sharedBrowserApi() {
  const current = { user: OPERATOR };
  const inbox = inboxApi([]);
  const fetch = (request: Request) => {
    const { pathname } = new URL(request.url);
    if (request.method === 'GET' && pathname.endsWith('/users/me')) {
      return Promise.resolve(json(200, current.user));
    }
    if (request.method === 'DELETE' && pathname.endsWith('/sessions/current')) {
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    return inbox(request);
  };
  return { current, fetch };
}

async function signOut() {
  await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
  await screen.findByRole('heading', { name: 'Sign in to UrbanPulse' });
}

describe('several statuses in the status filter (#218)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  // T1, the part jsdom can check: the browser's own focus ring and Tab order are checked by hand.
  it('ticks and unticks the statuses with the keyboard', async () => {
    const requests: URLSearchParams[] = [];
    const router = renderApp(`${PATHS.inbox}?status=REPORTED`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: inboxApi(requests),
    });
    await screen.findByText('No incidents match these filters.');

    statusChoice('Reported').focus();
    await userEvent.tab();
    expect(document.activeElement).toBe(statusChoice('Validated'));
    await userEvent.keyboard(' ');
    await userEvent.tab({ shift: true });
    await userEvent.keyboard(' ');

    expect(tickedStatuses()).toEqual(['Validated']);
    expect(new URLSearchParams(router.state.location.search).getAll('status')).toEqual([
      'VALIDATED',
    ]);
    expect(requests.at(-1)!.getAll('status')).toEqual(['VALIDATED']);
  });

  // T3. The web session lives in memory, so a reload goes through the sign-in (#78, T7).
  it('keeps several statuses of the address through a reload', async () => {
    const requests: URLSearchParams[] = [];
    const router = renderApp(`${PATHS.inbox}?status=ASSIGNED&status=IN_PROGRESS`, {
      fetch: inboxApi(requests),
    });

    await signIn();

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Assigned', 'In progress']);
    expect(new URLSearchParams(router.state.location.search).getAll('status')).toEqual([
      'ASSIGNED',
      'IN_PROGRESS',
    ]);
    expect(requests.at(-1)!.getAll('status')).toEqual(['ASSIGNED', 'IN_PROGRESS']);
  });

  // T4, the inbox: after signing in it opens on Reported only. The menu opens the defaults the
  // first time (routing/sections.test.ts) and the last filters afterwards (#263).
  it('opens the inbox on Reported after signing in', async () => {
    renderApp(PATHS.signIn, { fetch: inboxApi([]) });

    await signIn();

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Reported']);
  });

  // #263, AC1: the filters come back from the menu, also after Clear filters.
  it('reopens the inbox from the menu with the filters last used on it', async () => {
    const router = renderApp(PATHS.signIn, { fetch: inboxApi([]) });
    await signIn();
    await screen.findByText('No incidents match these filters.');
    const menu = screen.getByRole('navigation', { name: 'Main' });

    await userEvent.click(statusChoice('Reopened'));
    await act(() => router.navigate('/elsewhere'));
    await userEvent.click(within(menu).getByRole('link', { name: 'Inbox' }));

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Reported', 'Reopened']);

    // The first button is the one of the filter bar; the empty state has the other.
    await userEvent.click(screen.getAllByRole('button', { name: 'Clear filters' })[0]!);
    await screen.findByText('There are no incidents yet.');
    await act(() => router.navigate('/elsewhere'));
    await userEvent.click(within(menu).getByRole('link', { name: 'Inbox' }));

    await screen.findByText('There are no incidents yet.');
    expect(tickedStatuses()).toEqual([]);
    expect(router.state.location.search).toBe('');
  });

  // #263, AC2: the filters are of the user, not of the browser.
  it('gives another user of the browser their own filters, or the defaults', async () => {
    const first = renderApp(`${PATHS.inbox}?status=VALIDATED&priority=HIGH`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: inboxApi([]),
    });
    await screen.findByText('No incidents match these filters.');
    await act(() => first.navigate('/elsewhere'));
    cleanup();

    const second = renderApp('/elsewhere', {
      role: 'MUNICIPAL_OPERATOR',
      userId: 'user-2',
      fetch: inboxApi([]),
    });
    await userEvent.click(
      within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Inbox' }),
    );

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Reported']);
    expect(second.state.location.search).toBe('?status=REPORTED');
  });

  // T5
  it('shows every status from the first page when the last status is unticked', async () => {
    const requests: URLSearchParams[] = [];
    const router = renderApp(`${PATHS.inbox}?status=ASSIGNED&priority=HIGH&page=3`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: inboxApi(requests),
    });
    await screen.findByText('No incidents match these filters.');

    await userEvent.click(statusChoice('Assigned'));

    expect(tickedStatuses()).toEqual([]);
    expect(Object.fromEntries(new URLSearchParams(router.state.location.search))).toEqual({
      priority: 'HIGH',
    });
    const query = requests.at(-1)!;
    expect(query.has('status')).toBe(false);
    // The API counts pages from 0, so page 3 of the address was page 2 of the API.
    expect(query.get('page') ?? '0').toBe('0');
  });

  // T6
  it('drops an unknown status and counts a repeated one once', async () => {
    const requests: URLSearchParams[] = [];
    renderApp(`${PATHS.inbox}?status=FOO&status=CLOSED&status=CLOSED`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: inboxApi(requests),
    });

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Closed']);
    expect(requests.at(-1)!.getAll('status')).toEqual(['CLOSED']);
  });

  // T7
  it('clears several statuses from the empty state and keeps the focus in the filters', async () => {
    const requests: URLSearchParams[] = [];
    const router = renderApp(`${PATHS.inbox}?status=REPORTED&status=REOPENED&priority=HIGH`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: inboxApi(requests),
    });

    await screen.findByText('No incidents match these filters.');
    const [, emptyState] = screen.getAllByRole('button', { name: 'Clear filters' });
    await userEvent.click(emptyState!);

    expect(await screen.findByText('There are no incidents yet.')).toBeDefined();
    expect(router.state.location.search).toBe('');
    expect(tickedStatuses()).toEqual([]);
    expect(requests.at(-1)!.has('status')).toBe(false);
    expect(document.activeElement).toBe(statusChoice('Reported'));
  });

  // T8
  it.each<[string, ListAnswer]>([
    ['a 5xx', () => json(503, { status: 503 })],
    [
      'no network',
      () => {
        throw new TypeError('Failed to fetch');
      },
    ],
  ])('keeps several statuses ticked after %s, and retries with them', async (_, failure) => {
    const requests: URLSearchParams[] = [];
    let available = false;
    renderApp(`${PATHS.inbox}?status=REPORTED&status=REOPENED`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: inboxApi(requests, () => (available ? json(200, EMPTY_PAGE) : failure())),
    });

    expect(await screen.findByRole('alert')).toBeDefined();
    expect(tickedStatuses()).toEqual(['Reported', 'Reopened']);

    available = true;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Reported', 'Reopened']);
    expect(requests.at(-1)!.getAll('status')).toEqual(['REPORTED', 'REOPENED']);
  });

  // #263: signing out sends to the sign-in with the address of the one who left.
  it('does not give the filters of one user to the next who signs in', async () => {
    const { current, fetch } = sharedBrowserApi();
    renderApp(PATHS.signIn, { fetch });
    await signIn();
    await screen.findByText('No incidents match these filters.');
    await userEvent.click(statusChoice('Reopened'));
    await signOut();

    current.user = { ...OPERATOR, id: 'second-operator' };
    await signIn();

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Reported']);
    expect(window.localStorage.getItem('urbanpulse.list-filters.inbox.second-operator')).toBe(
      '?status=REPORTED',
    );
    const menu = screen.getByRole('navigation', { name: 'Main' });
    expect(within(menu).getByRole('link', { name: 'Inbox' }).getAttribute('href')).toBe(
      '/inbox?status=REPORTED',
    );
  });

  // #263: the first visit has the defaults, not every later sign-in.
  it('lands on the filters last used on the inbox when the same user signs in again', async () => {
    const { fetch } = sharedBrowserApi();
    const router = renderApp(PATHS.signIn, { fetch });
    await signIn();
    await screen.findByText('No incidents match these filters.');
    await userEvent.click(statusChoice('Reopened'));
    await signOut();
    // No address to return to: the landing path decides.
    await act(() => router.navigate(PATHS.signIn, { replace: true }));

    await signIn();

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Reported', 'Reopened']);
    expect(router.state.location.search).toBe('?status=REPORTED&status=REOPENED');
  });

  it('opens the menu and the inbox on the defaults when the browser refuses its storage', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });
    const router = renderApp(PATHS.home, { role: 'MUNICIPAL_OPERATOR', fetch: inboxApi([]) });

    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Reported']);
    await userEvent.click(statusChoice('Reopened'));
    const menu = screen.getByRole('navigation', { name: 'Main' });
    expect(within(menu).getByRole('link', { name: 'Inbox' }).getAttribute('href')).toBe(
      '/inbox?status=REPORTED',
    );
    expect(screen.queryByRole('alert')).toBeNull();
    expect(router.state.location.pathname).toBe(PATHS.inbox);
  });
});
