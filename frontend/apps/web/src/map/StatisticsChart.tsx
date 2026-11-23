import { colors } from '@urbanpulse/ui';
import Plotly, { type Config, type Data, type Layout } from 'plotly.js-basic-dist-min';
import { useMemo, useState, type CSSProperties } from 'react';
import createPlotlyComponent from 'react-plotly.js/factory';

import { CategoryIconGlyph } from './CategoryIconGlyph';
import { chartDescription, chartHeight, formatCount, type DashboardChart } from './dashboard-data';
import { ChartTable } from './ChartTable';
import styles from './Map.module.css';

export interface StatisticsChartProps {
  chart: DashboardChart;
}

// The basic bundle (bar, pie, scatter) instead of the whole library, which is several times
// larger (#173).
const Plot = createPlotlyComponent(Plotly);

// The chart only reads: no zoom, pan or toolbar, which a few bars do not need. Not `responsive`:
// that makes Plotly take the height of its container, which has none of its own, so the chart
// collapsed to 0 px and the charts were drawn over each other. `useResizeHandler` follows the width.
const CONFIG: Partial<Config> = { displayModeBar: false, displaylogo: false };

/**
 * One chart of the dashboard: a horizontal bar per group, in the order of the API, with its count
 * written at the end of the bar. Loaded on its own (`LazyStatisticsChart`), because Plotly is most
 * of its weight. If Plotly cannot draw it, the same figures show as a table.
 *
 * A chart whose bars have icons (by category, #267) writes the names itself, each with its icon,
 * in a column of rows as tall as the bands of the bars: Plotly draws images where it can only be
 * told in units of the plot, not pixels, so an icon there landed over the name.
 */
export function StatisticsChart({ chart }: StatisticsChartProps) {
  const [drawFailed, setDrawFailed] = useState(false);
  // New objects make Plotly draw again, so they change only with the counts.
  const data = useMemo(() => toTraces(chart), [chart]);
  const layout = useMemo(() => toLayout(chart), [chart]);
  // The box keeps the height of the chart, so the next chart starts under it.
  const style = useMemo(() => ({ width: '100%', height: chartHeight(chart.bars.length) }), [chart]);
  const withIcons = hasIcons(chart);

  if (drawFailed) {
    return <ChartTable chart={chart} />;
  }
  return (
    // The bars mean nothing to a screen reader, so it reads the chart in words instead.
    <div
      role="img"
      aria-label={chartDescription(chart)}
      className={withIcons ? styles.iconChart : undefined}
    >
      {withIcons && (
        <ol
          className={styles.iconChartNames}
          style={{ '--rows': chart.bars.length } as CSSProperties}
          aria-hidden="true"
        >
          {chart.bars.map((bar) => (
            <li key={bar.label}>
              {bar.icon && <CategoryIconGlyph icon={bar.icon} className={styles.legendIcon} />}
              <span title={bar.label}>{bar.label}</span>
            </li>
          ))}
        </ol>
      )}
      <Plot
        data={data}
        layout={layout}
        config={CONFIG}
        style={style}
        useResizeHandler
        onError={() => setDrawFailed(true)}
      />
    </div>
  );
}

function toTraces({ bars }: DashboardChart): Data[] {
  return [
    {
      type: 'bar',
      orientation: 'h',
      y: bars.map((bar) => bar.label),
      x: bars.map((bar) => bar.count),
      // Short when large (12.3K), so the label fits beside its bar; the tooltip has the exact count.
      text: bars.map((bar) => formatCount(bar.count).short),
      textposition: 'outside',
      cliponaxis: false,
      textfont: { color: colors.text },
      // The exact count with thousands separators (12,345), whatever the axis abbreviates.
      hovertemplate: '%{y}: %{x:,}<extra></extra>',
      // A line of the page's colour leaves a gap between neighbouring bars.
      marker: { color: bars.map((bar) => bar.color), line: { color: colors.background, width: 1 } },
    },
  ];
}

function toLayout({ bars }: DashboardChart): Partial<Layout> {
  const largest = Math.max(0, ...bars.map((bar) => bar.count));
  const withIcons = hasIcons({ bars } as DashboardChart);
  return {
    height: chartHeight(bars.length),
    autosize: true,
    // The left margin grows to the longest label; the right one leaves room for the counts. The
    // same top and bottom margins as the column of names, so its rows match the bands.
    margin: { t: CHART_MARGIN_PX, r: 36, b: CHART_MARGIN_PX, l: 8 },
    bargap: 0.25,
    font: { family: 'system-ui, sans-serif', size: 12, color: colors.text },
    paper_bgcolor: colors.background,
    plot_bgcolor: colors.background,
    showlegend: false,
    // The counts are written on the bars, so the axis only sets the scale; with no incidents it
    // still needs a range, or Plotly draws the bars of 0 in the middle.
    xaxis: {
      visible: false,
      range: [0, Math.max(1, largest)],
      fixedrange: true,
    },
    // The groups keep the order of the API, from the top down.
    yaxis: {
      type: 'category',
      autorange: 'reversed',
      automargin: true,
      fixedrange: true,
      // The column beside the chart writes the names with their icons.
      showticklabels: !withIcons,
    },
  };
}

/** Top and bottom margin of the plot, in pixels; the column of names keeps the same. */
const CHART_MARGIN_PX = 4;

function hasIcons({ bars }: DashboardChart): boolean {
  return bars.some((bar) => bar.icon);
}
