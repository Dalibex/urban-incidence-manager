import { render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { CHART_UNAVAILABLE } from './ChartTable';
import type { DashboardChart } from './dashboard-data';

const CHART: DashboardChart = {
  title: 'By category',
  bars: [
    { label: 'Pavement', count: 2, color: '#2563EB' },
    { label: 'Not classified', count: 1, color: '#64748B' },
  ],
};

afterEach(() => {
  vi.doUnmock('./StatisticsChart');
  vi.doUnmock('react-plotly.js/factory');
  vi.doUnmock('plotly.js-basic-dist-min');
  vi.resetModules();
  vi.restoreAllMocks();
});

/** The figures of the table a chart falls back to, as "label count". */
function tableRows(): string[] {
  return within(screen.getByRole('table'))
    .getAllByRole('row')
    .map((row) => row.textContent ?? '');
}

// #258 T19: a chart that cannot be shown still gives its figures.
describe('dashboard chart that cannot be shown', () => {
  it('shows its figures as a table when its file cannot be fetched (no network)', async () => {
    vi.doMock('./StatisticsChart', () => {
      throw new TypeError('Failed to fetch dynamically imported module');
    });
    // React reports the error it catches; the test only cares about what the screen shows.
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { LazyStatisticsChart } = await import('./LazyStatisticsChart');

    render(<LazyStatisticsChart chart={CHART} />);

    expect(screen.getByText('Loading the chart…')).toBeDefined();
    expect(await screen.findByText(CHART_UNAVAILABLE)).toBeDefined();
    expect(tableRows()).toEqual(['Pavement2', 'Not classified1']);
  });

  it('shows its figures as a table when Plotly fails to draw it', async () => {
    // A Plotly that fails as soon as it draws, as the real one reports through `onError`. The
    // bundle itself is not needed, and parsing its megabyte would only slow the test down.
    vi.doMock('plotly.js-basic-dist-min', () => ({ default: {} }));
    vi.doMock('react-plotly.js/factory', async () => {
      const { useEffect } = await import('react');
      return {
        default: () =>
          function FailingPlot({ onError }: { onError?: (error: Error) => void }) {
            useEffect(() => onError?.(new Error('WebGL is not available')), [onError]);
            return null;
          },
      };
    });
    const { LazyStatisticsChart } = await import('./LazyStatisticsChart');

    render(<LazyStatisticsChart chart={CHART} />);

    expect(await screen.findByText(CHART_UNAVAILABLE)).toBeDefined();
    expect(tableRows()).toEqual(['Pavement2', 'Not classified1']);
  });
});
