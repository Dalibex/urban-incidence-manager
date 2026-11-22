import 'maplibre-gl/dist/maplibre-gl.css';

import type { components } from '@urbanpulse/api-client';
import { colors } from '@urbanpulse/ui';
import {
  addProtocol,
  setWorkerUrl,
  type ExpressionSpecification,
  type GeoJSONSource,
  type Map as MapLibreMap,
} from 'maplibre-gl';
import mapWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import { Protocol } from 'pmtiles';
import { useMemo, useState, type Ref } from 'react';
import Map, {
  FullscreenControl,
  Layer,
  NavigationControl,
  Source,
  type LayerProps,
  type MapLayerMouseEvent,
  type MapRef,
} from 'react-map-gl/maplibre';

import { ICON_KEYS, iconDataUrl } from './category-icons';
import {
  CLUSTER_COLOR,
  HIGH_BORDER,
  iconImageId,
  MARKER_GLYPHS,
  PRIORITY_MARKS,
  PRIORITY_RANK,
  toFeatureCollection,
  type IncidentFeatureProperties,
  type PriorityMark,
} from './incident-features';
import styles from './Map.module.css';
import { CLUSTER_MAX_ZOOM, MALAGA_VIEW, MAP_SCREEN_ID, mapStyleUrl } from './map-config';

type IncidentPosition = components['schemas']['IncidentPosition'];
type Category = components['schemas']['CategoryResponse'];

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

// One meaning per visual channel (#196): the color of a marker is its status, as in the badges of
// the lists, the priority is its size, a dark border for `HIGH` and, on a cluster, a label, and the
// icon inside it is its category (#267). A cluster has no icon: its incidents mix categories.

/**
 * A `step` expression over a priority rank: the value of the highest rank not above it, read from
 * {@link PRIORITY_MARKS} so the map and its legend never disagree.
 */
function byPriority(
  rank: ExpressionSpecification,
  valueOf: (mark: PriorityMark) => string | number,
): ExpressionSpecification {
  const [none, ...levels] = PRIORITY_MARKS;
  return [
    'step',
    rank,
    valueOf(none!),
    ...levels.flatMap((mark) => [mark.rank, valueOf(mark)]),
  ] as ExpressionSpecification;
}

/** Cluster radius by the number of incidents, in three steps so sizes stay comparable. */
const CLUSTER_SIZES = { small: 16, medium: 22, large: 28, mediumFrom: 10, largeFrom: 50 } as const;
const CLUSTER_LABEL_SIZE = 12;

const clusterLayer: LayerProps = {
  id: CLUSTER_LAYER,
  type: 'circle',
  source: SOURCE_ID,
  filter: ['has', 'point_count'],
  paint: {
    'circle-color': CLUSTER_COLOR,
    'circle-radius': [
      'step',
      ['get', 'point_count'],
      CLUSTER_SIZES.small,
      CLUSTER_SIZES.mediumFrom,
      CLUSTER_SIZES.medium,
      CLUSTER_SIZES.largeFrom,
      CLUSTER_SIZES.large,
    ],
    'circle-stroke-width': 2,
    'circle-stroke-color': '#ffffff',
  },
};

/** A font of the self-hosted glyphs (tiles/Dockerfile), never one from an external host. */
const LABEL_FONT = ['Noto Sans Bold'];

const clusterCountLayer: LayerProps = {
  id: 'incident-cluster-count',
  type: 'symbol',
  source: SOURCE_ID,
  filter: ['has', 'point_count'],
  layout: {
    'text-field': ['get', 'point_count_abbreviated'],
    'text-font': LABEL_FONT,
    'text-size': 13,
    'text-allow-overlap': true,
  },
  paint: { 'text-color': '#ffffff' },
};

/** Just below a cluster circle of each size, in ems of the label. */
function labelOffset(radius: number): ['literal', [number, number]] {
  return ['literal', [0, radius / CLUSTER_LABEL_SIZE + 0.25]];
}

/** The highest priority of a cluster in words, under it; a cluster without priority has none. */
const clusterPriorityLayer: LayerProps = {
  id: 'incident-cluster-priority',
  type: 'symbol',
  source: SOURCE_ID,
  filter: ['all', ['has', 'point_count'], ['>', ['get', MAX_PRIORITY], 0]],
  layout: {
    'text-field': byPriority(['get', MAX_PRIORITY], ({ rank, label }) => (rank > 0 ? label : '')),
    'text-font': LABEL_FONT,
    'text-size': CLUSTER_LABEL_SIZE,
    'text-anchor': 'top',
    'text-offset': [
      'step',
      ['get', 'point_count'],
      labelOffset(CLUSTER_SIZES.small),
      CLUSTER_SIZES.mediumFrom,
      labelOffset(CLUSTER_SIZES.medium),
      CLUSTER_SIZES.largeFrom,
      labelOffset(CLUSTER_SIZES.large),
    ],
    'text-allow-overlap': true,
  },
  paint: { 'text-color': CLUSTER_COLOR, 'text-halo-color': '#ffffff', 'text-halo-width': 2 },
};

