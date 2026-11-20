import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { createQueryClient } from '../query-client';
import { incidentPath } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

/**
 * The detail is an article labelled by its heading (#180): it keeps a name while the incident
 * loads and after it failed to load, when there is no title to show yet.
 */

const INCIDENT_ID = '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e';

const SERVER_ERROR = {
  type: 'about:blank',
  title: 'Internal Server Error',
  status: 500,
  code: 'INTERNAL_ERROR',
};

function api(detail: () => Response | Promise<Response>) {
  return fakeApi({ [`GET /incidents/${INCIDENT_ID}`]: detail });
}

describe('the heading of the incident detail', () => {
  it('names the page while the incident loads', async () => {
    renderApp(incidentPath(INCIDENT_ID), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(() => new Promise<Response>(() => {})),
    });

    expect(await screen.findByRole('article', { name: 'Incident' })).toBeDefined();
    expect(screen.getByRole('heading', { level: 1, name: 'Incident' })).toBeDefined();
  });

  it('names the page when the incident could not be loaded', async () => {
    renderApp(incidentPath(INCIDENT_ID), {
      role: 'MUNICIPAL_OPERATOR',
      fetch: api(() => json(500, SERVER_ERROR)),
      queryClient: createQueryClient({ retryDelay: 0 }),
    });

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The incident could not be loaded.',
    );
    expect(screen.getByRole('article', { name: 'Incident' })).toBeDefined();
  });
});
