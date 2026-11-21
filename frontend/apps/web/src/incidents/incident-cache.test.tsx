import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { components } from '@urbanpulse/api-client';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';

import { useAuditEntryPage } from '../audit/audit-queries';
import { createQueryClient } from '../query-client';
import { SessionProvider, useSession } from '../session/SessionContext';
import { fakeApi, json, userWith } from '../test-utils';
import { useCreateUser, useUserPage } from '../users/user-queries';
import {
  useAcceptIncident,
  useIncident,
  useIncidentStatistics,
  useLatestIncidents,
  useTechnicians,
} from './incident-queries';

type Incident = components['schemas']['IncidentResponse'];

/**
 * What the cache does when an incident or a user changes (#287). The screens read through the hooks,
 * so these tests render the hooks with the real session and cache, and a fake API whose answers the
 * test holds back where the order of the answers matters.
 */

const INCIDENT_ID = '3f1c2a9e-7b4d-4c1a-9e2f-5d6b7a8c9d0e';

const ASSIGNED: Incident = {
  id: INCIDENT_ID,
  title: 'Street light off',
  description: 'The street light in front of number 12 has been off for three days.',
  status: 'ASSIGNED',
  priority: 'HIGH',
  category: null,
  location: { latitude: 36.7213, longitude: -4.4214 },
  reportedAt: '2026-11-02T09:58:31Z',
  updatedAt: '2026-11-04T08:00:00Z',
  closedAt: null,
  version: 3,
} as Incident;

const IN_PROGRESS: Incident = { ...ASSIGNED, status: 'IN_PROGRESS', version: 4 };

/** An answer the test releases by hand, so it decides in which order the API answers. */
function heldAnswer() {
  let release: (response: Response) => void = () => {};
  const answer = new Promise<Response>((resolve) => {
    release = resolve;
  });
  return { answer: () => answer, release };
}

const NO_FILTERS = {};

function statistics(total: number) {
  return json(200, { total, byStatus: [], byPriority: [], byCategory: [], byDepartment: [] });
}

function wrapperFor(queryClient: QueryClient, fetch: (request: Request) => Promise<Response>) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <SessionProvider initialUser={userWith('MUNICIPAL_OPERATOR')} fetch={fetch}>
          {children}
        </SessionProvider>
      </QueryClientProvider>
    );
  };
}

