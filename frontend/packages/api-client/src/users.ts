import type { ApiClient } from './index';
import { callApi, type ApiResult } from './account';
import { callForm, type FormResult } from './incidents';
import type { components, operations } from './schema';

type Schemas = components['schemas'];

/**
 * Query of `listUsers`: `page` starts at 0; `name` and `email` match a part of the text ignoring
 * case; `role` may repeat. Omitted values take the contract's defaults.
 */
export type UserListQuery = NonNullable<operations['listUsers']['parameters']['query']>;

/** A page of users of every role, enabled or not, by name unless `sort` says otherwise. Administrators only. */
export function listUsers(
  client: ApiClient,
  query: UserListQuery = {},
): Promise<ApiResult<Schemas['UserPageResponse']>> {
  return callApi(() => client.GET('/users', { params: { query } }));
}

/**
 * Creates an enabled staff account with the initial password the administrator typed. `409` with
 * `EMAIL_ALREADY_EXISTS` when the email is taken; `422` when a field is invalid, and for the role
 * `CITIZEN`, who sign up by themselves.
 */
export function createUser(
  client: ApiClient,
  body: Schemas['CreateUserRequest'],
): Promise<FormResult<Schemas['UserResponse']>> {
  return callForm(() => client.POST('/users', { body }), 'the created user');
}

/**
 * Changes the name or the role of a user, or disables or enables the account. Only the properties
 * present in the body change, so send only what the administrator changed. `422` for the own role
 * or account and for a role from or to `CITIZEN`; `404` when the user does not exist.
 */
export function updateUser(
  client: ApiClient,
  userId: string,
  body: Schemas['UpdateUserRequest'],
): Promise<FormResult<Schemas['UserResponse']>> {
  return callForm(
    () => client.PATCH('/users/{userId}', { params: { path: { userId } }, body }),
    'the user',
  );
}
