import { render, screen } from '@testing-library/react-native';
import { AccessibilityInfo } from 'react-native';

import { FormMessage } from './FormMessage';

describe('FormMessage', () => {
  let announce: jest.SpiedFunction<typeof AccessibilityInfo.announceForAccessibility>;

  beforeEach(() => {
    announce = jest
      .spyOn(AccessibilityInfo, 'announceForAccessibility')
      .mockImplementation(() => {});
  });

  afterEach(() => {
    announce.mockRestore();
  });

  it('renders nothing and announces nothing without a message', async () => {
    await render(<FormMessage message={null} />);

    expect(screen.toJSON()).toBeNull();
    expect(announce).not.toHaveBeenCalled();
  });

  it('shows the message and announces it for screen readers', async () => {
    await render(<FormMessage message="Wrong email or password." />);

    expect(screen.getByText('Wrong email or password.')).toBeOnTheScreen();
    expect(announce).toHaveBeenCalledWith('Wrong email or password.');
  });

  it('announces the message again when it changes', async () => {
    await render(<FormMessage message="Signing in…" tone="info" />);
    await screen.rerender(<FormMessage message="Wrong email or password." />);

    expect(screen.queryByText('Signing in…')).not.toBeOnTheScreen();
    expect(screen.getByText('Wrong email or password.')).toBeOnTheScreen();
    expect(announce).toHaveBeenLastCalledWith('Wrong email or password.');
    expect(announce).toHaveBeenCalledTimes(2);
  });
});
