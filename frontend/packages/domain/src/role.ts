/**
 * Roles of a user (ADR-0004), in the same spelling as the API (`Role` in `docs/api/openapi.yml`).
 * A user has exactly one. The clients use it to choose screens; the API checks it again in every
 * operation, so hiding a screen is never access control.
 */
export const ROLES = [
  'CITIZEN',
  'MUNICIPAL_OPERATOR',
  'TECHNICIAN',
  'ADMINISTRATOR',
  'ANALYST',
] as const;

export type Role = (typeof ROLES)[number];
