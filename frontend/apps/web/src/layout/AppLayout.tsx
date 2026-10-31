import { Outlet } from 'react-router';

import styles from './AppLayout.module.css';
import { Navbar } from './Navbar';

/** Frame of every signed-in screen: the navbar on top and the screen below. */
export function AppLayout() {
  return (
    <div className={styles.shell}>
      <Navbar />
      <main className={styles.content}>
        <Outlet />
      </main>
    </div>
  );
}