describe('cache after a change of an incident or a user (#287)', () => {
  describe('A: what a change makes other screens read again', () => {
    it('shows the dashboard counts and the technicians as the server has them after an incident action', async () => {
      // Arrange
      const server = { total: 1, latestTitle: 'Before', technician: 'Ana' };
      const fetch = fakeApi({
        'GET /incident-statistics': () => statistics(server.total),
        'GET /incidents': () =>
          json(200, {
            content: [{ ...ASSIGNED, title: server.latestTitle }],
            page: { number: 0, size: 5, totalElements: 1, totalPages: 1 },
          }),
        'GET /technicians': () => json(200, [{ id: 't1', name: server.technician }]),
        [`POST /incidents/${INCIDENT_ID}/acceptance`]: () => json(200, IN_PROGRESS),
        [`GET /incidents/${INCIDENT_ID}`]: () => json(200, IN_PROGRESS),
      });
      const { result } = renderHook(
        () => ({
          statistics: useIncidentStatistics(NO_FILTERS),
          latest: useLatestIncidents(NO_FILTERS),
          technicians: useTechnicians(),
          accept: useAcceptIncident(INCIDENT_ID),
        }),
        { wrapper: wrapperFor(createQueryClient({ retryDelay: 0 }), fetch) },
      );
      await waitFor(() => expect(result.current.technicians.data).toBeDefined());
      await waitFor(() => expect(result.current.latest.data).toBeDefined());
      await waitFor(() => expect(result.current.statistics.data).toBeDefined());
      server.total = 2;
      server.latestTitle = 'After';
      server.technician = 'Ana, now busy';

      // Act
      await act(() => result.current.accept.mutateAsync({ version: 3 }));

      // Assert
      await waitFor(() => expect(result.current.statistics.data?.total).toBe(2));
      expect(result.current.latest.data?.content[0]?.title).toBe('After');
      expect(result.current.technicians.data?.[0]?.name).toBe('Ana, now busy');
    });

    it('shows the technicians and the audit entries as the server has them after a user is created', async () => {
      // Arrange
      const server = { technician: 'Ana', audit: 1 };
      const created = {
        id: 'u2',
        name: 'Pedro',
        email: 'pedro@example.com',
        role: 'TECHNICIAN',
        enabled: true,
      };
      const fetch = fakeApi({
        'GET /users': () =>
          json(200, {
            content: [],
            page: { number: 0, size: 20, totalElements: 0, totalPages: 0 },
          }),
        'GET /technicians': () => json(200, [{ id: 't1', name: server.technician }]),
        'GET /audit-entries': () =>
          json(200, {
            content: [],
            page: { number: 0, size: 20, totalElements: server.audit, totalPages: 1 },
          }),
        'POST /users': () => json(201, created),
      });
      const { result } = renderHook(
        () => ({
          users: useUserPage(0, {}),
          technicians: useTechnicians(),
          audit: useAuditEntryPage(0, {}),
          create: useCreateUser(),
        }),
        { wrapper: wrapperFor(createQueryClient({ retryDelay: 0 }), fetch) },
      );
      await waitFor(() => expect(result.current.technicians.data).toBeDefined());
      await waitFor(() => expect(result.current.audit.data).toBeDefined());
      server.technician = 'Ana, Pedro';
      server.audit = 2;

      // Act
      await act(() =>
        result.current.create.mutateAsync({
          name: 'Pedro',
          email: 'pedro@example.com',
          role: 'TECHNICIAN',
          password: 'a-long-enough-password',
        } as components['schemas']['CreateUserRequest']),
      );

      // Assert
      await waitFor(() => expect(result.current.technicians.data?.[0]?.name).toBe('Ana, Pedro'));
      expect(result.current.audit.data?.page.totalElements).toBe(2);
    });
  });

  describe('B: a reload of the detail still in flight when an action answers', () => {
    it('keeps the answer of the action when the older reload answers after it', async () => {
      // Arrange
      const staleReload = heldAnswer();
      let detailReads = 0;
      const fetch = fakeApi({
        [`GET /incidents/${INCIDENT_ID}`]: () =>
          ++detailReads === 1 ? json(200, ASSIGNED) : staleReload.answer(),
        [`POST /incidents/${INCIDENT_ID}/acceptance`]: () => json(200, IN_PROGRESS),
      });
      const queryClient = createQueryClient({ retryDelay: 0 });
      const { result } = renderHook(
        () => ({ detail: useIncident(INCIDENT_ID), accept: useAcceptIncident(INCIDENT_ID) }),
        { wrapper: wrapperFor(queryClient, fetch) },
      );
      await waitFor(() => expect(result.current.detail.data?.status).toBe('ASSIGNED'));
      // A reload that left before the action, and that the server answers with the old incident.
      void queryClient.invalidateQueries({ queryKey: ['incidents', INCIDENT_ID] });
      await waitFor(() => expect(detailReads).toBe(2));
      await act(() => result.current.accept.mutateAsync({ version: 3 }));

      // Act
      await act(async () => {
        staleReload.release(json(200, ASSIGNED));
        await staleReload.answer();
      });

      // Assert
      expect(result.current.detail.data).toMatchObject({ status: 'IN_PROGRESS', version: 4 });
    });
  });

  describe('C: an action still in flight when the user signs out', () => {
    it('leaves the cache empty when the answer of the action comes after the sign-out', async () => {
      // Arrange
      const heldAction = heldAnswer();
      const fetch = fakeApi({
        [`POST /incidents/${INCIDENT_ID}/acceptance`]: heldAction.answer,
        'DELETE /sessions/current': () => new Response(null, { status: 204 }),
      });
      const queryClient = createQueryClient({ retryDelay: 0 });
      const { result } = renderHook(
        () => ({ session: useSession(), accept: useAcceptIncident(INCIDENT_ID) }),
        { wrapper: wrapperFor(queryClient, fetch) },
      );
      const action = result.current.accept.mutateAsync({ version: 3 });
      await act(() => result.current.session.signOut());

      // Act
      await act(async () => {
        heldAction.release(json(200, IN_PROGRESS));
        await action;
      });

      // Assert
      expect(queryClient.getQueryData(['incidents', INCIDENT_ID])).toBeUndefined();
    });
  });
});
