import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { components } from '@urbanpulse/api-client';
import { formatInstant } from '@urbanpulse/ui';
import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { incidentPath } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';
import { PENDING_CONTEXT_CHECKS, PENDING_CONTEXT_INTERVAL_MS } from './incident-queries';

type UrbanContext = components['schemas']['UrbanContextResponse'];
type Answer = () => Response;

/** The Context section of the incident detail (#308, ADR-0018). */

const INCIDENT: components['schemas']['IncidentResponse'] = {
  id: '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e',
  title: 'Street light off',
  description: 'It has been off for three days.',
  status: 'REPORTED',
  priority: null,
  category: null,
  location: {
    latitude: 36.7213,
    longitude: -4.4214,
    accuracy: 12.5,
    address: null,
    neighborhood: null,
    district: null,
  },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-02T09:58:31Z',
  closedAt: null,
};

const CAPTURED_AT = '2026-11-02T09:58:47Z';

const RAINY: UrbanContext = {
  status: 'COMPLETE',
  capturedAt: CAPTURED_AT,
  weather: {
    condition: 'RAINY',
    source: {
      name: 'Open-Meteo',
      attribution: 'Weather data by Open-Meteo.com',
      url: 'https://open-meteo.com/',
    },
  },
};

const PENDING: UrbanContext = { status: 'PENDING', capturedAt: null, weather: null };
const ERROR: UrbanContext = { status: 'ERROR', capturedAt: null, weather: null };
const UNAVAILABLE: UrbanContext = { status: 'UNAVAILABLE', capturedAt: null, weather: null };
const PARTIAL_WITHOUT_WEATHER: UrbanContext = {
  status: 'PARTIAL',
  capturedAt: CAPTURED_AT,
  weather: null,
};

const EMPTY_PAGE = {
  content: [],
  page: { number: 0, size: 100, totalElements: 0, totalPages: 0 },
};

const SERVER_ERROR: Answer = () => json(500, { status: 500, code: 'INTERNAL_ERROR' });
const NO_NETWORK: Answer = () => {
  throw new TypeError('Failed to fetch');
};
const NOT_AVAILABLE_TEXT = 'The weather is not available for this incident.';

/** The detail of `INCIDENT`; `context` answers each read of its urban context. */
function detailApi(context: Answer) {
  return fakeApi({
    [`GET /incidents/${INCIDENT.id}`]: () => json(200, INCIDENT),
    [`GET /incidents/${INCIDENT.id}/status-changes`]: () => json(200, []),
    [`GET /incidents/${INCIDENT.id}/classification-changes`]: () => json(200, EMPTY_PAGE),
    [`GET /incidents/${INCIDENT.id}/comments`]: () => json(200, EMPTY_PAGE),
    [`GET /incidents/${INCIDENT.id}/attachments`]: () => json(200, []),
    [`GET /incidents/${INCIDENT.id}/urban-context`]: context,
  });
}

function contextReads(fetch: ReturnType<typeof detailApi>): number {
  return fetch.mock.calls.filter(([request]) =>
    new URL(request.url).pathname.endsWith('/urban-context'),
  ).length;
}

function openDetail(context: Answer) {
  const fetch = detailApi(context);
  renderApp(incidentPath(INCIDENT.id), { role: 'MUNICIPAL_OPERATOR', fetch });
  return fetch;
}

async function findContextPanel(): Promise<HTMLElement> {
  return screen.findByRole('region', { name: 'Context' });
}

