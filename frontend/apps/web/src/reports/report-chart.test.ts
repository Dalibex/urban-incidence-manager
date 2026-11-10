import { describe, expect, it } from 'vitest';

import { reportChartData } from './report-chart';

const row = (group: string | null, open: number, closed: number, rejected: number) => ({
  group,
  total: open + closed + rejected,
  open,
  closed,
  rejected,
  resolutionHoursP50: null,
  resolutionHoursP95: null,
});

describe('report chart', () => {
  it('stacks open, closed and rejected for each group, in the order of the report', () => {
    const data = reportChartData('category', [
      row('Street lighting', 5, 6, 1),
      row('Waste', 0, 2, 0),
    ]);

    expect(data.groups).toEqual(['Street lighting', 'Waste']);
    expect(data.series).toEqual([
      { part: 'open', label: 'Open', counts: [5, 0] },
      { part: 'closed', label: 'Closed', counts: [6, 2] },
      { part: 'rejected', label: 'Rejected', counts: [1, 0] },
    ]);
  });

  it('names the groups as the table does: statuses by label, a missing group as not available', () => {
    const data = reportChartData('status', [row('IN_PROGRESS', 2, 0, 0), row(null, 1, 0, 0)]);

    expect(data.groups).toEqual(['In progress', 'Not available']);
  });

  it('names priorities by their label', () => {
    expect(reportChartData('priority', [row('HIGH', 1, 0, 0)]).groups).toEqual(['High']);
  });

  it('has no bars for a report without rows', () => {
    const data = reportChartData('district', []);

    expect(data.groups).toEqual([]);
    expect(data.series.every((series) => series.counts.length === 0)).toBe(true);
  });
});
