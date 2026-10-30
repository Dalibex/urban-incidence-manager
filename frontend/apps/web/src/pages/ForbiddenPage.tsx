import { Link } from 'react-router';

import { PATHS } from '../routing/access';
import styles from './Page.module.css';

export function ForbiddenPage() {
  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        You cannot open this page
      </h1>
      <p className={styles.summary}>Your role does not give access to this screen.</p>
      <Link to={PATHS.home} className={styles.link}>
        Go to your home screen
      </Link>
    </section>
  );
}
