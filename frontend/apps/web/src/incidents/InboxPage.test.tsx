import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

const EMPTY_PAGE = { content: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 } };

/** A fake API whose incident listing is always empty; `requests` keeps the query of each call. */
function emptyInbox(requests: URLSearchParams[]) {
  return fakeApi({
    'GET /incidents': (request) => {
      requests.push(new URL(request.url).searchParams);
      return json(200, EMPTY_PAGE);
    },
    'GET /categories': () => json(200, []),
    'GET /departments': () => json(200, []),
  });
}

describe('inbox filters', () => {
  it('asks the API for the filters of the address and drops the invalid ones', async () => {
    const requests: URLSearchParams[] = [];
    renderApp(`${PATHS.inbox}?status=FOO&priority=HIGH&reportedTo=2026-11-05&page=2`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: emptyInbox(requests),
    });

    await screen.findByText('No incidents match these filters.');
    const query = requests.at(-1)!;
    expect(query.has('status')).toBe(false);
    expect(query.getAll('priority')).toEqual(['HIGH']);
    expect(query.get('reportedTo')).toBe('2026-11-05T23:00:00.000Z');
    expect(query.get('page')).toBe('1');
  });

  // #217, T3
  it('ticks every status of the address and asks the API for all of them', async () => {
    const requests: URLSearchParams[] = [];
    renderApp(`${PATHS.inbox}?status=IN_PROGRESS&status=ASSIGNED`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: emptyInbox(requests),
    });

    await screen.findByText('No incidents match these filters.');
    const ticked = screen
      .getAllByRole<HTMLInputElement>('checkbox')
      .filter((checkbox) => checkbox.checked)
      .map((checkbox) => checkbox.closest('label')?.textContent);
    expect(ticked).toEqual(['Assigned', 'In progress']);
    expect(requests.at(-1)!.getAll('status')).toEqual(['ASSIGNED', 'IN_PROGRESS']);
  });

  it('starts again on the first page when a filter changes, keeping the others', async () => {
    const requests: URLSearchParams[] = [];
    const router = renderApp(`${PATHS.inbox}?priority=HIGH&page=3`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: emptyInbox(requests),
    });

    const statuses = await screen.findByRole('group', { name: 'Status' });
    await userEvent.click(within(statuses).getByRole('checkbox', { name: 'Assigned' }));

    const params = new URLSearchParams(router.state.location.search);
    expect(Object.fromEntries(params)).toEqual({ status: 'ASSIGNED', priority: 'HIGH' });
  });

  it('offers to clear the filters when nothing matches them', async () => {
    const requests: URLSearchParams[] = [];
    const router = renderApp(`${PATHS.inbox}?status=REPORTED`, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: emptyInbox(requests),
    });

    await screen.findByText('No incidents match these filters.');
    const [, emptyState] = screen.getAllByRole('button', { name: 'Clear filters' });
    await userEvent.click(emptyState!);

    expect(router.state.location.search).toBe('');
    expect(await screen.findByText('There are no incidents yet.')).toBeDefined();
    // The button goes away with the empty state; the focus stays in the filters (#211 review).
    expect(document.activeElement).toBe(screen.getByRole('checkbox', { name: 'Reported' }));
  });

  it('keeps the filters when the list fails, and filters again on retry', async () => {
    const requests: URLSearchParams[] = [];
    let available = false;
    const fetch = fakeApi({
      'GET /incidents': (request) => {
        requests.push(new URL(request.url).searchParams);
        return available ? json(200, EMPTY_PAGE) : json(503, { status: 503 });
      },
      'GET /categories': () => json(200, []),
      'GET /departments': () => json(200, []),
    });
    renderApp(`${PATHS.inbox}?priority=HIGH`, { role: 'MUNICIPAL_OPERATOR', fetch });

    expect(await screen.findByRole('alert')).toBeDefined();
    expect((screen.getByLabelText('Priority') as HTMLSelectElement).value).toBe('HIGH');

    available = true;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    await screen.findByText('No incidents match these filters.');
    expect(requests.at(-1)!.getAll('priority')).toEqual(['HIGH']);
  });
});

// #283 C: a page number far past the last page, typed or from an old link.
describe('inbox page of the address', () => {
  const INT32_MAX = 2_147_483_647;

  it('says that a huge page has no incidents, asking only for a page the API can read', async () => {
    const requests: URLSearchParams[] = [];
    const fetch = fakeApi({
      'GET /incidents': (request) => {
        const query = new URL(request.url).searchParams;
        requests.push(query);
        // As the API does: an offset past an int32 is a 500, any other page past the last is empty.
        if (Number(query.get('page')) * Number(query.get('size')) > INT32_MAX) {
          return json(500, { status: 500 });
        }
        return json(200, {
          content: [],
          page: { number: Number(query.get('page')), size: 20, totalElements: 3, totalPages: 1 },
        });
      },
      'GET /categories': () => json(200, []),
      'GET /departments': () => json(200, []),
    });
    renderApp(`${PATHS.inbox}?page=9999999999`, { role: 'MUNICIPAL_OPERATOR', fetch });

    expect(await screen.findByText('Page 9999999999 has no incidents.')).toBeDefined();
    expect(screen.queryByText(/could not be loaded/)).toBeNull();
    expect(requests.at(-1)!.get('page')).toBe(String(Math.floor(INT32_MAX / 20)));
  });
});
