import { act, render, screen } from '@testing-library/react-native';
import { SKELETON_DELAY_MS, SLOW_LOADING_MS } from '@urbanpulse/ui';
import { AccessibilityInfo, Animated, Text } from 'react-native';

import { LoadingSkeleton } from './Skeleton';

/** The cases of #174: a quick answer, a slow one, screen readers and less motion. */

const SLOW_MESSAGE = 'Still loading your reports. UrbanPulse is taking longer than usual.';

async function renderSkeleton() {
  await render(
    <LoadingSkeleton what="your reports">
      <Text>Shape</Text>
    </LoadingSkeleton>,
  );
}

async function advance(ms: number) {
  await act(async () => {
    jest.advanceTimersByTime(ms);
  });
}

function reduceMotion(enabled: boolean) {
  jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(enabled);
}

beforeEach(() => {
  jest.useFakeTimers();
  reduceMotion(false);
});

afterEach(() => {
  jest.useRealTimers();
  jest.restoreAllMocks();
});

describe('LoadingSkeleton', () => {
  it('says what is loading at once, and draws the shape only after a moment', async () => {
    await renderSkeleton();

    expect(screen.getByRole('progressbar', { name: 'Loading your reports' })).toBeOnTheScreen();
    expect(screen.queryByTestId('skeleton')).toBeNull();

    await advance(SKELETON_DELAY_MS);
    expect(screen.getByTestId('skeleton')).toBeOnTheScreen();
  });

  it('says that UrbanPulse is slow when the answer takes long, also to screen readers', async () => {
    const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility');
    await renderSkeleton();

    await advance(SLOW_LOADING_MS);
    expect(screen.getByRole('progressbar', { name: SLOW_MESSAGE })).toBeOnTheScreen();
    expect(screen.getByText(SLOW_MESSAGE)).toBeOnTheScreen();
    expect(announce).toHaveBeenCalledWith(SLOW_MESSAGE);
  });

  it('pulses while it waits', async () => {
    const loop = jest.spyOn(Animated, 'loop');
    await renderSkeleton();
    await advance(SKELETON_DELAY_MS);
    expect(loop).toHaveBeenCalled();
  });

  it('does not move when the phone asks for less motion', async () => {
    reduceMotion(true);
    const loop = jest.spyOn(Animated, 'loop');
    await renderSkeleton();

    await advance(SKELETON_DELAY_MS);
    expect(screen.getByTestId('skeleton')).toBeOnTheScreen();
    expect(loop).not.toHaveBeenCalled();
  });
});
