import type { components } from '@urbanpulse/api-client';
import { formatInstant, STATUS_LABELS, statusColor } from '@urbanpulse/ui';
import { lazy, Suspense, useEffect, useRef, useState, type CSSProperties } from 'react';
import type { MapRef } from 'react-map-gl/maplibre';
import { useSearchParams } from 'react-router';

import { IncidentFilterBar } from '../incidents/filters/IncidentFilterBar';
import {
  filtersFrom,
  hasFilters,
  withFilters,
  type IncidentFilters,
} from '../incidents/filters/incident-filters';
import { useCategories, useDepartments, useIncidentLocations } from '../incidents/incident-queries';
import incidentStyles from '../incidents/Incidents.module.css';
import { PRIORITY_LABELS } from '../incidents/labels';
import { LoadFailure } from '../incidents/LoadFailure';
import { filterSummary } from './filter-summary';
import { IncidentPanel } from './IncidentPanel';
import { MapDashboard } from './MapDashboard';
import { MapKey } from './MapKey';
import styles from './Map.module.css';
import { INCIDENT_ZOOM, MAP_SCREEN_ID } from './map-config';
import { useMapPreferences } from './map-preferences';

type IncidentPosition = components['schemas']['IncidentPosition'];

// MapLibre is most of the weight of the web app, so only this screen downloads it.
const IncidentMap = lazy(() =>
  import('./IncidentMap').then((module) => ({ default: module.IncidentMap })),
);

const FILTERS_ID = 'map-filters';

/**
 * The live map of incidents (#53, #196): the incidents the user may see that match the filters, the
 * same filters as the lists, kept in the address. The API decides which ones the user may see: an
 * operator gets every incident, a technician their active assignments. The positions are read again
 * on an interval, so new incidents appear without reloading. Beside the map, the summary of the
 * same incidents (#177), or the incident selected on it. Every incident is also in a list under
 * the summary, which opens the same panel from the keyboard.
 *
 * The filters fold behind a button, so the map keeps the room; while folded, a line says which
 * ones apply, so nobody forgets that the map is filtered.
 */
export function MapPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = filtersFrom(searchParams);
  const locations = useIncidentLocations(filters);
  const categories = useCategories();
  const departments = useDepartments();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);
  const preferences = useMapPreferences();
  const mapRef = useRef<MapRef>(null);
  const firstFilter = useRef<HTMLInputElement>(null);
  const filtersButton = useRef<HTMLButtonElement>(null);
  // Where the focus goes back when the panel closes: the button that opened it. The dashboard stays
  // mounted while the panel is open, only hidden, so a button inside it is still there.
  const opener = useRef<HTMLElement | null>(null);
  const positions = locations.data?.content ?? [];
  const activeFilters = filterSummary(filters, categories.data, departments.data);

  const changeFilters = (next: IncidentFilters) => setSearchParams(withFilters(searchParams, next));

  const select = (incidentId: string) => {
    opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setSelectedId(incidentId);
  };

  /** Selects an incident and centres the map on it, when it is on the map. */
  const selectAndShow = (incidentId: string) => {
    select(incidentId);
    const position = positions.find((each) => each.id === incidentId);
    const map = mapRef.current;
    if (position && map) {
      map.flyTo({
        center: [position.longitude, position.latitude],
        zoom: Math.max(map.getZoom(), INCIDENT_ZOOM),
      });
    }
  };

  const selectFromList = (position: IncidentPosition) => selectAndShow(position.id);

  const closePanel = () => {
    setSelectedId(null);
  };

  // The opener can only take the focus once the dashboard is shown again, after the render that
  // closed the panel; a button inside a hidden block cannot be focused.
  useEffect(() => {
    if (selectedId === null && opener.current?.isConnected) {
      opener.current.focus();
      opener.current = null;
    }
  }, [selectedId]);

  // The clear buttons go away when pressed, so the focus stays in the filters: on the first one
  // when the bar is open, on the button that opens it when it is folded.
  const clearFilters = () => {
    changeFilters({});
    (firstFilter.current ?? filtersButton.current)?.focus();
  };

  return (
    // The whole screen fills the height under the navbar (AppLayout), and the full-screen button
    // of the map takes all of it, filters included (map-config.ts).
    <section
      id={MAP_SCREEN_ID}
      className={styles.page}
      aria-labelledby="page-title"
      data-fill-height
    >
      <header className={styles.toolbar}>
        <h1 id="page-title" className={styles.pageTitle}>
          Map
        </h1>
        <button
          ref={filtersButton}
          type="button"
          className={incidentStyles.button}
          aria-expanded={filtersOpen}
          aria-controls={FILTERS_ID}
          onClick={() => setFiltersOpen((open) => !open)}
        >
          {activeFilters.length > 0 ? `Filters (${activeFilters.length})` : 'Filters'}
        </button>
        {!filtersOpen &&
          (activeFilters.length > 0 ? (
            <>
              {/* Cut to one line; the title shows the whole list on hover. */}
              <p className={styles.filterSummary} title={activeFilters.join(' · ')}>
                {activeFilters.join(' · ')}
              </p>
              <button type="button" className={incidentStyles.button} onClick={clearFilters}>
                Clear filters
              </button>
            </>
          ) : (
            <p className={styles.filterSummary}>No filters: every incident you can see.</p>
          ))}
      </header>

      {filtersOpen && (
        <div id={FILTERS_ID} className={styles.filters}>
          <IncidentFilterBar
            filters={filters}
            onChange={changeFilters}
            firstFilterRef={firstFilter}
          />
        </div>
      )}

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

      {/* The map keeps its size: the summary and the selected incident share the side column. */}
      <div className={styles.workspace}>
        <div className={styles.mapArea}>
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
          <MapKey
            open={preferences.isOpen('key')}
            onToggle={(open) => preferences.setPanel('key', open)}
          />
        </div>
        <div className={styles.side}>
          {selectedId && <IncidentPanel incidentId={selectedId} onClose={closePanel} />}
          {/* Hidden, not removed, while an incident is open: its queries keep their data and the
              button that opened the incident is still there to take the focus back. */}
          <div className={styles.sideContent} hidden={selectedId !== null}>
            <MapDashboard
              filters={filters}
              isOpen={preferences.isOpen}
              onToggle={preferences.setPanel}
              onSelectIncident={selectAndShow}
            />
            {/* In the column, not under the map, so opening it never takes height from the map. */}
            {positions.length > 0 && (
              <details className={styles.list}>
                <summary>List of the incidents on the map, the most relevant first</summary>
                <ol>
                  {positions.map((position) => (
                    <li key={position.id}>
                      <button
                        type="button"
                        className={styles.listItem}
                        onClick={() => selectFromList(position)}
                      >
                        <span
                          className={incidentStyles.status}
                          style={
                            { '--status-color': statusColor(position.status) } as CSSProperties
                          }
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
          </div>
        </div>
      </div>
    </section>
  );
}
