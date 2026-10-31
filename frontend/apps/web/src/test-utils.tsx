import { render } from '@testing-library/react';
import type { Role } from '@urbanpulse/domain';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { vi } from 'vitest';

import { routes } from './routing/routes';
import { SessionProvider, type SessionUser } from './session/SessionContext';

export function userWith(role: Role): SessionUser {
  return { id: 'user-1', name: 'Lucía Martín', email: 'lucia.martin@example.com', role };
}

type Answer = (request: Request) => Response | Promise<Response>;

/** Fake API answering by "METHOD /path"; an unexpected request fails the test. */
export function fakeApi(answers: Record<string, Answer>) {
  return vi.fn(async (request: Request) => {
    const path = new URL(request.url).pathname.replace('/api/v1', '');
    const answer = answers[`${request.method} ${path}`];
    if (!answer) {
      throw new Error(`Unexpected request ${request.method} ${path}`);
    }
    return answer(request);
  });
}

export function json(status: number, body: unknown): Response {
  const contentType = status >= 400 ? 'application/problem+json' : 'application/json';
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

interface RenderOptions {
  role?: Role | null;
  fetch?: (request: Request) => Promise<Response>;
}

/** Renders the real route table at `path`, signed in as `role` or signed out. */
export function renderApp(path: string, { role = null, fetch = fakeApi({}) }: RenderOptions = {}) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <SessionProvider initialUser={role ? userWith(role) : null} fetch={fetch}>
      <RouterProvider router={router} />
    </SessionProvider>,
  );
  return router;
}
