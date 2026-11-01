import styles from './Incidents.module.css';

/**
 * Where the operator acts on the incident. Each lifecycle feature adds the buttons and panels of
 * its status here (#44, #47 to #50, #52), and receives the incident to choose them by `status` and
 * send its `version`, so the detail page itself does not change.
 */
export function IncidentActions() {
  return (
    <section className={`${styles.panel} ${styles.actions}`} aria-labelledby="actions-title">
      <h2 id="actions-title" className={styles.panelTitle}>
        Actions
      </h2>
      <p className={styles.lead}>No actions are available for this incident yet.</p>
    </section>
  );
}
