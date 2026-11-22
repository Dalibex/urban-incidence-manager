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
type Answer = () => Response | Promise<Response>;

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
    addressStatus: 'UNAVAILABLE' as const,
    addressSource: null,
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

// RESPONSE_TIMEOUT_MS of the API client (packages/api-client/src/session.ts), which it does not
// export, and the retries of a transient failure in query-client.ts.
const NO_ANSWER_TIMEOUT_MS = 30_000;
const CLIENT_RETRIES = 2;

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

  // T6 (#309): every staff role that opens the detail sees the weather, not only the operator.
  it.each(['ADMINISTRATOR', 'TECHNICIAN'] as const)(
    'shows the weather to the %s too',
    async (role) => {
      renderApp(incidentPath(INCIDENT.id), { role, fetch: detailApi(() => json(200, RAINY)) });

      const panel = within(await findContextPanel());

      expect(await panel.findByText('Rainy')).toBeTruthy();
      expect(panel.getByRole('link', { name: /Weather data by Open-Meteo\.com/ })).toBeTruthy();
    },
  );

  // T6 (#309): each condition of the contract has its own words, none is left as its code.
  it.each([
    ['SUNNY', 'Clear'],
    ['PARTLY_CLOUDY', 'Partly cloudy'],
    ['CLOUDY', 'Cloudy'],
    ['FOGGY', 'Foggy'],
    ['RAINY', 'Rainy'],
    ['SNOWY', 'Snowy'],
    ['STORMY', 'Stormy'],
    ['WINDY', 'Windy'],
  ] as const)('shows %s as "%s"', async (condition, label) => {
    openDetail(() => json(200, { ...RAINY, weather: { ...RAINY.weather!, condition } }));

    const panel = within(await findContextPanel());

    expect(await panel.findByText(label)).toBeTruthy();
    expect(panel.queryByText(condition)).toBeNull();
  });

  // T19 (#309): a condition the API adds before this app knows it is never a blank value.
  it('shows a condition it does not know as not available, never blank', async () => {
    openDetail(() =>
      json(200, { ...RAINY, weather: { ...RAINY.weather!, condition: 'HAIL' as 'RAINY' } }),
    );

    const panel = within(await findContextPanel());
    const value = (await panel.findByText('Weather')).nextElementSibling;

    expect(value?.textContent?.trim()).not.toBe('');
    expect(value?.textContent).not.toContain('HAIL');
  });

  // T19 (#309): the weather is not there, and an unknown weather is never shown as a value.
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
      // T21 (#309): one sentence with the weather and its time; Narrator read a list without them.
      expect(status.textContent).toBe(`Weather: Rainy, recorded ${formatInstant(CAPTURED_AT)}.`);
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
      expect(document.activeElement).toBe(panel.getByRole('button', { name: 'Check again' }));
    });

    // A browser moves the focus of a `disabled` button to the page, and jsdom does not, so the
    // button must stay enabled while it checks and ignore a second click instead.
    it('keeps the button focusable while it checks, and a second click reads nothing more', async () => {
      useFakeClock();
      let release: () => void = () => {};
      let holdNextRead = false;
      const fetch = openDetail(() =>
        holdNextRead
          ? new Promise<Response>((resolve) => {
              release = () => resolve(json(200, PENDING));
            })
          : json(200, PENDING),
      );

      const panel = within(await findContextPanel());
      await panel.findByText('Getting the weather…');
      for (let check = 0; check < PENDING_CONTEXT_CHECKS; check++) {
        await advance(PENDING_CONTEXT_INTERVAL_MS);
      }
      await panel.findByText('The weather has not arrived yet.');
      const readsBefore = contextReads(fetch);

      holdNextRead = true;
      await userEvent.click(panel.getByRole('button', { name: 'Check again' }));
      const checking = await panel.findByRole('button', { name: 'Checking…' });

      expect(checking.hasAttribute('disabled')).toBe(false);
      expect(checking.getAttribute('aria-disabled')).toBe('true');
      expect(document.activeElement).toBe(checking);
      await userEvent.click(checking);
      expect(contextReads(fetch)).toBe(readsBefore + 1);

      holdNextRead = false;
      await act(async () => release());
      expect(await panel.findByRole('button', { name: 'Check again' })).toBe(
        document.activeElement,
      );
    });
  });

  // T20 (#309): the endpoint fails.
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

  // T20 (#309): the endpoint never answers, as a paused server does. The client gives up after its
  // response timeout and every retry, and the section says so with a retry while the rest works.
  it('shows an alert with a retry when the weather never answers, and keeps the rest of the detail', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let hangs = true;
    openDetail(() => (hangs ? new Promise<Response>(() => {}) : json(200, RAINY)));

    const panel = within(await findContextPanel());
    expect(screen.getByRole('heading', { name: 'Street light off' })).toBeTruthy();
    expect(panel.getByText('Loading the weather…')).toBeTruthy();

    // The first read and its retries each wait for the client's response timeout.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(NO_ANSWER_TIMEOUT_MS * (CLIENT_RETRIES + 1));
    });

    expect((await panel.findByRole('alert')).textContent).toContain(
      'The server cannot be reached. Check your connection and try again.',
    );
    expect(screen.getByRole('region', { name: 'Report' })).toBeTruthy();

    hangs = false;
    await userEvent.click(panel.getByRole('button', { name: 'Try again' }));

    expect(await panel.findByText('Rainy')).toBeTruthy();
    expect(panel.queryByRole('alert')).toBeNull();
  });

  // The role or the incident is refused; asking again gives the same answer.
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
