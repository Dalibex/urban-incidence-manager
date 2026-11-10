import { colors, statusColor } from '@urbanpulse/ui';
import { describe, expect, it } from 'vitest';

import { CLUSTER_PRIORITY_COLORS, priorityRank, toFeatureCollection } from './incident-features';

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

describe('CLUSTER_PRIORITY_COLORS', () => {
  it('goes up by rank, so the step expression of the clusters can read it, and ends in danger', () => {
    const ranks = CLUSTER_PRIORITY_COLORS.map(({ rank }) => rank);
    expect(ranks).toEqual([...ranks].sort((a, b) => a - b));
    expect(CLUSTER_PRIORITY_COLORS.at(-1)).toMatchObject({ label: 'High', color: colors.danger });
  });
});
