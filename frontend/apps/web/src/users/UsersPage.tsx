import type { components } from '@urbanpulse/api-client';
import { Fragment, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';

import styles from '../incidents/Incidents.module.css';
import { LoadFailure } from '../incidents/LoadFailure';
import { pageFrom, Pagination } from '../incidents/Pagination';
import { ROLE_LABELS } from '../layout/role-labels';
import { useSession } from '../session/SessionContext';
import { CreateUserForm } from './CreateUserForm';
import { filtersFrom, hasFilters, withFilters, type UserFilters } from './user-filters';
import { useReloadUsers, useUserPage } from './user-queries';
import { UserEditor } from './UserEditor';
import { UserFilterBar } from './UserFilterBar';
import userStyles from './Users.module.css';

type User = components['schemas']['UserResponse'];

const PAGE_PARAM = 'page';
const COLUMNS = 5;

/**
 * The administrator's users screen (#191, RF01, RF02): every account with its role and whether it
 * can sign in, and the forms to create a staff account and to change one. The filters and the page
 * are in the address, so reloading or going back keeps them.
 */
export function UsersPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = filtersFrom(searchParams);
  const page = pageFrom(searchParams.get(PAGE_PARAM));
  const users = useUserPage(page - 1, filters);
  const { user: signedIn } = useSession();
  const firstFilter = useRef<HTMLInputElement>(null);
  const newAccountButton = useRef<HTMLButtonElement>(null);
  const [creating, setCreating] = useState(false);
  const keepFocusAfterReload = useRef(false);

  // The reload after a closed panel can remove the row whose button had the focus. The filters are
  // the nearest control left, so a keyboard user does not start again from the top of the page.
  useEffect(() => {
    if (keepFocusAfterReload.current && !users.isFetching) {
      keepFocusAfterReload.current = false;
      if (document.activeElement === document.body) {
        firstFilter.current?.focus();
      }
    }
  }, [users.data, users.isFetching]);

  const goTo = (target: number) => {
    const next = new URLSearchParams(searchParams);
    if (target === 1) {
      next.delete(PAGE_PARAM);
    } else {
      next.set(PAGE_PARAM, String(target));
    }
    setSearchParams(next);
  };

  // Other filters give other pages, so a change starts again on the first one.
  const changeFilters = (next: UserFilters) => {
    const params = withFilters(searchParams, next);
    params.delete(PAGE_PARAM);
    setSearchParams(params);
  };

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <header className={`${styles.header} ${userStyles.header}`}>
        <div>
          <h1 id="page-title" className={styles.title}>
            Users
          </h1>
          <p className={styles.lead}>Accounts, roles and access.</p>
        </div>
        <button
          ref={newAccountButton}
          type="button"
          className={styles.primaryButton}
          aria-expanded={creating}
          onClick={() => setCreating(true)}
          disabled={creating}
        >
          New staff account
        </button>
      </header>

      {creating && (
        <CreateUserForm
          onClose={() => {
            setCreating(false);
            // The button was disabled while the form was open and gets the focus back.
            requestAnimationFrame(() => newAccountButton.current?.focus());
          }}
        />
      )}

      <UserFilterBar filters={filters} onChange={changeFilters} firstFilterRef={firstFilter} />

      {users.isPending ? (
        <p className={styles.state} role="status">
          Loading the users…
        </p>
      ) : users.isError ? (
        <LoadFailure
          what="The users"
          error={users.error}
          retrying={users.isFetching}
          onRetry={() => void users.refetch()}
        />
      ) : users.data.page.totalElements === 0 && hasFilters(filters) ? (
        <div className={styles.state}>
          <p>No users match these filters.</p>
          <p>
            <button
              type="button"
              className={styles.button}
              onClick={() => {
                changeFilters({});
                // This button goes away with the empty state, like the one of the bar is disabled.
                firstFilter.current?.focus();
              }}
            >
              Clear filters
            </button>
          </p>
        </div>
      ) : users.data.page.totalElements === 0 ? (
        <div className={styles.state}>
          <p>There are no users yet.</p>
          <p className={styles.muted}>Create a staff account to start.</p>
        </div>
      ) : users.data.content.length === 0 ? (
        <div className={styles.state}>
          <p>Page {page} has no users.</p>
          <p>
            <button type="button" className={styles.button} onClick={() => goTo(1)}>
              Go to the first page
            </button>
          </p>
        </div>
      ) : (
        <>
          <UserTable
            rows={users.data.content}
            loading={users.isPlaceholderData}
            signedInId={signedIn?.id}
            // Another page or other filters show other rows: a panel left open would reopen by itself.
            viewKey={searchParams.toString()}
            onReloading={() => {
              keepFocusAfterReload.current = true;
            }}
          />
          <Pagination
            label="Pages of the users"
            noun={['user', 'users']}
            page={page}
            totalPages={users.data.page.totalPages}
            totalElements={users.data.page.totalElements}
            loading={users.isPlaceholderData}
            onGoTo={goTo}
          />
        </>
      )}
    </section>
  );
}

