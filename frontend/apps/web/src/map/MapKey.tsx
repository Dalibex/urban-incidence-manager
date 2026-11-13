import { INCIDENT_STATUSES } from '@urbanpulse/domain';
import { STATUS_LABELS, statusColor } from '@urbanpulse/ui';
import type { CSSProperties } from 'react';

import { HIGH_BORDER, PRIORITY_MARKS, PRIORITY_RANK } from './incident-features';
import styles from './Map.module.css';

interface MapKeyProps {
  open: boolean;
  onToggle: (open: boolean) => void;
}

/**
 * What the map draws, in the bottom-left corner of the map (#177): the color is the status, the
 * size the priority, and a group shows how many incidents it holds and their highest priority. The
 * list and the panel say the same in words. Folded unless the user opened it, so it does not cover
 * the map; the attribution of the base map keeps the other corner (ADR-0016).
 */
export function MapKey({ open, onToggle }: MapKeyProps) {
  return (
    <details
      className={styles.mapKey}
      open={open}
      onToggle={(event) => onToggle(event.currentTarget.open)}
    >
      <summary>Map key</summary>
      <p className={styles.legendTitle}>Color: status</p>
      <ul className={styles.legendItems}>
        {INCIDENT_STATUSES.map((status) => (
          <li key={status}>
            <span
              className={styles.swatch}
              style={{ background: statusColor(status) }}
              aria-hidden="true"
            />
            {STATUS_LABELS[status]}
          </li>
        ))}
      </ul>
      <p className={styles.legendTitle}>Size: priority</p>
      <ul className={styles.legendItems}>
        {PRIORITY_MARKS.map(({ rank, label, radius }) => (
          <li key={label}>
            <span
              className={styles.sizeSwatch}
              style={
                {
                  '--swatch-size': `${radius * 2}px`,
                  ...(rank === PRIORITY_RANK.HIGH && {
                    '--swatch-border': `${HIGH_BORDER.width}px solid ${HIGH_BORDER.color}`,
                  }),
                } as CSSProperties
              }
              aria-hidden="true"
            />
            {rank === PRIORITY_RANK.HIGH ? `${label}, with a dark border` : label}
          </li>
        ))}
      </ul>
      <p className={styles.legendTitle}>Groups</p>
      <ul className={styles.legendItems}>
        <li>
          <span className={styles.groupSwatch} aria-hidden="true">
            8
          </span>
          The number of incidents, with their highest priority written under it
        </li>
      </ul>
    </details>
  );
}
