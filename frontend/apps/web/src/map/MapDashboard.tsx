import { formatInstant } from '@urbanpulse/ui';
import { useMemo, type ReactNode } from 'react';

import { hasFilters, type IncidentFilters } from '../incidents/filters/incident-filters';
import { useIncidentStatistics, useLatestIncidents } from '../incidents/incident-queries';
import incidentStyles from '../incidents/Incidents.module.css';
import { LoadFailure } from '../incidents/LoadFailure';
import { StatusBadge } from '../incidents/StatusBadge';
import { dashboardCharts, formatCount, priorityFigures, reportedAgo } from './dashboard-data';
import { LazyStatisticsChart } from './LazyStatisticsChart';
import styles from './Map.module.css';
import type { MapPanel } from './map-preferences';

interface MapDashboardProps {
  filters: IncidentFilters;
  /** Whether a panel of the dashboard is open, as the user left it. */
  isOpen: (panel: MapPanel) => boolean;
  onToggle: (panel: MapPanel, open: boolean) => void;
  /** Opens the summary of an incident of the latest list, as a marker does. */
  onSelectIncident: (incidentId: string) => void;
}

/**
 * The indicators beside the map (#177): how many incidents match the filters of the map, by
 * priority, status, category and department, and the latest ones reported. The API counts them with
 * the same filters and scope as the map, with no cap, and they are read again with the markers. A
 * failed refresh keeps the last counts with a warning; they are never shown as zeros. Each panel
 * folds, and stays as the user left it.
 */
export function MapDashboard({ filters, isOpen, onToggle, onSelectIncident }: MapDashboardProps) {
  const statistics = useIncidentStatistics(filters);
  const charts = useMemo(
    () => (statistics.data ? dashboardCharts(statistics.data) : []),
    [statistics.data],
  );

  const panel = (id: MapPanel, title: string, children: ReactNode) => (
    <DashboardPanel
      key={id}
      title={title}
      open={isOpen(id)}
      onToggle={(open) => onToggle(id, open)}
    >
      {children}
    </DashboardPanel>
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
              <dd>
                <Count count={statistics.data.total} />
              </dd>
            </div>
          </dl>

          {statistics.data.total === 0 ? (
            <p className={incidentStyles.muted}>
              {hasFilters(filters)
                ? 'Nothing to count with these filters.'
                : 'There are no incidents to count.'}
            </p>
          ) : (
            <>
              {panel(
                'priority',
                'By priority',
                <dl className={styles.figures}>
                  {priorityFigures(statistics.data).map((figure) => (
                    <div key={figure.label}>
                      <dt>{figure.label}</dt>
                      <dd>
                        <Count count={figure.count} />
                      </dd>
                    </div>
                  ))}
                </dl>,
              )}
              {charts.map((chart) =>
                panel(chart.id, chart.title, <LazyStatisticsChart chart={chart} />),
              )}
            </>
          )}
        </>
      )}

      {panel(
        'latest',
        'Latest reported',
        <LatestIncidents filters={filters} onSelect={onSelectIncident} />,
      )}
    </section>
  );
}

interface DashboardPanelProps {
  title: string;
  open: boolean;
  onToggle: (open: boolean) => void;
  children: ReactNode;
}

/** A part of the dashboard that folds to its title. */
function DashboardPanel({ title, open, onToggle, children }: DashboardPanelProps) {
  return (
    <details
      className={styles.panelBlock}
      open={open}
      onToggle={(event) => onToggle(event.currentTarget.open)}
    >
      <summary>
        <h3 className={styles.chartTitle}>{title}</h3>
      </summary>
      <div className={styles.chart}>{children}</div>
    </details>
  );
}

/**
 * A count that never spills out of its column: short when it is large (`12.3K`), with the exact
 * number in its tooltip and for a screen reader.
 */
function Count({ count }: { count: number }) {
  const { short, full } = formatCount(count);
  if (short === full) {
    return <>{full}</>;
  }
  return (
    <span title={full}>
      <span aria-hidden="true">{short}</span>
      <span className={incidentStyles.visuallyHidden}>{full}</span>
    </span>
  );
}

interface LatestIncidentsProps {
  filters: IncidentFilters;
  onSelect: (incidentId: string) => void;
}

/**
 * The latest incidents reported that match the filters of the map, newest first, each opening its
 * summary like a marker. Its own request, so a failure here leaves the counts in place.
 */
function LatestIncidents({ filters, onSelect }: LatestIncidentsProps) {
  const latest = useLatestIncidents(filters);

  if (latest.isPending) {
    return <p role="status">Loading the latest incidents…</p>;
  }
  if (latest.isError && !latest.data) {
    return (
      <LoadFailure
        what="The latest incidents"
        error={latest.error}
        retrying={latest.isFetching}
        onRetry={() => void latest.refetch()}
      />
    );
  }
  // "How long ago" is counted from when the list was read, so it changes with every refresh (this
  // reads `dataUpdatedAt`, which renders the list again each time) and never claims to be newer
  // than the data.
  const readAt = new Date(latest.dataUpdatedAt);
  return (
    <>
      {latest.isError && (
        <p className={styles.warning} role="status">
          Not refreshed: as of {formatInstant(readAt.toISOString())}.
        </p>
      )}
      {latest.data.content.length === 0 ? (
        <p className={incidentStyles.muted}>
          {hasFilters(filters) ? 'None with these filters.' : 'None yet.'}
        </p>
      ) : (
        <ol className={styles.latest}>
          {latest.data.content.map((incident) => (
            <li key={incident.id}>
              <button
                type="button"
                className={styles.latestItem}
                onClick={() => onSelect(incident.id)}
              >
                <span className={styles.latestTitle}>{incident.title}</span>
                <span className={styles.latestMeta}>
                  <StatusBadge status={incident.status} />
                  <time dateTime={incident.reportedAt} title={formatInstant(incident.reportedAt)}>
                    {reportedAgo(incident.reportedAt, readAt)}
                  </time>
                </span>
              </button>
            </li>
          ))}
        </ol>
      )}
    </>
  );
}
