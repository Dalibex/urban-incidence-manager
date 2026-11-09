import { onlineManager } from '@tanstack/react-query';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

const ANA = { id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c', name: 'Ana Admin' };
const INCIDENT_ID = '2b8d4f6a-1c3e-4a5b-9d7f-0e2c4a6b8d1f';

const ENTRIES = [
  {
    id: '9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d',
    instant: '2026-11-02T09:58:31Z',
    actor: ANA,
    action: 'CHANGE_ROLE',
    target: { type: 'USER', id: '11111111-1111-4111-8111-111111111111', label: 'Pedro Gómez' },
    details: { before: { role: 'TECHNICIAN' }, after: { role: 'MUNICIPAL_OPERATOR' } },
  },
  {
    id: '8a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d',
    instant: '2026-11-02T09:00:00Z',
    actor: ANA,
    action: 'CLASSIFY_INCIDENT',
    target: { type: 'INCIDENT', id: INCIDENT_ID, label: 'Broken streetlight' },
    details: null,
  },
  {
    id: '7a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d',
    instant: '2026-11-01T08:00:00Z',
    actor: ANA,
    action: 'CREATE_USER',
    target: { type: 'USER', id: '22222222-2222-4222-8222-222222222222', label: null },
    details: { before: {}, after: { role: 'ANALYST' } },
  },
];

function auditApi(requests: URLSearchParams[], content: unknown[] = ENTRIES) {
  return fakeApi({
    'GET /audit-entries': (request) => {
      requests.push(new URL(request.url).searchParams);
      return json(200, {
        content,
        page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 },
      });
    },
  });
}

afterEach(() => {
  onlineManager.setOnline(true);
});

describe('audit log', () => {
  // jsdom is always online, so a failing fetch alone does not show this: with the browser offline,
  // React Query would pause the request and the screen would keep loading.
  it('says the server cannot be reached while the browser is offline', async () => {
    onlineManager.setOnline(false);
    const fetch = fakeApi({
      'GET /audit-entries': () => {
        throw new TypeError('Failed to fetch');
      },
    });
    renderApp(PATHS.auditLog, { role: 'ADMINISTRATOR', fetch });

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The server cannot be reached. Check your connection and try again.',
    );
    expect(screen.getByRole('button', { name: 'Try again' })).toBeDefined();
  });

  it('lists the entries with their actor, action, record and changes', async () => {
    renderApp(PATHS.auditLog, { role: 'ADMINISTRATOR', fetch: auditApi([]) });

    const rows = within(await screen.findByRole('table'))
      .getAllByRole('row')
      .slice(1);
    expect(rows).toHaveLength(3);
    expect(rows[0]!.textContent).toContain('Role changed');
    expect(rows[0]!.textContent).toContain('Pedro Gómez');
    expect(rows[0]!.textContent).toContain('role: TECHNICIAN → MUNICIPAL_OPERATOR');
    expect(within(rows[1]!).getByRole('link', { name: 'Broken streetlight' })).toBeDefined();
    expect(rows[1]!.textContent).toContain('No details');
    expect(rows[2]!.textContent).toContain('No longer exists');
  });

  it('filters by the actor of a row and shows them in a chip that removes the filter', async () => {
    const requests: URLSearchParams[] = [];
    const router = renderApp(`${PATHS.auditLog}?page=2`, {
      role: 'ADMINISTRATOR',
      fetch: auditApi(requests),
    });

    const [first] = await screen.findAllByRole('button', {
      name: 'Show only the entries of Ana Admin',
    });
    await userEvent.click(first!);

    const chip = await screen.findByRole('button', { name: 'Remove the actor filter: Ana Admin' });
    expect(requests.at(-1)!.get('actorId')).toBe(ANA.id);
    // Another filter gives other pages, so it starts on the first one.
    expect(router.state.location.search).toBe(`?actorId=${ANA.id}`);

    await userEvent.click(chip);

    expect(router.state.location.search).toBe('');
  });

  // The address holds only the id, so a page without their rows cannot tell the name.
  it('names the actor generically when no row tells their name', async () => {
    renderApp(`${PATHS.auditLog}?actorId=${ANA.id}&action=CHANGE_PASSWORD`, {
      role: 'ADMINISTRATOR',
      fetch: auditApi([], []),
    });

    expect(await screen.findByText('No entries match these filters.')).toBeDefined();
    expect(
      screen.getByRole('button', { name: 'Remove the actor filter: the chosen user' }),
    ).toBeDefined();
  });
});
