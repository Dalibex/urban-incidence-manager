import type { components } from '@urbanpulse/api-client';
import { colors, statusColor } from '@urbanpulse/ui';
import { describe, expect, it } from 'vitest';

import {
  chartDescription,
  dashboardCharts,
  formatCount,
  NO_PRIORITY,
  NOT_ASSIGNED,
  NOT_CLASSIFIED,
  priorityFigures,
  reportedAgo,
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
      id: 'status',
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

describe('formatCount', () => {
  it('writes every digit below ten thousand, where each one counts', () => {
    expect(formatCount(7)).toEqual({ short: '7', full: '7' });
    expect(formatCount(3000)).toEqual({ short: '3,000', full: '3,000' });
    expect(formatCount(9999)).toEqual({ short: '9,999', full: '9,999' });
  });

  it('writes a large count short, so it fits its column, and keeps the full one', () => {
    expect(formatCount(10_000)).toEqual({ short: '10K', full: '10,000' });
    expect(formatCount(12_345)).toEqual({ short: '12.3K', full: '12,345' });
    expect(formatCount(2_500_000)).toEqual({ short: '2.5M', full: '2,500,000' });
  });
});

describe('reportedAgo', () => {
  const NOW = new Date('2026-11-11T12:00:00Z');

  it('says how long ago in the largest unit that fits', () => {
    expect(reportedAgo('2026-11-11T11:59:40Z', NOW)).toBe('just now');
    expect(reportedAgo('2026-11-11T11:55:00Z', NOW)).toBe('5 minutes ago');
    expect(reportedAgo('2026-11-11T09:00:00Z', NOW)).toBe('3 hours ago');
    // Never "yesterday": 25 hours ago may be two calendar days back.
    expect(reportedAgo('2026-11-10T11:00:00Z', NOW)).toBe('25 hours ago');
    expect(reportedAgo('2026-11-09T11:00:00Z', NOW)).toBe('2 days ago');
    expect(reportedAgo('2026-11-01T12:00:00Z', NOW)).toBe('10 days ago');
  });

  it('reads a time ahead of the clock as now, never as the future', () => {
    expect(reportedAgo('2026-11-11T12:05:00Z', NOW)).toBe('just now');
  });
});

describe('chartDescription', () => {
  it('says the whole chart in words, for a screen reader', () => {
    expect(chartDescription(dashboardCharts(STATISTICS)[0]!)).toBe(
      'Incidents by status: Reported 3, Validated 0, Assigned 2.',
    );
  });
});
