import styles from './Page.module.css';

interface PlaceholderPageProps {
  title: string;
  summary: string;
}

/** Stand-in for a screen that its own issue builds; the shell only proves the route exists. */
export function PlaceholderPage({ title, summary }: PlaceholderPageProps) {
  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        {title}
      </h1>
      <p className={styles.summary}>{summary}</p>
      <p className={styles.note}>This screen is not built yet.</p>
    </section>
  );
}
