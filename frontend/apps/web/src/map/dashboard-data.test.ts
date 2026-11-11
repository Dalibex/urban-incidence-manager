import type { components } from '@urbanpulse/api-client';
import { colors, statusColor } from '@urbanpulse/ui';
import { describe, expect, it } from 'vitest';

import {
  chartDescription,
  dashboardCharts,
  NO_PRIORITY,
  NOT_ASSIGNED,
  NOT_CLASSIFIED,
  priorityFigures,
} from './dashboard-data';

type Statistics = components['schemas']['IncidentStatisticsResponse'];

const STATISTICS: Statistics = {
  total: 5,
  byStatus: [
    { status: 'REPORTED', count: 3 },
    { status: 'VALIDATED', count: 0 },
    { status: 'ASSIGNED', count: 2 },
  ],
  byPriority: [
    { priority: 'HIGH', count: 1 },
    { priority: 'MEDIUM', count: 0 },
    { priority: 'LOW', count: 1 },
    { priority: null, count: 3 },
  ],
  byCategory: [
    { categoryId: 'c1', name: 'Pavement', count: 3 },
    { categoryId: null, name: null, count: 2 },
  ],
  byDepartment: [
    { departmentId: 'd1', name: 'Public works', count: 2 },
    { departmentId: null, name: null, count: 3 },
  ],
};

describe('priorityFigures', () => {
  it('names each priority of the API in its order, and the incidents without one', () => {
    expect(priorityFigures(STATISTICS)).toEqual([
      { priority: 'HIGH', label: 'High', count: 1 },
      { priority: 'MEDIUM', label: 'Medium', count: 0 },
      { priority: 'LOW', label: 'Low', count: 1 },
      { priority: null, label: NO_PRIORITY, count: 3 },
    ]);
  });
});

describe('dashboardCharts', () => {
  const [byStatus, byCategory, byDepartment] = dashboardCharts(STATISTICS);

  it('keeps the statuses of the API, a real zero included, in the colours of the markers', () => {
    expect(byStatus).toEqual({
      title: 'By status',
      bars: [
        { label: 'Reported', count: 3, color: statusColor('REPORTED') },
        { label: 'Validated', count: 0, color: statusColor('VALIDATED') },
        { label: 'Assigned', count: 2, color: statusColor('ASSIGNED') },
      ],
    });
  });

  it('names the groups without a value, in gray so they never read as one more group', () => {
    expect(byCategory!.bars).toEqual([
      { label: 'Pavement', count: 3, color: colors.primary },
      { label: NOT_CLASSIFIED, count: 2, color: colors.textMuted },
    ]);
    expect(byDepartment!.bars).toEqual([
      { label: 'Public works', count: 2, color: colors.primary },
      { label: NOT_ASSIGNED, count: 3, color: colors.textMuted },
    ]);
  });

  it('gives an empty chart when no incident has a category or a department', () => {
    const [, categories, departments] = dashboardCharts({
      ...STATISTICS,
      byCategory: [],
      byDepartment: [],
    });
    expect(categories!.bars).toEqual([]);
    expect(departments!.bars).toEqual([]);
  });
});

describe('chartDescription', () => {
  it('says the whole chart in words, for a screen reader', () => {
    expect(chartDescription(dashboardCharts(STATISTICS)[0]!)).toBe(
      'Incidents by status: Reported 3, Validated 0, Assigned 2.',
    );
  });
});
