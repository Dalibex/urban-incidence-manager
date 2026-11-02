import type { IncidentStatus } from '@urbanpulse/domain';
import { statusColor } from '@urbanpulse/ui';
import type { CSSProperties } from 'react';

import styles from './Incidents.module.css';
import { STATUS_LABELS } from './labels';

/** The status by name, with its shared color as a mark: the color never carries the meaning alone. */
export function StatusBadge({ status }: { status: IncidentStatus }) {
  return (
    <span
      className={styles.status}
      style={{ '--status-color': statusColor(status) } as CSSProperties}
    >
      {STATUS_LABELS[status]}
    </span>
  );
}
