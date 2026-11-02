import type { components } from '@urbanpulse/api-client';

type Priority = components['schemas']['Priority'];

/** Staff only: citizens never receive the priority, so it is not shared with the mobile app. */
export const PRIORITY_LABELS: Record<Priority, string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
};
