import {
  deletePushToken,
  getNotificationSettings,
  registerPushToken,
  updateNotificationSettings,
  type ApiResult,
  type components,
} from '@urbanpulse/api-client';
import { act, fireEvent, screen, waitFor } from '@testing-library/react-native';

import { askPushPermission, readPushToken } from '../notifications/push';
import { failed, ok, renderWithQueries } from '../test-utils';
import { ProfileScreen } from './ProfileScreen';

jest.mock('@urbanpulse/api-client', () => ({
  getNotificationSettings: jest.fn(),
  updateNotificationSettings: jest.fn(),
  registerPushToken: jest.fn(),
  deletePushToken: jest.fn(),
}));
jest.mock('../notifications/push', () => ({
  readPushPermission: jest.fn(),
  askPushPermission: jest.fn(),
  readPushToken: jest.fn(),
}));
jest.mock('../session/SessionContext', () => ({
  useSession: () => ({
    api: {},
    user: { id: 'u-1', name: 'Lucía Martín', email: 'lucia@example.com', role: 'CITIZEN' },
    signOut: jest.fn(),
  }),
}));

type Settings = components['schemas']['NotificationSettings'];

const DEVICE_TOKEN = 'ExponentPushToken[device-1]';
const NETWORK_FAILURE = failed<never>({ kind: 'network' });

const mockedGet = jest.mocked(getNotificationSettings);
const mockedUpdate = jest.mocked(updateNotificationSettings);
const mockedRegister = jest.mocked(registerPushToken);
const mockedDelete = jest.mocked(deletePushToken);

beforeEach(() => {
  jest.resetAllMocks();
  jest.mocked(askPushPermission).mockResolvedValue('granted');
  jest.mocked(readPushToken).mockResolvedValue(DEVICE_TOKEN);
  mockedRegister.mockResolvedValue(ok(undefined));
  mockedDelete.mockResolvedValue(ok(undefined));
});

/** The server keeps what a `PATCH` sets, as the contract says: absent properties keep their value. */
function serverHolding(initial: Settings) {
  let stored = initial;
  mockedGet.mockImplementation(async () => ok(stored));
  mockedUpdate.mockImplementation(async (_api, change) => {
    stored = { ...stored, ...change };
    return ok(stored);
  });
}

/** A call that stays in flight until the test settles it. */
function inFlight<T>() {
  let settle!: (result: ApiResult<T>) => void;
  const promise = new Promise<ApiResult<T>>((resolve) => {
    settle = resolve;
  });
  return { promise, settle };
}

const pushSwitch = () => screen.getByRole('switch', { name: 'Push notifications' });
const emailSwitch = () => screen.getByRole('switch', { name: 'Email' });

/** The screen with its settings loaded. */
async function renderProfile(initial: Settings = { push: false, email: true }) {
  serverHolding(initial);
  await renderWithQueries(<ProfileScreen />);
  await screen.findByRole('switch', { name: 'Push notifications' });
}

