import { INCIDENT_STATUSES } from '@urbanpulse/domain';
import { statusColor } from '@urbanpulse/ui';
import { describe, expect, it } from 'vitest';

import { ICON_KEYS } from './category-icons';
import {
  CLUSTER_COLOR,
  HIGH_BORDER,
  PRIORITY_MARKS,
  iconImageId,
  markerIconId,
  priorityRank,
  toFeatureCollection,
} from './incident-features';

describe('toFeatureCollection', () => {
  it('orders each position as [longitude, latitude], as GeoJSON does (#76 T1)', () => {
    const collection = toFeatureCollection([
      {
        id: 'a',
        status: 'REPORTED',
        categoryId: null,
        priority: 'HIGH',
        latitude: 36.7213,
        longitude: -4.4214,
      },
    ]);

    expect(collection.features[0]!.geometry.coordinates).toEqual([-4.4214, 36.7213]);
  });

  it('keeps the id, the status color and the priority as a rank a cluster can compare', () => {
    const collection = toFeatureCollection([
      {
        id: 'a',
        status: 'REOPENED',
        categoryId: null,
        priority: null,
        latitude: 36.7,
        longitude: -4.4,
      },
    ]);

    expect(collection.features[0]!.properties).toEqual({
      id: 'a',
      status: 'REOPENED',
      priorityRank: 0,
      color: statusColor('REOPENED'),
      icon: 'category-GENERIC-light',
    });
  });

  // #267: the icon is the category; the glyph is white or dark to read on the status colour.
  it('names the image of the icon of each category, in the glyph colour of its status', () => {
    const categories = [
      {
        id: 'lighting',
        name: 'Street lighting',
        description: null,
        icon: 'STREET_LIGHTING' as const,
      },
    ];
    const at = { priority: null, latitude: 36.7, longitude: -4.4 };

    const collection = toFeatureCollection(
      [
        { id: 'a', status: 'VALIDATED', categoryId: 'lighting', ...at },
        { id: 'b', status: 'REPORTED', categoryId: 'lighting', ...at },
        { id: 'c', status: 'REPORTED', categoryId: 'inactive', ...at },
      ],
      categories,
    );

    expect(collection.features.map(({ properties }) => properties.icon)).toEqual([
      'category-STREET_LIGHTING-light',
      'category-STREET_LIGHTING-dark',
      'category-GENERIC-dark',
    ]);
  });
});

// The map adds one image per icon and glyph colour (IncidentMap); a marker naming any other image
// would draw without icon, and the map tests replace the map, so this checks the wiring.
describe('markerIconId', () => {
  it('names an image the map adds, for every icon on every status colour', () => {
    const added = new Set(
      ICON_KEYS.flatMap((key) =>
        (['light', 'dark'] as const).map((glyph) => iconImageId(key, glyph)),
      ),
    );

    for (const status of INCIDENT_STATUSES) {
      for (const key of ICON_KEYS) {
        expect(added).toContain(markerIconId(key, statusColor(status)));
      }
    }
    expect(added.size).toBe(ICON_KEYS.length * 2);
  });
});

describe('priorityRank', () => {
  it('ranks no priority below LOW and HIGH above all', () => {
    expect([null, 'LOW', 'MEDIUM', 'HIGH'].map((p) => priorityRank(p as never))).toEqual([
      0, 1, 2, 3,
    ]);
  });
});

describe('PRIORITY_MARKS', () => {
  it('goes up by rank, so the step expressions of the layers can read it', () => {
    const ranks = PRIORITY_MARKS.map(({ rank }) => rank);
    expect(ranks).toEqual([0, 1, 2, 3]);
  });

  it('draws a higher priority bigger, and names each level as the lists do', () => {
    const radii = PRIORITY_MARKS.map(({ radius }) => radius);
    expect(radii).toEqual([...new Set(radii)].sort((a, b) => a - b));
    expect(PRIORITY_MARKS.map(({ label }) => label)).toEqual([
      'No priority',
      'Low',
      'Medium',
      'High',
    ]);
  });
});

describe('colors of the map', () => {
  // #196: the color of a marker is its status; neither the clusters nor the border of HIGH may use a
  // status color, or a red cluster would read as a reopened incident.
  it('keeps every status color for the status only', () => {
    const statusColors = new Set(INCIDENT_STATUSES.map(statusColor));
    expect(statusColors.has(CLUSTER_COLOR)).toBe(false);
    expect(statusColors.has(HIGH_BORDER.color)).toBe(false);
  });
});
