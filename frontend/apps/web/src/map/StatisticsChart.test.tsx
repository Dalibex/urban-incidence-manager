import { render, screen, within } from '@testing-library/react';
import type { Layout } from 'plotly.js-basic-dist-min';
import { describe, expect, it, vi } from 'vitest';

import { iconShapes } from './category-icons';
import type { DashboardChart } from './dashboard-data';
import { StatisticsChart } from './StatisticsChart';

// Plotly draws with SVG measurements jsdom does not have. A stand-in shows whether Plotly was told
// to write the names of the bars itself.
vi.mock('plotly.js-basic-dist-min', () => ({ default: {} }));
vi.mock('react-plotly.js/factory', () => ({
  default: () =>
    function PlotStandIn({ layout }: { layout: Partial<Layout> }) {
      return <p data-testid="plot">tick labels {String(layout.yaxis?.showticklabels)}</p>;
    },
}));

const BY_CATEGORY: DashboardChart = {
  id: 'category',
  title: 'By category',
  bars: [
    { label: 'Street lighting', count: 3, color: '#2563EB', icon: 'STREET_LIGHTING' },
    { label: 'Not classified', count: 1, color: '#64748B', icon: 'GENERIC' },
  ],
};

const BY_STATUS: DashboardChart = {
  id: 'status',
  title: 'By status',
  bars: [{ label: 'Reported', count: 3, color: '#D97706' }],
};

// #267: the names of a chart with icons are written beside it, each with its icon, because Plotly
// placed its images over them.
describe('a dashboard chart with category icons', () => {
  it('writes each name with its icon beside the bars, and Plotly writes none', () => {
    render(<StatisticsChart chart={BY_CATEGORY} />);

    const chart = screen.getByRole('img', { name: /Incidents by category/ });
    const names = within(chart).getAllByRole('listitem', { hidden: true });
    expect(names.map((name) => name.textContent)).toEqual(['Street lighting', 'Not classified']);
    expect(names[0]!.querySelector('path')?.getAttribute('d')).toBe(
      iconShapes('STREET_LIGHTING')[0]![1].d,
    );
    expect(screen.getByTestId('plot').textContent).toBe('tick labels false');
  });

  it('keeps the column out of reach of a screen reader, which reads the chart in words', () => {
    render(<StatisticsChart chart={BY_CATEGORY} />);

    expect(screen.getByRole('img').getAttribute('aria-label')).toBe(
      'Incidents by category: Street lighting 3, Not classified 1.',
    );
    expect(screen.queryAllByRole('listitem')).toEqual([]);
  });

  it('leaves a chart without icons to Plotly, names included', () => {
    render(<StatisticsChart chart={BY_STATUS} />);

    expect(screen.queryAllByRole('listitem', { hidden: true })).toEqual([]);
    expect(screen.getByTestId('plot').textContent).toBe('tick labels true');
  });
});
