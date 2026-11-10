import type { components } from '@urbanpulse/api-client';
import { INCIDENT_STATUSES } from '@urbanpulse/domain';
import { formatInstant, STATUS_LABELS, statusColor } from '@urbanpulse/ui';
import { lazy, Suspense, useRef, useState, type CSSProperties } from 'react';
import type { MapRef } from 'react-map-gl/maplibre';
import { useSearchParams } from 'react-router';

import { IncidentFilterBar } from '../incidents/filters/IncidentFilterBar';
import {
  filtersFrom,
  hasFilters,
  withFilters,
  type IncidentFilters,
} from '../incidents/filters/incident-filters';
import { useIncidentLocations } from '../incidents/incident-queries';
import incidentStyles from '../incidents/Incidents.module.css';
import { PRIORITY_LABELS } from '../incidents/labels';
import { LoadFailure } from '../incidents/LoadFailure';
import { HIGH_BORDER, PRIORITY_MARKS, PRIORITY_RANK } from './incident-features';
import { IncidentPanel } from './IncidentPanel';
import styles from './Map.module.css';
import { INCIDENT_ZOOM } from './map-config';

type IncidentPosition = components['schemas']['IncidentPosition'];

// MapLibre is most of the weight of the web app, so only this screen downloads it.
const IncidentMap = lazy(() =>
  import('./IncidentMap').then((module) => ({ default: module.IncidentMap })),
);

/**
 * The live map of incidents (#53, #196): the incidents the user may see that match the filters, the
 * same filters as the lists, kept in the address. The API decides which ones the user may see: an
 * operator gets every incident, a technician their active assignments. The positions are read again
 * on an interval, so new incidents appear without reloading. Every incident is also in a list under
 * the map, which opens the same panel from the keyboard.
 */
export function MapPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = filtersFrom(searchParams);
  const locations = useIncidentLocations(filters);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const mapRef = useRef<MapRef>(null);
  const firstFilter = useRef<HTMLInputElement>(null);
  // Where the focus goes back when the panel closes: the button of the list that opened it.
  const opener = useRef<HTMLElement | null>(null);
  const positions = locations.data?.content ?? [];

  const changeFilters = (next: IncidentFilters) => setSearchParams(withFilters(searchParams, next));

  const select = (incidentId: string) => {
    opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setSelectedId(incidentId);
  };

  const selectFromList = (position: IncidentPosition) => {
    select(position.id);
    const map = mapRef.current;
    map?.flyTo({
      center: [position.longitude, position.latitude],
      zoom: Math.max(map.getZoom(), INCIDENT_ZOOM),
    });
  };

  const closePanel = () => {
    setSelectedId(null);
    if (opener.current?.isConnected) {
      opener.current.focus();
    }
  };

  const clearFilters = () => {
    changeFilters({});
    firstFilter.current?.focus();
  };

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <header className={incidentStyles.header}>
        <h1 id="page-title" className={incidentStyles.title}>
          Map
        </h1>
        <p className={incidentStyles.lead}>
          The incidents that match the filters, updated on their own. Select one to see its summary.
        </p>
      </header>

      <IncidentFilterBar filters={filters} onChange={changeFilters} firstFilterRef={firstFilter} />

      <div className={styles.messages}>
        {locations.isPending ? (
          <p role="status">Loading the incidents…</p>
        ) : locations.isError && !locations.data ? (
          <LoadFailure
            what="The incidents"
            error={locations.error}
            retrying={locations.isFetching}
            onRetry={() => void locations.refetch()}
          />
        ) : (
          <>
            {locations.isError && (
              <p className={styles.warning} role="alert">
                The map could not be refreshed. It shows the incidents as of{' '}
                {formatInstant(new Date(locations.dataUpdatedAt).toISOString())} and will try again
                on its own.
              </p>
            )}
            {locations.data?.truncated && (
              <p className={styles.warning}>
                The map shows the {positions.length} most relevant incidents. Narrow the filters to
                see the rest.
              </p>
            )}
            {positions.length === 0 ? (
              <div className={styles.empty}>
                {hasFilters(filters) ? (
                  <>
                    <p>No incidents match these filters.</p>
                    <button type="button" className={incidentStyles.button} onClick={clearFilters}>
                      Clear filters
                    </button>
                  </>
                ) : (
                  <p>There are no incidents to show.</p>
                )}
              </div>
            ) : (
              <p className={incidentStyles.muted} aria-live="polite">
                {positions.length} {positions.length === 1 ? 'incident' : 'incidents'} on the map
              </p>
            )}
          </>
        )}
      </div>

      <div className={selectedId ? styles.layoutWithPanel : styles.layout}>
        <Suspense
          fallback={
            <div className={styles.mapFrame}>
              <p className={styles.mapNotice} role="status">
                Loading the map…
              </p>
            </div>
          }
        >
          <IncidentMap
            ref={mapRef}
            positions={positions}
            selectedId={selectedId}
            onSelect={select}
          />
        </Suspense>
        {selectedId && <IncidentPanel incidentId={selectedId} onClose={closePanel} />}
      </div>

      <MapLegend />

      {positions.length > 0 && (
        <details className={styles.list}>
          <summary>List of the incidents on the map, the most relevant first</summary>
          <ol>
            {positions.map((position) => (
              <li key={position.id}>
                <button
                  type="button"
                  className={styles.listItem}
                  aria-current={position.id === selectedId ? 'true' : undefined}
                  onClick={() => selectFromList(position)}
                >
                  <span
                    className={incidentStyles.status}
                    style={{ '--status-color': statusColor(position.status) } as CSSProperties}
                  >
                    {STATUS_LABELS[position.status]}
                  </span>
                  <span>
                    {position.priority
                      ? `${PRIORITY_LABELS[position.priority]} priority`
                      : 'No priority'}
                  </span>
                  <span className={incidentStyles.muted}>
                    {position.latitude.toFixed(4)}, {position.longitude.toFixed(4)}
                  </span>
                </button>
              </li>
            ))}
          </ol>
        </details>
      )}
    </section>
  );
}

/**
 * What the map draws: the color is the status, the size the priority, and a group shows how many
 * incidents it holds and their highest priority. The list and the panel say the same in words.
 */
function MapLegend() {
  return (
    <div className={styles.legend}>
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
    </div>
  );
}
