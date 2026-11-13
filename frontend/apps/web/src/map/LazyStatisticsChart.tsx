import { Component, lazy, Suspense, type ReactNode } from 'react';

import incidentStyles from '../incidents/Incidents.module.css';
import { ChartTable } from './ChartTable';
import { chartHeight, type DashboardChart } from './dashboard-data';
import styles from './Map.module.css';

// Plotly travels in its own file, fetched the first time the dashboard has counts, so signing in
// and the other screens do not wait for it (#173).
const StatisticsChart = lazy(() =>
  import('./StatisticsChart').then((module) => ({ default: module.StatisticsChart })),
);

/** A chart of the dashboard, with the room it takes while Plotly loads. */
export function LazyStatisticsChart({ chart }: { chart: DashboardChart }) {
  return (
    <ChartLoadBoundary chart={chart}>
      <Suspense
        fallback={
          <div className={styles.chartLoading} style={{ height: chartHeight(chart.bars.length) }}>
            <p className={incidentStyles.muted}>Loading the chart…</p>
          </div>
        }
      >
        <StatisticsChart chart={chart} />
      </Suspense>
    </ChartLoadBoundary>
  );
}

interface BoundaryProps {
  chart: DashboardChart;
  children: ReactNode;
}

/**
 * Without network the chart's file cannot be fetched. The chart then shows its figures as a table,
 * instead of the dashboard or the map breaking. A reload fetches the file again.
 */
class ChartLoadBoundary extends Component<BoundaryProps, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    return this.state.failed ? <ChartTable chart={this.props.chart} /> : this.props.children;
  }
}
