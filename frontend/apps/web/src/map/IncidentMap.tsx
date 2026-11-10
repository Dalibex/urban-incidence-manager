import 'maplibre-gl/dist/maplibre-gl.css';

import type { components } from '@urbanpulse/api-client';
import {
  addProtocol,
  setWorkerUrl,
  type ExpressionSpecification,
  type GeoJSONSource,
} from 'maplibre-gl';
import mapWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import { Protocol } from 'pmtiles';
import { useMemo, useState, type Ref } from 'react';
import Map, {
  Layer,
  NavigationControl,
  Source,
  type LayerProps,
  type MapLayerMouseEvent,
  type MapRef,
} from 'react-map-gl/maplibre';

import {
  CLUSTER_PRIORITY_COLORS,
  toFeatureCollection,
  type IncidentFeatureProperties,
} from './incident-features';
import styles from './Map.module.css';
import { CLUSTER_MAX_ZOOM, MALAGA_VIEW, mapStyleUrl } from './map-config';

type IncidentPosition = components['schemas']['IncidentPosition'];

// MapLibre looks for its worker next to its own module, which Vite moves when it bundles it, so
// the map would not draw. Vite serves the worker file as it is and gives its address here.
setWorkerUrl(mapWorkerUrl);

// The style loads the tiles as `pmtiles://.../malaga.pmtiles`: one file read with range requests,
// with no tile server (ADR-0016). MapLibre keeps the protocols globally, so it is added once.
addProtocol('pmtiles', new Protocol().tile);

const SOURCE_ID = 'incidents';
const CLUSTER_LAYER = 'incident-clusters';
const POINT_LAYER = 'incident-points';
/** Clusters merge the incidents within this many pixels. */
const CLUSTER_RADIUS = 50;

/** The highest priority of a cluster's incidents, as a cluster property. */
const MAX_PRIORITY = 'maxPriorityRank';

/**
 * The color of a cluster by its highest priority; the legend reads the same table. A `step`
 * expression takes the color of the highest rank not above the value.
 */
const clusterColor: ExpressionSpecification = [
  'step',
  ['get', MAX_PRIORITY],
  CLUSTER_PRIORITY_COLORS[0]!.color,
  ...CLUSTER_PRIORITY_COLORS.slice(1).flatMap(({ rank, color }) => [rank, color]),
] as ExpressionSpecification;

const clusterLayer: LayerProps = {
  id: CLUSTER_LAYER,
  type: 'circle',
  source: SOURCE_ID,
  filter: ['has', 'point_count'],
  paint: {
    'circle-color': clusterColor,
    // Bigger with more incidents, in three steps so sizes stay comparable.
    'circle-radius': ['step', ['get', 'point_count'], 16, 10, 22, 50, 28],
    'circle-stroke-width': 2,
    'circle-stroke-color': '#ffffff',
  },
};

const clusterCountLayer: LayerProps = {
  id: 'incident-cluster-count',
  type: 'symbol',
  source: SOURCE_ID,
  filter: ['has', 'point_count'],
  layout: {
    'text-field': ['get', 'point_count_abbreviated'],
    // A font of the self-hosted glyphs (tiles/Dockerfile), never one from an external host.
    'text-font': ['Noto Sans Bold'],
    'text-size': 13,
    'text-allow-overlap': true,
  },
  paint: { 'text-color': '#ffffff' },
};

const pointLayer: LayerProps = {
  id: POINT_LAYER,
  type: 'circle',
  source: SOURCE_ID,
  filter: ['!', ['has', 'point_count']],
  paint: {
    'circle-color': ['get', 'color'],
    // A higher priority draws a bigger marker, so it stands out among its neighbours.
    'circle-radius': ['interpolate', ['linear'], ['get', 'priorityRank'], 0, 6, 3, 10],
    'circle-stroke-width': 2,
    'circle-stroke-color': '#ffffff',
  },
};

function selectedLayer(incidentId: string | null): LayerProps {
  return {
    id: 'incident-selected',
    type: 'circle',
    source: SOURCE_ID,
    filter: ['all', ['!', ['has', 'point_count']], ['==', ['get', 'id'], incidentId ?? '']],
    paint: {
      'circle-color': 'rgba(0, 0, 0, 0)',
      'circle-radius': 15,
      'circle-stroke-width': 3,
      'circle-stroke-color': '#0f172a',
    },
  };
}

export interface IncidentMapProps {
  positions: readonly IncidentPosition[];
  selectedId: string | null;
  onSelect: (incidentId: string) => void;
  ref?: Ref<MapRef>;
}

/**
 * The incidents on the self-hosted base map (ADR-0016), clustered by zoom: each cluster keeps the
 * highest priority of its incidents in its color, and a click on it zooms in until it splits. A
 * click on a marker selects its incident. If the base map cannot load, a notice says so and the
 * rest of the screen keeps working.
 */
export function IncidentMap({ positions, selectedId, onSelect, ref }: IncidentMapProps) {
  const data = useMemo(() => toFeatureCollection(positions), [positions]);
  const styleUrl = useMemo(() => mapStyleUrl(), []);
  const [cursor, setCursor] = useState<string>('');
  const [baseMapFailed, setBaseMapFailed] = useState(false);

  const handleClick = (event: MapLayerMouseEvent) => {
    const feature = event.features?.[0];
    if (!feature) {
      return;
    }
    if (feature.layer.id === POINT_LAYER) {
      onSelect((feature.properties as IncidentFeatureProperties).id);
      return;
    }
    if (feature.geometry.type !== 'Point') {
      return;
    }
    // GeoJSON order: [longitude, latitude].
    const center = feature.geometry.coordinates as [number, number];
    const clusterId = feature.properties.cluster_id as number;
    const source = event.target.getSource<GeoJSONSource>(SOURCE_ID);
    void source?.getClusterExpansionZoom(clusterId).then((zoom) => {
      event.target.easeTo({ center, zoom });
    });
  };

  return (
    <div className={styles.mapFrame}>
      {baseMapFailed && (
        <p className={styles.mapNotice} role="alert">
          The base map could not be loaded. The incidents are still listed below the map.
        </p>
      )}
      <Map
        ref={ref}
        initialViewState={MALAGA_VIEW}
        mapStyle={styleUrl}
        // The OpenStreetMap attribution stays visible, as its licence requires (ADR-0016).
        attributionControl={{ compact: false }}
        interactiveLayerIds={[CLUSTER_LAYER, POINT_LAYER]}
        cursor={cursor}
        onMouseEnter={() => setCursor('pointer')}
        onMouseLeave={() => setCursor('')}
        onClick={handleClick}
        onError={() => setBaseMapFailed(true)}
        onLoad={() => setBaseMapFailed(false)}
      >
        <NavigationControl showCompass={false} />
        <Source
          id={SOURCE_ID}
          type="geojson"
          data={data}
          cluster
          clusterMaxZoom={CLUSTER_MAX_ZOOM}
          clusterRadius={CLUSTER_RADIUS}
          clusterProperties={{ [MAX_PRIORITY]: ['max', ['get', 'priorityRank']] }}
        >
          <Layer {...clusterLayer} />
          <Layer {...clusterCountLayer} />
          <Layer {...pointLayer} />
          <Layer {...selectedLayer(selectedId)} />
        </Source>
      </Map>
    </div>
  );
}
