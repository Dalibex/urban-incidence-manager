import { INCIDENT_STATUSES } from '@urbanpulse/domain';
import { statusColor } from '@urbanpulse/ui';
import { describe, expect, it } from 'vitest';

import {
  CLUSTER_COLOR,
  HIGH_BORDER,
  PRIORITY_MARKS,
  priorityRank,
  toFeatureCollection,
} from './incident-features';

describe('toFeatureCollection', () => {
  it('orders each position as [longitude, latitude], as GeoJSON does (#76 T1)', () => {
    const collection = toFeatureCollection([
      { id: 'a', status: 'REPORTED', priority: 'HIGH', latitude: 36.7213, longitude: -4.4214 },
    ]);

    expect(collection.features[0]!.geometry.coordinates).toEqual([-4.4214, 36.7213]);
  });

  it('keeps the id, the status color and the priority as a rank a cluster can compare', () => {
    const collection = toFeatureCollection([
      { id: 'a', status: 'REOPENED', priority: null, latitude: 36.7, longitude: -4.4 },
    ]);

    expect(collection.features[0]!.properties).toEqual({
      id: 'a',
      status: 'REOPENED',
      priorityRank: 0,
      color: statusColor('REOPENED'),
    });
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
