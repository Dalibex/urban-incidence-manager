import { INCIDENT_STATUSES } from '@urbanpulse/domain';
import { colors, statusColor } from '@urbanpulse/ui';

import styles from './App.module.css';

/** Start screen of the skeleton: proves that the shared packages are wired into the web app. */
export function App() {
  return (
    <main className={styles.page} style={{ color: colors.text }}>
      <h1 className={styles.title}>UrbanPulse</h1>
      <p style={{ color: colors.textMuted }}>
        Web app for operators, technicians, analysts and administrators.
      </p>
      <ul className={styles.statuses} aria-label="Incident statuses">
        {INCIDENT_STATUSES.map((status) => (
          <li
            key={status}
            className={styles.badge}
            style={{ backgroundColor: statusColor(status) }}
          >
            {status}
          </li>
        ))}
      </ul>
    </main>
  );
}