describe('context section in the incident detail (#308)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  // T6: the weather arrived.
  it('shows the weather, when it was recorded and the source attribution', async () => {
    openDetail(() => json(200, RAINY));

    const panel = within(await findContextPanel());

    expect(await panel.findByText('Rainy')).toBeTruthy();
    expect(panel.getByText(formatInstant(CAPTURED_AT))).toBeTruthy();
    const link = panel.getByRole('link', { name: /Weather data by Open-Meteo\.com/ });
    expect(link.getAttribute('href')).toBe('https://open-meteo.com/');
  });

  it('shows the weather of a partial context like a complete one', async () => {
    openDetail(() => json(200, { ...RAINY, status: 'PARTIAL' }));

    const panel = within(await findContextPanel());

    expect(await panel.findByText('Rainy')).toBeTruthy();
    expect(panel.getByRole('link', { name: /Open-Meteo/ })).toBeTruthy();
  });

  it('shows the weather source in a new tab and says so to a screen reader', async () => {
    openDetail(() => json(200, RAINY));

    const panel = within(await findContextPanel());
    const link = await panel.findByRole('link', { name: /Open-Meteo/ });

    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toContain('noreferrer');
    expect(link.textContent).toContain('(opens in a new tab)');
    expect(link.getAttribute('aria-label')).toBeNull();
    expect(panel.getByRole('link', { name: /Open-Meteo\.com\s*\(opens in a new tab\)$/ })).toBe(
      link,
    );
  });

  // The section is a labelled region with its own h2, so a screen reader can jump to it.
  it('names the section with a Context heading of level 2', async () => {
    openDetail(() => json(200, RAINY));

    const panel = await findContextPanel();

    expect(within(panel).getByRole('heading', { level: 2, name: 'Context' })).toBeTruthy();
  });

  // T19 and T20: the weather is not there, and an unknown weather is never shown as a value.
  it.each([
    [
      'ERROR',
      ERROR,
      'Weather not available: the weather service could not answer for this incident.',
    ],
    [
      'UNAVAILABLE',
      UNAVAILABLE,
      'Weather not available: there is no weather for the time this incident was reported.',
    ],
    [
      'PARTIAL without weather',
      PARTIAL_WITHOUT_WEATHER,
      'Weather not available: there is no weather for the time this incident was reported.',
    ],
  ])(
    'says why there is no weather with the status %s, and shows no value',
    async (_n, ctx, text) => {
      openDetail(() => json(200, ctx));

      const panel = within(await findContextPanel());

      expect(await panel.findByText(text)).toBeTruthy();
      expect(panel.queryByText('Weather')).toBeNull();
      expect(panel.queryByText('Clear')).toBeNull();
      expect(panel.queryByRole('link')).toBeNull();
      expect(panel.queryByRole('button')).toBeNull();
    },
  );

  describe('while the weather is pending', () => {
    function useFakeClock() {
      vi.useFakeTimers({ shouldAdvanceTime: true });
    }

    async function advance(ms: number) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(ms);
      });
    }

    it('says it is getting the weather, and shows it once a later read has it', async () => {
      useFakeClock();
      let answer: UrbanContext = PENDING;
      const fetch = openDetail(() => json(200, answer));

      const panel = within(await findContextPanel());
      const status = await panel.findByRole('status');
      expect(status.textContent).toBe('Getting the weather…');
      expect(panel.queryByText('Rainy')).toBeNull();

      answer = RAINY;
      await advance(PENDING_CONTEXT_INTERVAL_MS);

      expect(await panel.findByText('Rainy')).toBeTruthy();
      // The same region, so a screen reader hears the change instead of a new element.
      expect(panel.getByRole('status')).toBe(status);
      expect(status.textContent).toContain('Rainy');
      expect(status.textContent).not.toContain('Getting the weather…');
      expect(contextReads(fetch)).toBe(2);
    });

    it('stops reading by itself when a read of the pending weather fails, and offers to try again', async () => {
      useFakeClock();
      let fails = false;
      const fetch = openDetail(() => (fails ? SERVER_ERROR() : json(200, PENDING)));

      const panel = within(await findContextPanel());
      await panel.findByText('Getting the weather…');
      fails = true;
      await advance(PENDING_CONTEXT_INTERVAL_MS);

      expect((await panel.findByRole('alert')).textContent).toContain(
        'The weather could not be loaded.',
      );
      const reads = contextReads(fetch);
      await advance(PENDING_CONTEXT_INTERVAL_MS * 4);
      expect(contextReads(fetch)).toBe(reads);
      expect(panel.getByRole('button', { name: 'Try again' })).toBeTruthy();
      expect(panel.queryByText('Getting the weather…')).toBeNull();
    });

    // T6 limit: it must not poll for ever.
    it('stops reading after the last check, and offers to check again once', async () => {
      useFakeClock();
      const fetch = openDetail(() => json(200, PENDING));

      const panel = within(await findContextPanel());
      await panel.findByText('Getting the weather…');
      for (let check = 0; check < PENDING_CONTEXT_CHECKS; check++) {
        await advance(PENDING_CONTEXT_INTERVAL_MS);
      }

      expect(await panel.findByText('The weather has not arrived yet.')).toBeTruthy();
      expect(contextReads(fetch)).toBe(PENDING_CONTEXT_CHECKS + 1);

      await advance(PENDING_CONTEXT_INTERVAL_MS * 4);
      expect(contextReads(fetch)).toBe(PENDING_CONTEXT_CHECKS + 1);

      await userEvent.click(panel.getByRole('button', { name: 'Check again' }));
      await vi.waitFor(() => {
        expect(contextReads(fetch)).toBe(PENDING_CONTEXT_CHECKS + 2);
      });
    });

    it('shows the weather when checking again finds it', async () => {
      useFakeClock();
      let answer: UrbanContext = PENDING;
      openDetail(() => json(200, answer));

      const panel = within(await findContextPanel());
      await panel.findByText('Getting the weather…');
      for (let check = 0; check < PENDING_CONTEXT_CHECKS; check++) {
        await advance(PENDING_CONTEXT_INTERVAL_MS);
      }
      await panel.findByText('The weather has not arrived yet.');

      answer = RAINY;
      await userEvent.click(panel.getByRole('button', { name: 'Check again' }));

      expect(await panel.findByText('Rainy')).toBeTruthy();
      expect(panel.queryByText('The weather has not arrived yet.')).toBeNull();
      // *Check again* is gone, so the keyboard moves to the section instead of falling to the body.
      expect(document.activeElement).toBe(
        screen.getByRole('heading', { level: 2, name: 'Context' }),
      );
    });

    it('keeps the focus on the button when checking again finds no weather yet', async () => {
      useFakeClock();
      openDetail(() => json(200, PENDING));

      const panel = within(await findContextPanel());
      await panel.findByText('Getting the weather…');
      for (let check = 0; check < PENDING_CONTEXT_CHECKS; check++) {
        await advance(PENDING_CONTEXT_INTERVAL_MS);
      }
      await panel.findByText('The weather has not arrived yet.');

      await userEvent.click(panel.getByRole('button', { name: 'Check again' }));

      await vi.waitFor(() => {
        expect(panel.getByRole('button', { name: 'Check again' })).toBeTruthy();
      });
      expect(document.activeElement).not.toBe(screen.getByRole('heading', { name: 'Context' }));
    });
  });

  // T19: the endpoint fails.
  it('shows an alert with a retry when the weather fails, and keeps the rest of the detail', async () => {
    let fails = true;
    openDetail(() => (fails ? SERVER_ERROR() : json(200, RAINY)));

    const panel = within(await findContextPanel());
    expect((await panel.findByRole('alert')).textContent).toContain(
      'The weather could not be loaded.',
    );
    expect(screen.getByRole('heading', { name: 'Street light off' })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Report' })).toBeTruthy();

    fails = false;
    await userEvent.click(panel.getByRole('button', { name: 'Try again' }));

    expect(await panel.findByText('Rainy')).toBeTruthy();
    expect(panel.queryByRole('alert')).toBeNull();
  });

  it('says the server cannot be reached when there is no network', async () => {
    openDetail(NO_NETWORK);

    const panel = within(await findContextPanel());

    expect((await panel.findByRole('alert')).textContent).toContain(
      'The server cannot be reached. Check your connection and try again.',
    );
    expect(screen.getByRole('heading', { name: 'Street light off' })).toBeTruthy();
  });

  // T20: the role or the incident is refused; asking again gives the same answer.
  it.each([403, 404])(
    'says the weather is not available on a %i, with nothing to retry',
    async (status) => {
      const fetch = openDetail(() => json(status, { status }));

      const panel = within(await findContextPanel());

      expect(await panel.findByText(NOT_AVAILABLE_TEXT)).toBeTruthy();
      expect(panel.queryByRole('button')).toBeNull();
      expect(panel.queryByRole('alert')).toBeNull();
      expect(screen.getByRole('heading', { name: 'Street light off' })).toBeTruthy();
      expect(contextReads(fetch)).toBe(1);
    },
  );
});