const isHigh: ExpressionSpecification = ['==', ['get', 'priorityRank'], PRIORITY_RANK.HIGH];

const pointLayer: LayerProps = {
  id: POINT_LAYER,
  type: 'circle',
  source: SOURCE_ID,
  filter: ['!', ['has', 'point_count']],
  paint: {
    'circle-color': ['get', 'color'],
    'circle-radius': byPriority(['get', 'priorityRank'], ({ radius }) => radius),
    'circle-stroke-width': ['case', isHigh, HIGH_BORDER.width, 2],
    'circle-stroke-color': ['case', isHigh, HIGH_BORDER.color, '#ffffff'],
  },
};

/** Icons are drawn at twice their size on screen, so they stay sharp on high-density screens. */
const ICON_PIXEL_RATIO = 2;
/** The size on screen of an icon image, in pixels, at `icon-size` 1. */
const ICON_IMAGE_SIZE = 24;
/** How wide the icon is against the radius of its marker: it fits inside the circle. */
const ICON_TO_RADIUS = 1.2;

const iconLayer: LayerProps = {
  id: 'incident-icons',
  type: 'symbol',
  source: SOURCE_ID,
  filter: ['!', ['has', 'point_count']],
  layout: {
    'icon-image': ['get', 'icon'],
    'icon-size': byPriority(
      ['get', 'priorityRank'],
      ({ radius }) => (radius * ICON_TO_RADIUS) / ICON_IMAGE_SIZE,
    ),
    // Every marker has its icon, like its circle, however close the markers are.
    'icon-allow-overlap': true,
    'icon-ignore-placement': true,
  },
};

/**
 * Adds an image of every icon in each glyph colour. MapLibre draws images, not markup, so each SVG
 * is loaded as one first.
 */
async function addIconImages(map: MapLibreMap): Promise<void> {
  const glyphs = Object.entries(MARKER_GLYPHS) as [keyof typeof MARKER_GLYPHS, string][];
  await Promise.all(
    ICON_KEYS.flatMap((key) =>
      glyphs.map(async ([glyph, color]) => {
        const image = new Image();
        image.src = iconDataUrl(key, color, ICON_IMAGE_SIZE * ICON_PIXEL_RATIO);
        await image.decode();
        const id = iconImageId(key, glyph);
        if (!map.hasImage(id)) {
          map.addImage(id, image, { pixelRatio: ICON_PIXEL_RATIO });
        }
      }),
    ),
  );
}

/** A ring around the selected incident, with a gap so it never reads as the border of `HIGH`. */
function selectedLayer(incidentId: string | null): LayerProps {
  return {
    id: 'incident-selected',
    type: 'circle',
    source: SOURCE_ID,
    filter: ['all', ['!', ['has', 'point_count']], ['==', ['get', 'id'], incidentId ?? '']],
    paint: {
      'circle-color': 'rgba(0, 0, 0, 0)',
      // Outside the largest marker and its `HIGH` border, with a gap.
      'circle-radius': 20,
      'circle-stroke-width': 2,
      'circle-stroke-color': colors.text,
    },
  };
}

export interface IncidentMapProps {
  positions: readonly IncidentPosition[];
  /** The categories, for the icon of each marker; without them every marker has the generic one. */
  categories?: readonly Category[];
  selectedId: string | null;
  onSelect: (incidentId: string) => void;
  ref?: Ref<MapRef>;
}

/**
 * The incidents on the self-hosted base map (ADR-0016), clustered by zoom: each cluster shows how
 * many incidents it holds and, under it, the highest priority among them, and a click on it zooms
 * in until it splits. A
 * click on a marker selects its incident. If the base map cannot load, a notice says so and the
 * rest of the screen keeps working.
 */
export function IncidentMap({
  positions,
  categories,
  selectedId,
  onSelect,
  ref,
}: IncidentMapProps) {
  const data = useMemo(() => toFeatureCollection(positions, categories), [positions, categories]);
  const styleUrl = useMemo(() => mapStyleUrl(), []);
  const [cursor, setCursor] = useState<string>('');
  const [baseMapFailed, setBaseMapFailed] = useState(false);
  // The icon layer waits for its images; if they cannot load, the markers keep their colour and
  // size, and the category stays in the panel and the list.
  const [iconsReady, setIconsReady] = useState(false);

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
          The base map could not be loaded. The incidents are still in the list of the incidents on
          the map.
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
        onLoad={(event) => {
          setBaseMapFailed(false);
          addIconImages(event.target).then(
            () => setIconsReady(true),
            () => setIconsReady(false),
          );
        }}
      >
        <NavigationControl showCompass={false} />
        {/* The map screen, with its filters and side column, on the whole screen, on demand
            (#177); Escape brings the page back. */}
        <FullscreenControl containerId={MAP_SCREEN_ID} />
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
          <Layer {...clusterPriorityLayer} />
          <Layer {...pointLayer} />
          {iconsReady && <Layer {...iconLayer} />}
          <Layer {...selectedLayer(selectedId)} />
        </Source>
      </Map>
    </div>
  );
}
