import { fireEvent, render, screen } from '@testing-library/react-native';

import { useSession } from '../session/SessionContext';
import { ProfileScreen } from './ProfileScreen';
import { UseWebScreen } from './UseWebScreen';

jest.mock('../session/SessionContext', () => ({ useSession: jest.fn() }));

const mockedUseSession = jest.mocked(useSession);
const signOut = jest.fn(async () => undefined);

// Clearing the tokens and the cached incidents on sign-out is SessionProvider's job, tested in
// SessionContext.test.tsx; these screens only have to ask for it.
function signedInAs(role: 'CITIZEN' | 'MUNICIPAL_OPERATOR') {
  mockedUseSession.mockReturnValue({
    user: {
      id: '7c2e4b1a-9d3f-4e8a-b6c5-1f0a2d3e4b5c',
      name: 'Lucía Martín',
      email: 'lucia.martin@example.com',
      role,
    },
    signOut,
  } as unknown as ReturnType<typeof useSession>);
}

beforeEach(() => {
  signOut.mockClear();
});

describe('ProfileScreen', () => {
  it('shows who is signed in and signs them out', async () => {
    signedInAs('CITIZEN');
    await render(<ProfileScreen />);

    expect(screen.getByText('Lucía Martín')).toBeOnTheScreen();
    expect(screen.getByText('lucia.martin@example.com')).toBeOnTheScreen();

    await fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));

    expect(signOut).toHaveBeenCalledTimes(1);
  });
});

describe('UseWebScreen', () => {
  it('sends a staff account to the website and lets it sign out', async () => {
    signedInAs('MUNICIPAL_OPERATOR');
    await render(<UseWebScreen />);

    expect(screen.getByRole('header', { name: 'Use the UrbanPulse website' })).toBeOnTheScreen();
    expect(screen.getByText(/Municipal staff work from the website/)).toBeOnTheScreen();

    await fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));

    expect(signOut).toHaveBeenCalledTimes(1);
  });
});
