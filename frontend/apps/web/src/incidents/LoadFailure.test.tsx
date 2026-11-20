import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { LoadFailure, RefreshFailure } from './LoadFailure';

const ERROR = new Error('500');

describe('Try again while it retries', () => {
  it.each([
    ['LoadFailure', LoadFailure],
    ['RefreshFailure', RefreshFailure],
  ] as const)('%s keeps the button focusable and ignores another click', async (_name, Failure) => {
    const onRetry = vi.fn();
    render(
      <Failure what="The incidents" error={ERROR} retrying onRetry={onRetry} shownSince={0} />,
    );

    const button = screen.getByRole('button', { name: 'Trying again…' });
    button.focus();
    await userEvent.click(button);

    // A browser moves the focus of a `disabled` button to the page; jsdom does not.
    expect(button.hasAttribute('disabled')).toBe(false);
    expect(button.getAttribute('aria-disabled')).toBe('true');
    expect(document.activeElement).toBe(button);
    expect(onRetry).not.toHaveBeenCalled();
  });

  it('retries on a click when it is not retrying', async () => {
    const onRetry = vi.fn();
    render(<LoadFailure what="The incidents" error={ERROR} retrying={false} onRetry={onRetry} />);

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(onRetry).toHaveBeenCalledOnce();
    expect(screen.getByRole('button', { name: 'Try again' }).hasAttribute('aria-disabled')).toBe(
      false,
    );
  });
});
