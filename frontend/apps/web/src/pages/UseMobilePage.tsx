import styles from './Page.module.css';

/** Landing of a citizen who signs in on the web: their app is the mobile one (ADR-0005). */
export function UseMobilePage() {
  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Use the UrbanPulse mobile app
      </h1>
      <p className={styles.summary}>
        Citizens report incidents and follow their progress from the mobile app. This website is for
        municipal staff.
      </p>
    </section>
  );
}
