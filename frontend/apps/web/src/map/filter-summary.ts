import { STATUS_LABELS } from '@urbanpulse/ui';

import type { IncidentFilters } from '../incidents/filters/incident-filters';
import { PRIORITY_LABELS } from '../incidents/labels';

interface Named {
  id: string;
  name: string;
}

/**
 * The active filters in words, one entry per filter, for the line beside the folded filter bar: with
 * the bar folded, this is what tells the user the map is filtered. A category or department whose
 * name has not loaded yet is still named as a filter, never left out.
 */
export function filterSummary(
  filters: IncidentFilters,
  categories: readonly Named[] = [],
  departments: readonly Named[] = [],
): string[] {
  const summary: string[] = [];
  if (filters.status) {
    summary.push(`Status: ${filters.status.map((status) => STATUS_LABELS[status]).join(', ')}`);
  }
  if (filters.categoryId) {
    summary.push(`Category: ${nameOf(filters.categoryId, categories) ?? 'selected'}`);
  }
  if (filters.priority) {
    summary.push(`Priority: ${PRIORITY_LABELS[filters.priority]}`);
  }
  if (filters.departmentId) {
    summary.push(`Department: ${nameOf(filters.departmentId, departments) ?? 'selected'}`);
  }
  const { reportedFrom: from, reportedTo: to } = filters;
  if (from && to) {
    summary.push(from === to ? `Reported on ${from}` : `Reported from ${from} to ${to}`);
  } else if (from) {
    summary.push(`Reported from ${from}`);
  } else if (to) {
    summary.push(`Reported until ${to}`);
  }
  return summary;
}

function nameOf(id: string, items: readonly Named[]): string | undefined {
  return items.find((item) => item.id === id)?.name;
}
