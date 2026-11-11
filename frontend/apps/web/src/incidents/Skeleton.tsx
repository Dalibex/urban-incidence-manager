import { SKELETON_DELAY_MS, SLOW_LOADING_MS, type LoadingStage } from '@urbanpulse/ui';
import { useEffect, useState, type ReactNode } from 'react';

import styles from './Incidents.module.css';
import skeleton from './Skeleton.module.css';

interface LoadingSkeletonProps {
  /** What is loading, as in "Loading incidents…". */
  what: string;
  /** The shape of the part that is loading, drawn with {@link SkeletonLine}. */
  children: ReactNode;
}

/**
 * Stands in for one part of a screen while its query is pending (#174). Screen readers hear what is
 * loading, not the shape. A quick answer replaces it before it shows, and a slow one says so.
 * Each screen draws its own shape: the map, the timeline and the photos use it in theirs.
 */
export function LoadingSkeleton({ what, children }: LoadingSkeletonProps) {
  const stage = useLoadingStage();
  return (
    <div>
      <p role="status" className={stage === 'slow' ? skeleton.slow : styles.visuallyHidden}>
        {stage === 'slow'
          ? `Still loading ${what}. The server is taking longer than usual.`
          : `Loading ${what}…`}
      </p>
      {stage !== 'waiting' && (
        <div aria-hidden="true" data-testid="skeleton">
          {children}
        </div>
      )}
    </div>
  );
}

interface SkeletonLineProps {
  /** A share of its container, such as `'60%'`, or a length, such as `'6em'`. */
  width?: string;
  /** The size of the text it stands for, such as `'var(--font-size-title)'`; body text by default. */
  height?: string;
}

/** One line of text still to come. */
export function SkeletonLine({ width = '100%', height }: SkeletonLineProps) {
  return <span className={skeleton.line} style={{ width, height }} />;
}

/** A text of several lines still to come, one per width, spaced like the lines of a paragraph. */
export function SkeletonText({ widths }: { widths: readonly string[] }) {
  return (
    <span className={skeleton.text}>
      {widths.map((width, line) => (
        <SkeletonLine key={line} width={width} />
      ))}
    </span>
  );
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
