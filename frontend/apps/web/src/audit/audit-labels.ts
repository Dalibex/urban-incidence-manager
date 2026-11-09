import type { components } from '@urbanpulse/api-client';

export type AuditAction = components['schemas']['AuditAction'];
type AuditValue = components['schemas']['AuditValue'];

/** What each action did, in the words of the screen. A `Record`, so a new action needs a label. */
export const ACTION_LABELS: Record<AuditAction, string> = {
  CREATE_USER: 'Account created',
  UPDATE_USER: 'Account updated',
  CHANGE_ROLE: 'Role changed',
  DISABLE_USER: 'Account deactivated',
  ENABLE_USER: 'Account reactivated',
  CHANGE_PASSWORD: 'Password changed',
  CLASSIFY_INCIDENT: 'Incident classified',
};

/** Every action, in the order the filter shows them. */
export const AUDIT_ACTIONS = Object.keys(ACTION_LABELS) as readonly AuditAction[];

export function isAuditAction(value: string): value is AuditAction {
  return (AUDIT_ACTIONS as readonly string[]).includes(value);
}

/** A recorded value as text: a field that had no value says so instead of showing nothing. */
export function formatAuditValue(value: AuditValue): string {
  if (value === null) {
    return 'none';
  }
  if (typeof value === 'boolean') {
    return value ? 'yes' : 'no';
  }
  return String(value);
}

/** One changed field: its value before (absent when the change created it) and after. */
export interface FieldChange {
  field: string;
  before?: AuditValue;
  after?: AuditValue;
}

/**
 * The fields of an entry's details, each once: the ones it replaced first, in their order, then
 * the ones only the change set.
 */
export function fieldChanges(details: components['schemas']['AuditDetails']): FieldChange[] {
  const fields = [...new Set([...Object.keys(details.before), ...Object.keys(details.after)])];
  return fields.map((field) => ({
    field,
    ...(field in details.before && { before: details.before[field] }),
    ...(field in details.after && { after: details.after[field] }),
  }));
}
