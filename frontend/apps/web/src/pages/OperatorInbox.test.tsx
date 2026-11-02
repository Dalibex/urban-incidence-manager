import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { describe, expect, it } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

type IncidentSummary = components['schemas']['IncidentSummaryResponse'];
type Incident = components['schemas']['IncidentResponse'];
type StatusChange = components['schemas']['StatusChangeResponse'];

/**
 * Acceptance tests of #69 for the operator inbox (#46). They go through the real route table with
 * a fake API, so they only rely on what the contract and the issue fix: the columns, the Manage
 * action, and "not available" for missing values. The screens decide everything else.
 */

const DEFAULT_PAGE_SIZE = 20;
const TOTAL_INCIDENTS = 25;
const DEFAULT_SORT = 'reportedAt,desc';

const STREETLIGHT_ID = '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e';
const OPERATOR = { id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c', name: 'Lucía Martín' } as const;
const CITIZEN = { id: '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d', name: 'Pedro Ruiz' } as const;

/** Incidents newest first, as the API orders them by default. */
const INCIDENTS: IncidentSummary[] = Array.from({ length: TOTAL_INCIDENTS }, (_, index) => {
  const number = String(index + 1).padStart(2, '0');
  const reportedAt = new Date(Date.UTC(2026, 9, 31, 23, 0) - index * 3_600_000).toISOString();
  return {
    id: `d1000000-0000-4000-8000-0000000000${number}`,
    title: `Incident ${number}`,
    status: 'REPORTED',
    priority: null,
    category: null,
    reportedAt,
    updatedAt: reportedAt,
  };
});

const STREETLIGHT_SUMMARY: IncidentSummary = {
  id: STREETLIGHT_ID,
  title: 'Street light off',
  status: 'VALIDATED',
  priority: 'HIGH',
  category: { id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d', name: 'Street lighting' },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T10:20:00Z',
};

const STREETLIGHT: Incident = {
  ...STREETLIGHT_SUMMARY,
  description: 'The street light in front of number 12 has been off for three days.',
  location: {
    latitude: 36.7213,
    longitude: -4.4214,
    accuracy: 12.5,
    address: 'Calle Larios 12',
    neighborhood: 'Centro Histórico',
    district: 'Centro',
  },
  closedAt: null,
  version: 2,
};

const STREETLIGHT_HISTORY: StatusChange[] = [
  {
    id: '5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b1',
    instant: '2026-11-02T09:58:31Z',
    previousStatus: null,
    newStatus: 'REPORTED',
    reason: null,
    actor: { ...CITIZEN, role: 'CITIZEN' },
    visibleToCitizen: true,
  },
  {
    id: '5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b2',
    instant: '2026-11-02T10:05:00Z',
    previousStatus: 'REPORTED',
    newStatus: 'REPORTED',
    reason: 'Checked with the lighting team: the whole street is affected.',
    actor: { ...OPERATOR, role: 'MUNICIPAL_OPERATOR' },
    visibleToCitizen: false,
  },
  {
    id: '5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b3',
    instant: '2026-11-02T10:20:00Z',
    previousStatus: 'REPORTED',
    newStatus: 'VALIDATED',
    reason: null,
    actor: { ...OPERATOR, role: 'MUNICIPAL_OPERATOR' },
    visibleToCitizen: true,
  },
];

/** Answers `GET /incidents` by slicing `incidents` with the page and size the client asks for. */
function pagedIncidents(incidents: IncidentSummary[], requests: URLSearchParams[]) {
  return (request: Request) => {
    const query = new URL(request.url).searchParams;
    requests.push(query);
    const page = Number(query.get('page') ?? 0);
    const size = Number(query.get('size') ?? DEFAULT_PAGE_SIZE);
    return json(200, {
      content: incidents.slice(page * size, (page + 1) * size),
      page: {
        number: page,
        size,
        totalElements: incidents.length,
        totalPages: Math.ceil(incidents.length / size),
      },
    });
  };
}

function inboxApi(incident: Incident, history: StatusChange[]) {
  const summary: IncidentSummary = {
    id: incident.id,
    title: incident.title,
    status: incident.status,
    priority: incident.priority,
    category: incident.category,
    reportedAt: incident.reportedAt,
    updatedAt: incident.updatedAt,
  };
  return fakeApi({
    'GET /incidents': pagedIncidents([summary], []),
    [`GET /incidents/${incident.id}`]: () => json(200, incident),
    [`GET /incidents/${incident.id}/status-changes`]: () => json(200, history),
  });
}

/** A control can be a link or a button; the test only cares about its accessible name. */
function control(container: HTMLElement, name: RegExp): HTMLElement | null {
  const scope = within(container);
  return scope.queryByRole('link', { name }) ?? scope.queryByRole('button', { name });
}

function isUsable(element: HTMLElement | null): element is HTMLElement {
  return element !== null && !element.hasAttribute('disabled') && element.ariaDisabled !== 'true';
}

/** Titles of the data rows of the list, in the order they are shown. */
async function shownTitles(expectedFirst: string): Promise<string[]> {
  const table = await screen.findByRole('table');
  await within(table).findByText(expectedFirst);
  const [, ...rows] = within(table).getAllByRole('row');
  return rows.map((row) => {
    const title = INCIDENTS.find(({ title }) => within(row).queryByText(title));
    expect(title, `row without a known title: ${row.textContent}`).toBeDefined();
    return title!.title;
  });
}

async function openDetail(title: string) {
  const row = (await screen.findByText(title)).closest('tr');
  expect(row, `no row for ${title}`).not.toBeNull();
  const manage = control(row!, /manage/i);
  expect(manage, `no Manage action in the row of ${title}`).not.toBeNull();
  await userEvent.click(manage!);
}

describe('operator inbox', () => {
  // #69, T4: the list goes page by page in the API's order, with no row repeated or skipped.
  it('pages through every incident newest first without repeating rows', async () => {
    const requests: URLSearchParams[] = [];
    const fetch = fakeApi({ 'GET /incidents': pagedIncidents(INCIDENTS, requests) });
    renderApp(PATHS.inbox, { role: 'MUNICIPAL_OPERATOR', fetch });

    const seen = await shownTitles(INCIDENTS[0]!.title);
    let next = control(document.body, /next/i);
    while (isUsable(next)) {
      const firstOfNextPage = INCIDENTS[seen.length]!.title;
      await userEvent.click(next);
      seen.push(...(await shownTitles(firstOfNextPage)));
      next = control(document.body, /next/i);
    }

    expect(seen).toEqual(INCIDENTS.map(({ title }) => title));
    expect(requests.length).toBeGreaterThan(1);
    // Stable ordering: every page asks for the same order (newest first) and the same size.
    for (const query of requests) {
      expect(query.get('sort') ?? DEFAULT_SORT).toBe(DEFAULT_SORT);
      expect(query.get('size')).toBe(requests[0]!.get('size'));
    }
    expect(requests.map((query) => Number(query.get('page') ?? 0))).toEqual(
      requests.map((_, page) => page),
    );
  });

  it('goes back to the previous page', async () => {
    const requests: URLSearchParams[] = [];
    const fetch = fakeApi({ 'GET /incidents': pagedIncidents(INCIDENTS, requests) });
    renderApp(PATHS.inbox, { role: 'MUNICIPAL_OPERATOR', fetch });
    const firstPage = await shownTitles(INCIDENTS[0]!.title);

    await userEvent.click(control(document.body, /next/i)!);
    await shownTitles(INCIDENTS[firstPage.length]!.title);
    await userEvent.click(control(document.body, /previous|prev/i)!);

    expect(await shownTitles(INCIDENTS[0]!.title)).toEqual(firstPage);
  });

  it('shows the date, title, category, status and priority of each incident', async () => {
    renderApp(PATHS.inbox, { role: 'MUNICIPAL_OPERATOR', fetch: inboxApi(STREETLIGHT, []) });

    const table = await screen.findByRole('table');
    for (const column of [/date|reported/i, /title/i, /category/i, /status/i, /priority/i]) {
      expect(within(table).getByRole('columnheader', { name: column })).toBeDefined();
    }
    const row = (await within(table).findByText('Street light off')).closest('tr')!;
    expect(within(row).getByText('Street lighting')).toBeDefined();
    expect(within(row).getByText(/validated/i)).toBeDefined();
    expect(within(row).getByText(/high/i)).toBeDefined();
  });

  // #69, T5: Manage opens the detail with the report data and the whole history.
  it('opens the detail with the report data and the full history, internal entries included', async () => {
    renderApp(PATHS.inbox, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: inboxApi(STREETLIGHT, STREETLIGHT_HISTORY),
    });

    await openDetail('Street light off');

    expect(await screen.findByRole('heading', { name: 'Street light off' })).toBeDefined();
    expect(screen.getByText(STREETLIGHT.description)).toBeDefined();
    expect(screen.getAllByText('Street lighting').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/high/i).length).toBeGreaterThan(0);
    expect(screen.getByText(/36\.7213/)).toBeDefined();
    expect(screen.getByText(/-4\.4214/)).toBeDefined();
    expect(screen.getByText(/12\.5/)).toBeDefined();
    expect(screen.getByText(/Calle Larios 12/)).toBeDefined();
    expect(screen.getByText(/Centro Histórico/)).toBeDefined();
    // The internal entry (visibleToCitizen: false) and who made each change are shown to staff.
    expect(screen.getByText(STREETLIGHT_HISTORY[1]!.reason!)).toBeDefined();
    expect(screen.getAllByText(OPERATOR.name).length).toBeGreaterThan(0);
    expect(screen.getAllByText(CITIZEN.name).length).toBeGreaterThan(0);
  });

  // #69, T6: an unknown place is "not available", never empty, "null" or a made-up value.
  it('shows missing accuracy, address, neighbourhood and district as not available', async () => {
    const unlocated: Incident = {
      ...STREETLIGHT,
      category: null,
      location: {
        latitude: 36.7213,
        longitude: -4.4214,
        accuracy: null,
        address: null,
        neighborhood: null,
        district: null,
      },
    };
    renderApp(PATHS.inbox, {
      role: 'MUNICIPAL_OPERATOR',
      fetch: inboxApi(unlocated, STREETLIGHT_HISTORY.slice(0, 1)),
    });

    await openDetail('Street light off');

    await screen.findByRole('heading', { name: 'Street light off' });
    expect(screen.getAllByText(/not available/i).length).toBeGreaterThanOrEqual(4);
    expect(document.body.textContent).not.toMatch(/\bnull\b|\bundefined\b|NaN/);
  });
});
