import { Link } from 'react-router';

import { PATHS } from '../routing/access';
import styles from './Page.module.css';

export function NotFoundPage() {
  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Page not found
      </h1>
      <p className={styles.summary}>There is no screen at this address.</p>
      <Link to={PATHS.home} className={styles.link}>
        Go to your home screen
      </Link>
    </section>
  );
}
