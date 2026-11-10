/**
 * How a screen waits for its data (#174), the same on the web and on the phone: first nothing,
 * then the shape of what is coming, then a notice that the server is slow.
 */
export type LoadingStage = 'waiting' | 'showing' | 'slow';

/** A skeleton shown at once flashes when the answer comes a moment later, so it waits this long. */
export const SKELETON_DELAY_MS = 300;

/** After this long the skeleton also says that the server is slow, so it never waits in silence. */
export const SLOW_LOADING_MS = 10_000;
