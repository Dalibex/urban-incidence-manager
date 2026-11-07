import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

// Acceptance tests of #78 for the inbox filters of #183, from the manual run on this pull request:
// each one reproduces a failure seen in a real browser that the component tests did not catch.
// The focus lost after "Clear filters" is not here: jsdom keeps the focus on a button that becomes
// disabled, so a test would pass without the fix; it is checked by hand.

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

/** An API with an empty inbox; `requests` keeps the query of each listing. */
function emptyInbox(requests: URLSearchParams[] = []) {
  return fakeApi({
    'POST /sessions': () => json(201, TOKENS),
    'GET /users/me': () => json(200, OPERATOR),
    'GET /incidents': (request) => {
      requests.push(new URL(request.url).searchParams);
      return json(200, EMPTY_PAGE);
    },
    'GET /categories': () => json(200, []),
    'GET /departments': () => json(200, []),
  });
}

describe('inbox filters in a real browser (#78)', () => {
  // T1. Typing a day with the keyboard, a browser reports a complete date as soon as the year has
  // one digit (30/10/2 is 0002-10-30). That intermediate day must not wipe what was typed, and the
  // day is applied once it is finished.
  it('keeps a day while its year is being typed, and applies it once it is complete', async () => {
    const requests: URLSearchParams[] = [];
    const router = renderApp(PATHS.inbox, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: emptyInbox(requests),
    });
    const from = (await screen.findByLabelText('Reported from')) as HTMLInputElement;

    fireEvent.change(from, { target: { value: '0002-10-30' } });

    expect(from.value).toBe('0002-10-30');
    // Nor is it sent to the API as a day of the year 2.
    expect(requests.every((query) => !query.get('reportedFrom')?.startsWith('000'))).toBe(true);

    fireEvent.change(from, { target: { value: '2026-10-30' } });
    fireEvent.blur(from);

    expect(new URLSearchParams(router.state.location.search).get('reportedFrom')).toBe(
      '2026-10-30',
    );
    expect(from.value).toBe('2026-10-30');
  });

  // T7. The web session lives in memory, so a reload, or a link to a filtered inbox opened in a new
  // tab, goes through the sign-in. After it, the inbox opens with the filters of the address.
  it('opens a filtered inbox with its filters after signing in', async () => {
    const requests: URLSearchParams[] = [];
    const router = renderApp(`${PATHS.inbox}?priority=HIGH`, { fetch: emptyInbox(requests) });

    await userEvent.type(await screen.findByLabelText('Email'), OPERATOR.email);
    await userEvent.type(screen.getByLabelText('Password'), 'secret-password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Inbox' })).toBeDefined();
    expect(router.state.location.pathname).toBe(PATHS.inbox);
    expect(Object.fromEntries(new URLSearchParams(router.state.location.search))).toEqual({
      priority: 'HIGH',
    });
    expect((screen.getByLabelText('Priority') as HTMLSelectElement).value).toBe('HIGH');
  });
});
