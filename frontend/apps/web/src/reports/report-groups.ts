import { isIncidentStatus } from '@urbanpulse/domain';
import { NOT_AVAILABLE, STATUS_LABELS } from '@urbanpulse/ui';

import { isPriority } from '../incidents/filters/incident-filters';
import { PRIORITY_LABELS } from '../incidents/labels';
import type { ReportGrouping } from './report-period';

/** The group as the screens name it; the CSV keeps the values of the API. */
export function groupLabel(groupBy: ReportGrouping, group: string | null): string {
  if (group === null) {
    return NOT_AVAILABLE;
  }
  if (groupBy === 'status' && isIncidentStatus(group)) {
    return STATUS_LABELS[group];
  }
  if (groupBy === 'priority' && isPriority(group)) {
    return PRIORITY_LABELS[group];
  }
  return group;
}
