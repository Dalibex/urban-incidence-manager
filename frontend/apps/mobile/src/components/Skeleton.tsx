import {
  colors,
  SKELETON_DELAY_MS,
  SLOW_LOADING_MS,
  spacing,
  type LoadingStage,
} from '@urbanpulse/ui';
import { useEffect, useState, type ReactNode } from 'react';
import {
  AccessibilityInfo,
  Animated,
  StyleSheet,
  Text,
  View,
  type DimensionValue,
} from 'react-native';

import { screenStyles } from '../screens/styles';

interface LoadingSkeletonProps {
  /** What is loading, as in "Loading your reports". */
  what: string;
  /** The shape of the part that is loading, drawn with {@link SkeletonBlock}. */
  children: ReactNode;
}

/** One pulse, from full to faded and back, in ms. */
const PULSE_MS = 800;
const PULSE_OPACITY = 0.45;

const BLOCK_HEIGHT = 16;
/** The muted text color this faint, as on the web: visible on the background, never read as text. */
const BLOCK_OPACITY = 0.22;

/**
 * Stands in for one part of a screen while its query is pending (#174). Screen readers get one
 * element that says what is loading, not the shape. A quick answer replaces it before it shows,
 * and a slow one says so. Each screen draws its own shape: the map, the timeline and the photos
 * use it in theirs.
 */
export function LoadingSkeleton({ what, children }: LoadingSkeletonProps) {
  const stage = useLoadingStage();
  const opacity = usePulse();
  const slow = stage === 'slow';
  const message = slow
    ? `Still loading ${what}. UrbanPulse is taking longer than usual.`
    : `Loading ${what}`;

  useEffect(() => {
    if (slow) {
      // A changed label is not read again on its own.
      AccessibilityInfo.announceForAccessibility(message);
    }
  }, [slow, message]);

  return (
    <View accessible accessibilityRole="progressbar" accessibilityLabel={message}>
      {slow && <Text style={[screenStyles.note, styles.notice]}>{message}</Text>}
      {stage !== 'waiting' && (
        <Animated.View testID="skeleton" style={{ opacity }}>
          {children}
        </Animated.View>
      )}
    </View>
  );
}

interface SkeletonBlockProps {
  /** As in a style: a number of points or a share such as `'60%'`. */
  width?: DimensionValue;
  height?: number;
}

/** One block of content still to come, such as a line of text. */
export function SkeletonBlock({ width = '100%', height = BLOCK_HEIGHT }: SkeletonBlockProps) {
  return <View style={[styles.block, { width, height }]} />;
}

function useLoadingStage(): LoadingStage {
  const [stage, setStage] = useState<LoadingStage>('waiting');
  useEffect(() => {
    const showing = setTimeout(() => setStage('showing'), SKELETON_DELAY_MS);
    const slow = setTimeout(() => setStage('slow'), SLOW_LOADING_MS);
    return () => {
      clearTimeout(showing);
      clearTimeout(slow);
    };
  }, []);
  return stage;
}

/** The opacity of a slow pulse, which stays still when the phone asks for less motion. */
function usePulse(): Animated.Value {
  const [opacity] = useState(() => new Animated.Value(1));
  const reduceMotion = useReduceMotion();

  useEffect(() => {
    if (reduceMotion) {
      return;
    }
    const pulse = Animated.loop(
      Animated.sequence([
        Animated.timing(opacity, {
          toValue: PULSE_OPACITY,
          duration: PULSE_MS,
          useNativeDriver: true,
        }),
        Animated.timing(opacity, { toValue: 1, duration: PULSE_MS, useNativeDriver: true }),
      ]),
    );
    pulse.start();
    return () => {
      pulse.stop();
      opacity.setValue(1);
    };
  }, [opacity, reduceMotion]);

  return opacity;
}

/** Whether the phone asks for less motion; until it answers, the pulse waits, so it never flashes. */
function useReduceMotion(): boolean {
  const [reduceMotion, setReduceMotion] = useState(true);
  useEffect(() => {
    let mounted = true;
    void AccessibilityInfo.isReduceMotionEnabled().then((enabled) => {
      if (mounted) {
        setReduceMotion(enabled);
      }
    });
    const subscription = AccessibilityInfo.addEventListener('reduceMotionChanged', setReduceMotion);
    return () => {
      mounted = false;
      subscription.remove();
    };
  }, []);
  return reduceMotion;
}

const styles = StyleSheet.create({
  block: {
    borderRadius: 4,
    backgroundColor: colors.textMuted,
    opacity: BLOCK_OPACITY,
  },
  // The screens draw their shapes edge to edge, with the padding of their own rows.
  notice: {
    paddingHorizontal: spacing.md,
    marginBottom: spacing.sm,
  },
});
