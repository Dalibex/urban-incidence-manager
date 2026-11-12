import {
  createUser,
  listUsers,
  updateUser,
  type ApiClient,
  type components,
  type FormResult,
} from '@urbanpulse/api-client';
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query';

import { dataOf } from '../query-client';
import { useSession } from '../session/SessionContext';
import { toListQuery, type UserFilters } from './user-filters';

type User = components['schemas']['UserResponse'];
type UserPage = components['schemas']['UserPageResponse'];

/** Rows per page of the users list; the API allows up to 100 (docs/api.md, "Listings"). */
export const USERS_PAGE_SIZE = 20;

const USERS_KEY = ['users'] as const;

/**
 * One page of the users that match `filters`, by name (the API's default order, with `id` as the
 * tie-break, so no row appears on two pages).
 *
 * @param page starting at 0, as in the API
 */
export function useUserPage(page: number, filters: UserFilters) {
  const { api } = useSession();
  return useQuery({
    queryKey: [...USERS_KEY, 'page', page, filters] as const,
    queryFn: () => dataOf(listUsers(api, { page, size: USERS_PAGE_SIZE, ...toListQuery(filters) })),
    // The current rows stay on screen while the next page loads, so the table does not jump.
    placeholderData: keepPreviousData,
    // Asking while the browser says it is offline fails at once, so the screen says that the server
    // cannot be reached and offers *Try again*, as the audit log does.
    networkMode: 'always',
  });
}

/**
 * A change sent from a form. The call resolves with the API's answer, a failure included, so the
 * form can show it next to its fields; only an unexpected bug rejects. `afterSuccess` runs once the
 * API accepted it, and is not awaited: the form answers as soon as the API does.
 */
function useUserChange<TBody>(
  send: (api: ApiClient, body: TBody) => Promise<FormResult<User>>,
  afterSuccess: (queryClient: QueryClient, user: User) => void,
) {
  const { api } = useSession();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: TBody) => send(api, body),
    onSuccess: (result) => {
      if (result.ok) {
        afterSuccess(queryClient, result.data);
      }
    },
    networkMode: 'always',
  });
}

/** Creates a staff account (#191); the list reloads to show it where it belongs. */
export function useCreateUser() {
  return useUserChange(
    (api, body: components['schemas']['CreateUserRequest']) => createUser(api, body),
    (queryClient) => void queryClient.invalidateQueries({ queryKey: USERS_KEY }),
  );
}

/**
 * Changes the name or role of a user, or deactivates or reactivates the account (#191). The row is
 * replaced in every cached page, but the lists do not reload yet: a change that takes the row out
 * of the filters would unmount the panel that is showing the result. The screen reloads them when
 * the panel closes ({@link useReloadUsers}).
 */
export function useUpdateUser(userId: string) {
  return useUserChange(
    (api, body: components['schemas']['UpdateUserRequest']) => updateUser(api, userId, body),
    (queryClient, user) => {
      queryClient.setQueriesData<UserPage>(
        { queryKey: [...USERS_KEY, 'page'] },
        (page) =>
          page && {
            ...page,
            content: page.content.map((row) => (row.id === user.id ? user : row)),
          },
      );
      void queryClient.invalidateQueries({ queryKey: USERS_KEY, refetchType: 'none' });
    },
  );
}

/** Reads the lists again, for the screen to call once an edit panel closes. */
export function useReloadUsers() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: USERS_KEY });
}
