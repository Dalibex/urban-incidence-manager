import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { fakeApi, renderApp } from '../test-utils';
import { PATHS } from './access';

describe('routes', () => {
  it.each([
    ['MUNICIPAL_OPERATOR', PATHS.inbox, 'Inbox'],
    ['TECHNICIAN', PATHS.workQueue, 'Work queue'],
    ['ADMINISTRATOR', PATHS.users, 'Users'],
    ['ANALYST', PATHS.reports, 'Reports'],
    ['CITIZEN', PATHS.useMobile, 'Use the UrbanPulse mobile app'],
  ] as const)('lands a %s on %s', async (role, path, heading) => {
    const router = renderApp(PATHS.home, { role });

    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeDefined();
    expect(router.state.location.pathname).toBe(path);
  });

  // #62, T5: a role that opens another role's route is redirected to its own screen.
  it.each([
    ['CITIZEN', PATHS.inbox, PATHS.useMobile, 'Use the UrbanPulse mobile app'],
    ['MUNICIPAL_OPERATOR', PATHS.users, PATHS.inbox, 'Inbox'],
    ['ANALYST', PATHS.workQueue, PATHS.reports, 'Reports'],
  ] as const)('redirects a %s from %s to %s', async (role, path, landing, heading) => {
    const router = renderApp(path, { role });

    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeDefined();
    expect(router.state.location.pathname).toBe(landing);
  });

  it('sends a visitor without a session to sign in', async () => {
    const router = renderApp(PATHS.inbox);

    expect(await screen.findByRole('heading', { name: 'Sign in to UrbanPulse' })).toBeDefined();
    expect(router.state.location.pathname).toBe(PATHS.signIn);
  });

  it('links the navbar only to the screens of the role', async () => {
    renderApp(PATHS.inbox, { role: 'MUNICIPAL_OPERATOR' });

    const nav = await screen.findByRole('navigation', { name: 'Main' });
    const links = nav.querySelectorAll('a');
    expect([...links].map((link) => link.textContent)).toEqual(['Inbox']);
  });

  it('shows the active user and signs them out on the API', async () => {
    const fetch = fakeApi({
      'DELETE /sessions/current': () => new Response(null, { status: 204 }),
    });
    const router = renderApp(PATHS.inbox, { role: 'MUNICIPAL_OPERATOR', fetch });
    expect(await screen.findByText('Lucía Martín')).toBeDefined();

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('heading', { name: 'Sign in to UrbanPulse' })).toBeDefined();
    expect(router.state.location.pathname).toBe(PATHS.signIn);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('shows not found for an unknown address', async () => {
    renderApp('/no-such-screen', { role: 'ANALYST' });

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeDefined();
  });
});
