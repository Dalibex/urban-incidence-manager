import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

// Acceptance tests of #64 for the screen side of T9 and T10 (#41). The API side of the cases is in
// AuditLogApiIT.

type Answer = () => Response;

const ENTRY = {
  id: '9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d',
  instant: '2026-11-09T13:01:59Z',
  actor: { id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c', name: 'Laura Bootstrap' },
  action: 'CREATE_USER',
  target: { type: 'USER', id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c', label: 'Laura Bootstrap' },
  details: { before: {}, after: { role: 'ADMINISTRATOR' } },
};

function page(content: unknown[], totalElements = content.length, number = 0): Answer {
  return () =>
    json(200, {
      content,
      page: { number, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) },
    });
}

const SERVER_ERROR: Answer = () =>
  json(500, { type: 'about:blank', title: 'Internal Server Error', status: 500 });

const NO_NETWORK: Answer = () => {
  throw new TypeError('Failed to fetch');
};

/** The audit API answering each request with the next answer, the last one from then on. */
function auditApi(...answers: Answer[]) {
  const queries: URLSearchParams[] = [];
  const fetch = fakeApi({
    'GET /audit-entries': (request) => {
      queries.push(new URL(request.url).searchParams);
      return (answers.length > 1 ? answers.shift()! : answers[0]!)();
    },
  });
  return { fetch, queries };
}

function accountCreated(): HTMLInputElement {
  return screen.getByRole<HTMLInputElement>('checkbox', { name: 'Account created' });
}

describe('audit log, acceptance of #64', () => {
  // T9: filters that match nothing are an empty state that clears them.
  it('says no entry matches the filters, and clears them', async () => {
    const { fetch, queries } = auditApi(page([]), page([ENTRY]));
    const router = renderApp(`${PATHS.auditLog}?action=ENABLE_USER&from=2026-11-10`, {
      role: 'ADMINISTRATOR',
      fetch,
    });

    expect(await screen.findByText('No entries match these filters.')).toBeDefined();
    expect(screen.queryByRole('table')).toBeNull();
    expect(queries[0]!.getAll('action')).toEqual(['ENABLE_USER']);

    // The bar has its own Clear filters; this is the one of the empty state, after it.
    const [, ofTheEmptyState] = screen.getAllByRole('button', { name: 'Clear filters' });
    await userEvent.click(ofTheEmptyState!);

    expect(await screen.findByRole('table')).toBeDefined();
    expect(router.state.location.search).toBe('');
    expect(document.activeElement).toBe(accountCreated());
    const last = queries.at(-1)!;
    expect(last.has('action')).toBe(false);
    expect(last.has('from')).toBe(false);
  });

  // T9: with no filters, an empty trail says so instead of offering to clear anything.
  it('says the audit log is empty when nothing was recorded yet', async () => {
    renderApp(PATHS.auditLog, { role: 'ADMINISTRATOR', fetch: auditApi(page([])).fetch });

    expect(await screen.findByText('The audit log is empty.')).toBeDefined();
    expect(screen.queryByText('No entries match these filters.')).toBeNull();
  });

  // T9: a page past the last one, from an old link, leads back to the first page.
  it('leads back to the first page from a page past the last one', async () => {
    const { fetch, queries } = auditApi(page([], 1, 2), page([ENTRY]));
    renderApp(`${PATHS.auditLog}?page=3`, { role: 'ADMINISTRATOR', fetch });

    expect(await screen.findByText('Page 3 has no entries.')).toBeDefined();

    await userEvent.click(screen.getByRole('button', { name: 'Go to the first page' }));

    expect(await screen.findByRole('table')).toBeDefined();
    expect(queries.at(-1)!.get('page')).toBe('0');
  });

  // T10: a 5xx is an error with a retry; the filters stay as they were.
  it('explains a server failure, keeps the filters and loads on retry', async () => {
    const { fetch, queries } = auditApi(SERVER_ERROR, SERVER_ERROR, SERVER_ERROR, page([ENTRY]));
    renderApp(`${PATHS.auditLog}?action=CREATE_USER`, { role: 'ADMINISTRATOR', fetch });

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The audit log could not be loaded. Try again in a moment.',
    );
    expect(accountCreated().checked).toBe(true);

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('table')).toBeDefined();
    expect(accountCreated().checked).toBe(true);
    expect(queries.every((query) => query.getAll('action').join() === 'CREATE_USER')).toBe(true);
  });

  // T10: without network the message says the server cannot be reached.
  it('says the server cannot be reached without network, and loads on retry', async () => {
    const { fetch } = auditApi(NO_NETWORK, NO_NETWORK, NO_NETWORK, page([ENTRY]));
    renderApp(`${PATHS.auditLog}?action=CREATE_USER`, { role: 'ADMINISTRATOR', fetch });

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The server cannot be reached. Check your connection and try again.',
    );
    expect(accountCreated().checked).toBe(true);

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('table')).toBeDefined();
  });
});
