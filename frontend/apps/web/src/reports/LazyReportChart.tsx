import { Component, lazy, Suspense, type ReactNode } from 'react';

import styles from '../incidents/Incidents.module.css';
import { CHART_UNAVAILABLE, chartHeight } from './report-chart';
import type { ReportChartProps } from './ReportChart';
import reportStyles from './Reports.module.css';

// Plotly travels in its own file, fetched the first time a report has rows, so signing in and the
// other screens do not wait for it.
const ReportChart = lazy(() =>
  import('./ReportChart').then((module) => ({ default: module.ReportChart })),
);

/** The chart of a report, with the room it takes while Plotly loads. */
export function LazyReportChart(props: ReportChartProps) {
  return (
    <div className={reportStyles.chart}>
      <ChartLoadBoundary>
        <Suspense
          fallback={
            <div
              className={reportStyles.chartLoading}
              style={{ height: chartHeight(props.rows.length) }}
            >
              <p className={styles.muted}>Loading the chart…</p>
            </div>
          }
        >
          <ReportChart {...props} />
        </Suspense>
      </ChartLoadBoundary>
    </div>
  );
}

/**
 * Without network the chart's file cannot be fetched. The screen then says so in the chart's place
 * and keeps the table, instead of losing the whole page. A reload fetches the file again.
 */
class ChartLoadBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    return this.state.failed ? (
      <p className={styles.muted}>{CHART_UNAVAILABLE}</p>
    ) : (
      this.props.children
    );
  }
}
