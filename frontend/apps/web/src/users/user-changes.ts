import type { components } from '@urbanpulse/api-client';
import type { Role } from '@urbanpulse/domain';

type User = components['schemas']['UserResponse'];
type UpdateUserRequest = components['schemas']['UpdateUserRequest'];

/** The roles an administrator can give: citizens sign up by themselves (`CreateUserRequest`). */
export const STAFF_ROLES: readonly Role[] = [
  'MUNICIPAL_OPERATOR',
  'TECHNICIAN',
  'ANALYST',
  'ADMINISTRATOR',
];

/** Longest values the API accepts (`CreateUserRequest`), so its raw size message never shows. */
export const NAME_MAX_LENGTH = 100;
export const EMAIL_MAX_LENGTH = 254;
export const PASSWORD_MAX_LENGTH = 72;

/**
 * The body to send, with only what the administrator changed compared with the row as loaded;
 * `null` when nothing changed. Sending a property they did not touch could undo what another
 * administrator changed meanwhile, because a user has no version to detect it.
 */
export function changesOf(user: User, name: string, role: Role): UpdateUserRequest | null {
  const body: UpdateUserRequest = {};
  const trimmed = name.trim();
  if (trimmed !== user.name) {
    body.name = trimmed;
  }
  if (role !== user.role) {
    body.role = role;
  }
  return Object.keys(body).length > 0 ? body : null;
}
