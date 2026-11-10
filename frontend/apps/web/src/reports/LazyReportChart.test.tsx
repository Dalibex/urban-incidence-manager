import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { LazyReportChart } from './LazyReportChart';
import { CHART_UNAVAILABLE } from './report-chart';

// Without network the chart's file cannot be fetched, so importing it fails.
vi.mock('./ReportChart', () => {
  throw new TypeError('Failed to fetch dynamically imported module');
});

afterEach(() => {
  vi.restoreAllMocks();
});

const ROWS = [
  {
    group: 'REPORTED',
    total: 2,
    open: 2,
    closed: 0,
    rejected: 0,
    resolutionHoursP50: null,
    resolutionHoursP95: null,
  },
];

describe('report chart without its file', () => {
  it('says the chart is not available instead of breaking the page', async () => {
    // React reports the error it catches; the test only cares about what the screen shows.
    vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<LazyReportChart groupBy="status" rows={ROWS} />);

    expect(screen.getByText('Loading the chart…')).toBeDefined();
    expect(await screen.findByText(CHART_UNAVAILABLE)).toBeDefined();
  });
});
