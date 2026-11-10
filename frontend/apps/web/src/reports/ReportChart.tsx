import type { components } from '@urbanpulse/api-client';
import { colors } from '@urbanpulse/ui';
import Plotly, { type Config, type Data, type Layout } from 'plotly.js-basic-dist-min';
import { useMemo, useState } from 'react';
import createPlotlyComponent from 'react-plotly.js/factory';

import styles from '../incidents/Incidents.module.css';
import {
  CHART_UNAVAILABLE,
  chartHeight,
  reportChartData,
  type ReportChartData,
  type ReportPart,
} from './report-chart';
import { GROUPING_LABELS, type ReportGrouping } from './report-period';

type ReportRow = components['schemas']['IncidentReportRow'];

export interface ReportChartProps {
  groupBy: ReportGrouping;
  rows: ReportRow[];
}

// The basic bundle (bar, pie, scatter) instead of the whole library, which is several times larger.
const Plot = createPlotlyComponent(Plotly);

/**
 * Three hues that people with a colour vision deficiency still tell apart in every pair. The aqua
 * of `closed` has less than 3:1 contrast with the page, so the legend names each colour and the
 * table under the chart has every figure.
 */
const PART_COLORS: Record<ReportPart, string> = {
  open: '#2a78d6',
  closed: '#1baf7a',
  rejected: '#eb6834',
};

/** Up to this many incidents the axis counts one by one; Plotly would otherwise mark halves. */
const UNIT_TICKS_UP_TO = 10;

// The chart only reads: no zoom, pan or toolbar, which a few bars do not need.
const CONFIG: Partial<Config> = { displayModeBar: false, displaylogo: false };

const PLOT_STYLE = { width: '100%' };

/**
 * The report as one stacked bar per group: open, closed and rejected add up to its total. It reads
 * the rows of the table, so it asks the API for nothing. Loaded on its own (`LazyReportChart`),
 * because Plotly is most of its weight.
 */
export function ReportChart({ groupBy, rows }: ReportChartProps) {
  const [drawFailed, setDrawFailed] = useState(false);
  // New objects make the chart draw again, so they change only with the report.
  const traces = useMemo(() => toTraces(reportChartData(groupBy, rows)), [groupBy, rows]);
  const layout = useMemo(() => toLayout(rows), [rows]);

  if (drawFailed) {
    return <p className={styles.muted}>{CHART_UNAVAILABLE}</p>;
  }
  return (
    // The bars are a picture of the table below, so assistive technologies read this label and then
    // the table instead of the shapes of the chart.
    <div
      role="img"
      aria-label={`Open, closed and rejected incidents by ${GROUPING_LABELS[groupBy].toLowerCase()}. The table below has the same figures.`}
    >
      <Plot
        data={traces}
        layout={layout}
        config={CONFIG}
        style={PLOT_STYLE}
        useResizeHandler
        onError={() => setDrawFailed(true)}
      />
    </div>
  );
}

function toTraces(data: ReportChartData): Data[] {
  return data.series.map((series) => ({
    type: 'bar',
    orientation: 'h',
    name: series.label,
    y: data.groups,
    x: series.counts,
    // A line of the page's colour leaves a gap between the parts of a bar.
    marker: { color: PART_COLORS[series.part], line: { color: colors.background, width: 2 } },
  }));
}

function toLayout(rows: ReportRow[]): Partial<Layout> {
  const largestTotal = Math.max(0, ...rows.map((row) => row.total));
  return {
    barmode: 'stack',
    bargap: 0.35,
    height: chartHeight(rows.length),
    autosize: true,
    // The left margin grows to the longest group name.
    margin: { t: 40, r: 16, b: 56, l: 8 },
    font: { family: 'system-ui, sans-serif', size: 14, color: colors.text },
    paper_bgcolor: colors.background,
    plot_bgcolor: colors.background,
    hovermode: 'y unified',
    // Plotly lists stacked bars backwards; the legend keeps the order of the table's columns.
    legend: {
      orientation: 'h',
      traceorder: 'normal',
      x: 0,
      xanchor: 'left',
      y: 1,
      yanchor: 'bottom',
    },
    xaxis: {
      title: { text: 'Incidents' },
      rangemode: 'tozero',
      dtick: largestTotal <= UNIT_TICKS_UP_TO ? 1 : undefined,
      gridcolor: colors.surface,
      zeroline: false,
      fixedrange: true,
      tickfont: { color: colors.textMuted },
    },
    // Categories keep the order of the table, from the top down.
    yaxis: { type: 'category', autorange: 'reversed', automargin: true, fixedrange: true },
  };
}
