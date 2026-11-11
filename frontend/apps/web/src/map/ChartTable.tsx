import incidentStyles from '../incidents/Incidents.module.css';
import type { DashboardChart } from './dashboard-data';
import styles from './Map.module.css';

/** What a chart says when it cannot load or draw: its figures stay, as a table. */
export const CHART_UNAVAILABLE = 'The chart is not available. These are its figures.';

/** The figures of a chart as a table, for when Plotly cannot show them. */
export function ChartTable({ chart }: { chart: DashboardChart }) {
  return (
    <div>
      <p className={incidentStyles.muted}>{CHART_UNAVAILABLE}</p>
      <table className={styles.chartTable}>
        <caption className={incidentStyles.visuallyHidden}>Incidents {chart.title}</caption>
        <tbody>
          {chart.bars.map((bar) => (
            <tr key={bar.label}>
              <th scope="row">{bar.label}</th>
              <td>{bar.count}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
