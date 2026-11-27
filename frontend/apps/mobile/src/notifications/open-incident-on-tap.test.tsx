import { render } from '@testing-library/react-native';

import { useOpenIncidentOnNotificationTap } from './useOpenIncidentOnNotificationTap';

// `expo-notifications` is not installed yet (it comes with `npx expo install`); a virtual mock
// keeps these tests independent of the native module.
jest.mock(
  'expo-notifications',
  () => ({
    addNotificationResponseReceivedListener: jest.fn(() => ({ remove: jest.fn() })),
  }),
  { virtual: true },
);
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate }),
}));

type Response = { notification: { request: { content: { data: Record<string, unknown> } } } };

const Notifications = jest.requireMock('expo-notifications') as {
  addNotificationResponseReceivedListener: jest.Mock;
};

function tapOf(data: Record<string, unknown>): Response {
  return { notification: { request: { content: { data } } } };
}

function Probe() {
  useOpenIncidentOnNotificationTap();
  return null;
}

/** Renders the hook and returns what a tap on a notification calls. */
async function renderListening() {
  await render(<Probe />);
  const [listener] = Notifications.addNotificationResponseReceivedListener.mock.lastCall as [
    (response: Response) => void,
  ];
  return listener;
}

beforeEach(() => {
  mockNavigate.mockClear();
  Notifications.addNotificationResponseReceivedListener.mockClear();
});

describe('tapping a notification', () => {
  it('opens the detail of the incident it names', async () => {
    // Arrange
    const tap = await renderListening();

    // Act
    tap(tapOf({ incidentId: 'a1b2' }));

    // Assert
    expect(mockNavigate).toHaveBeenCalledWith('MyReports', {
      screen: 'ReportDetail',
      params: { incidentId: 'a1b2' },
    });
  });

  it('does nothing when the notification names no incident', async () => {
    // Arrange
    const tap = await renderListening();

    // Act
    tap(tapOf({}));

    // Assert
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