interface UserTableProps {
  rows: User[];
  loading: boolean;
  signedInId: string | undefined;
  /** Changes with the page and the filters. */
  viewKey: string;
  /** The lists are reloading because a panel closed after a change. */
  onReloading: () => void;
}

function UserTable({ rows, loading, signedInId, viewKey, onReloading }: UserTableProps) {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [seenViewKey, setSeenViewKey] = useState(viewKey);
  if (viewKey !== seenViewKey) {
    setSeenViewKey(viewKey);
    setEditingId(null);
  }
  const reload = useReloadUsers();
  // A saved change leaves the lists out of date; they reload when the panel closes, not before.
  const unsaved = useRef(false);
  const reloadIfSaved = () => {
    if (unsaved.current) {
      unsaved.current = false;
      onReloading();
      void reload();
    }
  };
  const editButtons = useRef(new Map<string, HTMLButtonElement>());

  const close = (userId: string) => {
    setEditingId(null);
    // The panel took the focus when it opened; it goes back to the row that opened it.
    editButtons.current.get(userId)?.focus();
    reloadIfSaved();
  };

  return (
    <div className={styles.tableWrapper}>
      <table className={`${styles.table} ${userStyles.dense}`} aria-busy={loading}>
        <caption className={styles.visuallyHidden}>Users, by name</caption>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Email</th>
            <th scope="col">Role</th>
            <th scope="col">Status</th>
            <th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((user) => {
            const own = user.id === signedInId;
            const editing = editingId === user.id;
            return (
              <Fragment key={user.id}>
                <tr className={user.enabled ? undefined : userStyles.deactivated}>
                  <td className={userStyles.name}>
                    {user.name}
                    {own && <span className={userStyles.you}> (you)</span>}
                  </td>
                  <td className={userStyles.email}>{user.email}</td>
                  <td>{ROLE_LABELS[user.role]}</td>
                  <td>
                    <span
                      className={`${userStyles.status} ${user.enabled ? userStyles.active : userStyles.inactive}`}
                    >
                      {user.enabled ? 'Active' : 'Deactivated'}
                    </span>
                  </td>
                  <td>
                    <button
                      ref={(button) => {
                        if (button) {
                          editButtons.current.set(user.id, button);
                        } else {
                          editButtons.current.delete(user.id);
                        }
                      }}
                      type="button"
                      className={styles.button}
                      aria-expanded={editing}
                      aria-label={`Edit ${user.name}`}
                      onClick={() => {
                        setEditingId(editing ? null : user.id);
                        reloadIfSaved();
                      }}
                    >
                      Edit
                    </button>
                  </td>
                </tr>
                {editing && (
                  <tr className={userStyles.editorRow}>
                    <td colSpan={COLUMNS}>
                      <UserEditor
                        user={user}
                        own={own}
                        onSaved={() => {
                          unsaved.current = true;
                        }}
                        onClose={() => close(user.id)}
                      />
                    </td>
                  </tr>
                )}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
