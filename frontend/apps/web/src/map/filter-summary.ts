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
  categories?: readonly Named[],
  departments?: readonly Named[],
): string[] {
  const summary: string[] = [];
  // First: it is the one the user typed, and the one a cut line must not hide.
  if (filters.q) {
    summary.push(`Search: “${filters.q}”`);
  }
  if (filters.status) {
    summary.push(`Status: ${filters.status.map((status) => STATUS_LABELS[status]).join(', ')}`);
  }
  if (filters.categoryId) {
    summary.push(`Category: ${namesOf(filters.categoryId, categories, 'category')}`);
  }
  if (filters.priority) {
    summary.push(`Priority: ${PRIORITY_LABELS[filters.priority]}`);
  }
  if (filters.departmentId) {
    summary.push(`Department: ${namesOf(filters.departmentId, departments, 'department')}`);
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

/**
 * The names of the chosen ids in the order of the options; an id not among them comes last, named
 * unknown as the bar does. Without `items` (not loaded yet) the names are not known: one id is
 * `selected` and several are counted.
 */
function namesOf(
  ids: readonly string[],
  items: readonly Named[] | undefined,
  noun: string,
): string {
  if (!items) {
    return ids.length === 1
      ? 'selected'
      : `${ids.length} ${noun === 'category' ? 'categories' : 'departments'}`;
  }
  const known = items.filter((item) => ids.includes(item.id)).map((item) => item.name);
  const unknown = ids
    .filter((id) => !items.some((item) => item.id === id))
    .map(() => `Unknown ${noun}`);
  return [...known, ...unknown].join(', ');
}
