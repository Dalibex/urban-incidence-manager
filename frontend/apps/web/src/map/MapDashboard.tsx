import { INCIDENT_STATUSES } from '@urbanpulse/domain';
import { formatInstant, STATUS_LABELS, statusColor } from '@urbanpulse/ui';
import { useMemo, type CSSProperties } from 'react';

import { hasFilters, type IncidentFilters } from '../incidents/filters/incident-filters';
import { useIncidentStatistics } from '../incidents/incident-queries';
import incidentStyles from '../incidents/Incidents.module.css';
import { LoadFailure } from '../incidents/LoadFailure';
import { dashboardCharts, priorityFigures } from './dashboard-data';
import { HIGH_BORDER, PRIORITY_MARKS, PRIORITY_RANK } from './incident-features';
import { LazyStatisticsChart } from './LazyStatisticsChart';
import styles from './Map.module.css';

interface MapDashboardProps {
  filters: IncidentFilters;
}

/**
 * The indicators beside the map (#177): how many incidents match the filters of the map, by
 * priority, status, category and department. The API counts them with the same filters and scope
 * as the map, with no cap, and they are read again with the markers. A failed refresh keeps the
 * last counts with a warning; they are never shown as zeros.
 */
export function MapDashboard({ filters }: MapDashboardProps) {
  const statistics = useIncidentStatistics(filters);
  const charts = useMemo(
    () => (statistics.data ? dashboardCharts(statistics.data) : []),
    [statistics.data],
  );

  return (
    <section className={styles.dashboard} aria-labelledby="dashboard-title">
      <h2 id="dashboard-title" className={styles.dashboardTitle}>
        Summary
      </h2>

      {statistics.isPending ? (
        <p role="status">Loading the summary…</p>
      ) : statistics.isError && !statistics.data ? (
        <LoadFailure
          what="The summary"
          error={statistics.error}
          retrying={statistics.isFetching}
          onRetry={() => void statistics.refetch()}
        />
      ) : (
        <>
          {statistics.isError && (
            <p className={styles.warning} role="alert">
              The summary could not be refreshed. It shows the counts as of{' '}
              {formatInstant(new Date(statistics.dataUpdatedAt).toISOString())} and will try again
              on its own.
            </p>
          )}

          <dl className={styles.figures}>
            <div className={styles.totalFigure}>
              <dt>Incidents</dt>
              <dd>{statistics.data.total}</dd>
            </div>
            {priorityFigures(statistics.data).map((figure) => (
              <div key={figure.label}>
                <dt>{figure.label}</dt>
                <dd>{figure.count}</dd>
              </div>
            ))}
          </dl>

          {statistics.data.total === 0 ? (
            <p className={incidentStyles.muted}>
              {hasFilters(filters)
                ? 'Nothing to count with these filters.'
                : 'There are no incidents to count.'}
            </p>
          ) : (
            charts.map((chart) => (
              <section key={chart.title} className={styles.chart} aria-label={chart.title}>
                <h3 className={styles.chartTitle}>{chart.title}</h3>
                <LazyStatisticsChart chart={chart} />
              </section>
            ))
          )}
        </>
      )}

      <MapKey />
    </section>
  );
}

/**
 * What the map draws: the color is the status, the size the priority, and a group shows how many
 * incidents it holds and their highest priority. The list and the panel say the same in words.
 * Folded, because the chart by status already names the colors.
 */
function MapKey() {
  return (
    <details className={styles.legend}>
      <summary>Map key</summary>
      <p className={styles.legendTitle}>Color: status</p>
      <ul className={styles.legendItems}>
        {INCIDENT_STATUSES.map((status) => (
          <li key={status}>
            <span
              className={styles.swatch}
              style={{ background: statusColor(status) }}
              aria-hidden="true"
            />
            {STATUS_LABELS[status]}
          </li>
        ))}
      </ul>
      <p className={styles.legendTitle}>Size: priority</p>
      <ul className={styles.legendItems}>
        {PRIORITY_MARKS.map(({ rank, label, radius }) => (
          <li key={label}>
            <span
              className={styles.sizeSwatch}
              style={
                {
                  '--swatch-size': `${radius * 2}px`,
                  ...(rank === PRIORITY_RANK.HIGH && {
                    '--swatch-border': `${HIGH_BORDER.width}px solid ${HIGH_BORDER.color}`,
                  }),
                } as CSSProperties
              }
              aria-hidden="true"
            />
            {rank === PRIORITY_RANK.HIGH ? `${label}, with a dark border` : label}
          </li>
        ))}
      </ul>
      <p className={styles.legendTitle}>Groups</p>
      <ul className={styles.legendItems}>
        <li>
          <span className={styles.groupSwatch} aria-hidden="true">
            8
          </span>
          The number of incidents, with their highest priority written under it
        </li>
      </ul>
    </details>
  );
}