describe('Notifications section', () => {
  describe('loading the settings', () => {
    it('shows the stored values on two labelled switches', async () => {
      // Arrange & Act
      await renderProfile({ push: false, email: true });

      // Assert
      expect(pushSwitch()).not.toBeChecked();
      expect(emailSwitch()).toBeChecked();
    });

    it('shows a loading indicator and no switch while the settings load', async () => {
      // Arrange
      mockedGet.mockReturnValue(inFlight<Settings>().promise);

      // Act
      await renderWithQueries(<ProfileScreen />);

      // Assert
      expect(
        screen.getByRole('progressbar', { name: 'Loading your notification settings' }),
      ).toBeOnTheScreen();
      expect(screen.queryByRole('switch')).not.toBeOnTheScreen();
    });

    it('offers to try again when the settings cannot be loaded, and shows them after a good retry', async () => {
      // Arrange
      mockedGet
        .mockResolvedValueOnce(NETWORK_FAILURE)
        .mockResolvedValue(ok({ push: true, email: false }));
      await renderWithQueries(<ProfileScreen />);

      // Act
      await fireEvent.press(await screen.findByRole('button', { name: 'Try again' }));

      // Assert
      expect(await screen.findByRole('switch', { name: 'Push notifications' })).toBeChecked();
      expect(emailSwitch()).not.toBeChecked();
    });
  });

  describe('turning push on', () => {
    it('registers the device token, then sends the change, and switches on only after both', async () => {
      // Arrange
      await renderProfile({ push: false, email: true });

      // Act
      await fireEvent(pushSwitch(), 'valueChange', true);

      // Assert
      await waitFor(() => expect(pushSwitch()).toBeChecked());
      expect(mockedRegister.mock.calls[0][1]).toBe(DEVICE_TOKEN);
      expect(mockedUpdate).toHaveBeenCalledTimes(1);
      expect(mockedUpdate.mock.calls[0][1]).toEqual({ push: true });
      expect(mockedRegister.mock.invocationCallOrder[0]).toBeLessThan(
        mockedUpdate.mock.invocationCallOrder[0],
      );
    });

    it('keeps the switch off until the server confirms the change', async () => {
      // Arrange
      await renderProfile({ push: false, email: true });
      const update = inFlight<Settings>();
      mockedUpdate.mockReturnValueOnce(update.promise);

      // Act
      await fireEvent(pushSwitch(), 'valueChange', true);
      await act(async () => undefined);

      // Assert
      expect(mockedUpdate).toHaveBeenCalledTimes(1);
      expect(pushSwitch()).not.toBeChecked();
    });

    it('stays off and explains when the permission is denied, without asking the server', async () => {
      // Arrange
      await renderProfile({ push: false, email: true });
      jest.mocked(askPushPermission).mockResolvedValue('denied');

      // Act
      await fireEvent(pushSwitch(), 'valueChange', true);

      // Assert
      expect(await screen.findByRole('alert')).toBeOnTheScreen();
      expect(pushSwitch()).not.toBeChecked();
      expect(mockedRegister).not.toHaveBeenCalled();
      expect(mockedUpdate).not.toHaveBeenCalled();
    });

    it('shows an error, not success, when the token is registered but the change fails', async () => {
      // Arrange
      await renderProfile({ push: false, email: true });
      mockedUpdate.mockResolvedValueOnce(NETWORK_FAILURE);

      // Act
      await fireEvent(pushSwitch(), 'valueChange', true);

      // Assert
      expect(await screen.findByRole('alert')).toBeOnTheScreen();
      expect(pushSwitch()).not.toBeChecked();
    });
  });

  describe('turning push off', () => {
    it('sends the change, then deletes the device token', async () => {
      // Arrange
      await renderProfile({ push: true, email: true });

      // Act
      await fireEvent(pushSwitch(), 'valueChange', false);

      // Assert
      await waitFor(() => expect(pushSwitch()).not.toBeChecked());
      expect(mockedUpdate.mock.calls[0][1]).toEqual({ push: false });
      expect(mockedDelete).toHaveBeenCalledTimes(1);
      expect(mockedDelete.mock.calls[0][1]).toBe(DEVICE_TOKEN);
      expect(mockedUpdate.mock.invocationCallOrder[0]).toBeLessThan(
        mockedDelete.mock.invocationCallOrder[0],
      );
    });
  });

  describe('turning email on or off', () => {
    it('sends only the email change', async () => {
      // Arrange
      await renderProfile({ push: false, email: true });

      // Act
      await fireEvent(emailSwitch(), 'valueChange', false);

      // Assert
      await waitFor(() => expect(emailSwitch()).not.toBeChecked());
      expect(mockedUpdate.mock.calls[0][1]).toEqual({ email: false });
      expect(mockedRegister).not.toHaveBeenCalled();
      expect(mockedDelete).not.toHaveBeenCalled();
    });

    it('shows an error and keeps the switch when the change fails', async () => {
      // Arrange
      await renderProfile({ push: false, email: true });
      mockedUpdate.mockResolvedValueOnce(NETWORK_FAILURE);

      // Act
      await fireEvent(emailSwitch(), 'valueChange', false);

      // Assert
      expect(await screen.findByRole('alert')).toBeOnTheScreen();
      expect(emailSwitch()).toBeChecked();
    });
  });

  describe('a change in flight', () => {
    it('ignores a second tap until the first change is answered', async () => {
      // Arrange
      await renderProfile({ push: false, email: true });
      const update = inFlight<Settings>();
      mockedUpdate.mockReturnValueOnce(update.promise);
      await fireEvent(emailSwitch(), 'valueChange', false);

      // Act
      await fireEvent(emailSwitch(), 'valueChange', true);

      // Assert
      expect(mockedUpdate).toHaveBeenCalledTimes(1);
    });
  });
});
