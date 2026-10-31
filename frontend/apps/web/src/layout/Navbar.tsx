import { NavLink } from 'react-router';

import { SECTIONS } from '../routing/sections';
import { useSession } from '../session/SessionContext';
import styles from './AppLayout.module.css';
import { ROLE_LABELS } from './role-labels';

export function Navbar() {
  const { user, signOut } = useSession();
  if (!user) {
    return null;
  }
  const sections = SECTIONS.filter((section) => section.roles.includes(user.role));

  return (
    <header className={styles.navbar}>
      <span className={styles.brand}>UrbanPulse</span>
      {sections.length > 0 && (
        <nav aria-label="Main">
          <ul className={styles.links}>
            {sections.map((section) => (
              <li key={section.path}>
                <NavLink
                  to={section.path}
                  className={({ isActive }) => (isActive ? styles.activeLink : styles.link)}
                >
                  {section.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      )}
      <div className={styles.account}>
        <span className={styles.user}>
          <span className={styles.userName}>{user.name}</span>
          <span className={styles.userRole}>{ROLE_LABELS[user.role]}</span>
        </span>
        <button type="button" className={styles.signOut} onClick={() => void signOut()}>
          Sign out
        </button>
      </div>
    </header>
  );
}
