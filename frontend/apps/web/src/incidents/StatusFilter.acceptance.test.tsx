import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

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

describe('several statuses in the status filter (#218)', () => {
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

  // T4, the inbox: after signing in and from the menu it opens on Reported only.
  it('opens the inbox on Reported after signing in and from the menu', async () => {
    const router = renderApp(PATHS.signIn, { fetch: inboxApi([]) });

    await signIn();
    await screen.findByText('No incidents match these filters.');
    expect(tickedStatuses()).toEqual(['Reported']);

    await userEvent.click(statusChoice('Reopened'));
    expect(tickedStatuses()).toEqual(['Reported', 'Reopened']);
    const menu = screen.getByRole('navigation', { name: 'Main' });
    await userEvent.click(within(menu).getByRole('link', { name: 'Inbox' }));

    expect(tickedStatuses()).toEqual(['Reported']);
    // The list of Reported was asked for after the sign-in, so it comes from the cache.
    expect(router.state.location.search).toBe('?status=REPORTED');
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
});
