import { colors } from '@urbanpulse/ui';
import Plotly, {
  type Config,
  type Data,
  type Layout,
  type LayoutAxis,
} from 'plotly.js-basic-dist-min';
import { useMemo, useState } from 'react';
import createPlotlyComponent from 'react-plotly.js/factory';

import { iconDataUrl } from './category-icons';
import { chartDescription, chartHeight, formatCount, type DashboardChart } from './dashboard-data';
import { ChartTable } from './ChartTable';

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
 */
export function StatisticsChart({ chart }: StatisticsChartProps) {
  const [drawFailed, setDrawFailed] = useState(false);
  // New objects make Plotly draw again, so they change only with the counts.
  const data = useMemo(() => toTraces(chart), [chart]);
  const layout = useMemo(() => toLayout(chart), [chart]);
  // The box keeps the height of the chart, so the next chart starts under it.
  const style = useMemo(() => ({ width: '100%', height: chartHeight(chart.bars.length) }), [chart]);

  if (drawFailed) {
    return <ChartTable chart={chart} />;
  }
  return (
    // The bars mean nothing to a screen reader, so it reads the chart in words instead.
    <div role="img" aria-label={chartDescription(chart)}>
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

/** Room between a bar's name and the plot for its icon, in pixels. */
const ICON_ROOM_PX = 20;
/** How much of a bar's band its icon takes. */
const ICON_BAND_SHARE = 0.75;

/**
 * The icon of each bar that has one, right of its name (#267). Plotly draws images, not markup;
 * each one is as tall as its share of the band and keeps its proportions.
 */
function iconImages(bars: DashboardChart['bars']): Partial<Layout>['images'] {
  return bars.flatMap((bar) =>
    bar.icon
      ? [
          {
            source: iconDataUrl(bar.icon, colors.text, 32),
            xref: 'paper' as const,
            yref: 'y' as const,
            x: 0,
            y: bar.label,
            sizex: 1,
            sizey: ICON_BAND_SHARE,
            sizing: 'contain' as const,
            xanchor: 'right' as const,
            yanchor: 'middle' as const,
          },
        ]
      : [],
  );
}

function toLayout({ bars }: DashboardChart): Partial<Layout> {
  const largest = Math.max(0, ...bars.map((bar) => bar.count));
  const withIcons = bars.some((bar) => bar.icon);
  return {
    height: chartHeight(bars.length),
    autosize: true,
    // The left margin grows to the longest label; the right one leaves room for the counts.
    margin: { t: 4, r: 36, b: 4, l: 8 },
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
    // `ticklabelstandoff` is in Plotly since 2.29 but not yet in its types, hence the assertion.
    yaxis: {
      type: 'category',
      autorange: 'reversed',
      automargin: true,
      fixedrange: true,
      // The names step aside to leave the icons room between them and the bars.
      ticklabelstandoff: withIcons ? ICON_ROOM_PX : 0,
    } as Partial<LayoutAxis>,
    images: iconImages(bars),
  };
}
