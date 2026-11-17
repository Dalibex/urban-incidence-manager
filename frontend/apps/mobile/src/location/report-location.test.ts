import {
  fromLngLat,
  isGpsFixOutsideMap,
  isInsideCoveredArea,
  locationToReport,
  toLngLat,
} from './report-location';

const GPS = { latitude: 36.7213, longitude: -4.4214, accuracy: 12.5 };
/** Far outside the area the tiles cover. */
const MADRID = { latitude: 40.4168, longitude: -3.7038, accuracy: 10 };

describe('marker conversion', () => {
  it('puts the longitude first for MapLibre', () => {
    expect(toLngLat({ latitude: 36.7213, longitude: -4.4214 })).toEqual([-4.4214, 36.7213]);
  });

  it('reads a MapLibre point back without swapping latitude and longitude', () => {
    expect(fromLngLat([-4.4214, 36.7213])).toEqual({ latitude: 36.7213, longitude: -4.4214 });
  });

  it('gives back the same position after a round trip', () => {
    const position = { latitude: 36.7213, longitude: -4.4214 };

    expect(fromLngLat(toLngLat(position))).toEqual(position);
  });
});

describe('locationToReport', () => {
  it('sends the GPS fix with its accuracy while the pin is on it', () => {
    const pin = { latitude: 36.72130001, longitude: -4.42140001, source: 'gpsFix' } as const;

    expect(locationToReport(GPS, pin)).toEqual(GPS);
  });

  it('sends the moved pin without any accuracy', () => {
    const pin = { latitude: 36.7301, longitude: -4.4102, source: 'citizen' } as const;

    expect(locationToReport(GPS, pin)).toEqual({ latitude: 36.7301, longitude: -4.4102 });
  });

  it('sends the pin the citizen placed without a GPS fix, without any accuracy', () => {
    const pin = { latitude: 36.7187, longitude: -4.4246, source: 'citizen' } as const;

    expect(locationToReport(null, pin)).toEqual({ latitude: 36.7187, longitude: -4.4246 });
  });

  it('has nothing to send while the pin is still on the start point nobody chose', () => {
    const pin = { latitude: 36.72, longitude: -4.42, source: 'startPoint' } as const;

    expect(locationToReport(null, pin)).toBeNull();
  });

  it('never sends a pin on a GPS fix that is gone, nor an accuracy for it', () => {
    const pin = { latitude: 36.7213, longitude: -4.4214, source: 'gpsFix' } as const;

    expect(locationToReport(null, pin)).toBeNull();
  });

  it('sends the GPS fix when the map is not on screen', () => {
    expect(locationToReport(GPS, null)).toEqual(GPS);
  });

  it('leaves out the accuracy when the device does not report it', () => {
    expect(locationToReport({ ...GPS, accuracy: null }, null)).toEqual({
      latitude: 36.7213,
      longitude: -4.4214,
    });
  });

  it('has nothing to send without the map and without a GPS fix', () => {
    expect(locationToReport(null, null)).toBeNull();
  });

  // #290: the map cannot leave the covered area, so it shows the pin on its border, not on the fix.
  it('has nothing to send while the pin stands for a GPS fix outside the covered area', () => {
    const pin = { latitude: 37.3, longitude: -3.75, source: 'gpsFix' } as const;

    expect(locationToReport(MADRID, pin)).toBeNull();
  });

  it('sends the pin the citizen moved after a GPS fix outside the covered area', () => {
    const pin = { latitude: 36.7187, longitude: -4.4246, source: 'citizen' } as const;

    expect(locationToReport(MADRID, pin)).toEqual({ latitude: 36.7187, longitude: -4.4246 });
  });

  it('sends a GPS fix exactly on the border of the covered area', () => {
    const border = { latitude: 37.3, longitude: -3.75, accuracy: 8 };
    const pin = { latitude: 37.3, longitude: -3.75, source: 'gpsFix' } as const;

    expect(locationToReport(border, pin)).toEqual(border);
  });
});

// #315 review: the screen's message and what locationToReport sends come from this one rule, so
// they cannot drift apart.
describe('isGpsFixOutsideMap', () => {
  const pinOnFix = { latitude: 37.3, longitude: -3.75, source: 'gpsFix' } as const;

  it('is true only while the pin stands for a GPS fix outside the covered area', () => {
    expect(isGpsFixOutsideMap(MADRID, pinOnFix)).toBe(true);
  });

  it('is false once the citizen moves the pin, without a fix, or without the map', () => {
    const moved = { latitude: 36.7187, longitude: -4.4246, source: 'citizen' } as const;

    expect(isGpsFixOutsideMap(MADRID, moved)).toBe(false);
    expect(isGpsFixOutsideMap(null, pinOnFix)).toBe(false);
    expect(isGpsFixOutsideMap(MADRID, null)).toBe(false);
  });

  it('is false for a fix inside the covered area or on its border', () => {
    expect(isGpsFixOutsideMap(GPS, { ...pinOnFix, ...GPS })).toBe(false);
    expect(isGpsFixOutsideMap({ latitude: 37.3, longitude: -3.75, accuracy: 8 }, pinOnFix)).toBe(
      false,
    );
  });

  it('agrees with locationToReport: nothing is sent exactly when the message shows', () => {
    const pins = [
      pinOnFix,
      { latitude: 36.7187, longitude: -4.4246, source: 'citizen' } as const,
      null,
    ];
    for (const gps of [GPS, MADRID]) {
      for (const pin of pins) {
        if (isGpsFixOutsideMap(gps, pin)) {
          expect(locationToReport(gps, pin)).toBeNull();
        } else {
          expect(locationToReport(gps, pin)).not.toBeNull();
        }
      }
    }
  });
});

describe('isInsideCoveredArea', () => {
  it('is true in Málaga and on the border of the tiles', () => {
    expect(isInsideCoveredArea(GPS)).toBe(true);
    expect(isInsideCoveredArea({ latitude: 36.28, longitude: -5.62 })).toBe(true);
    expect(isInsideCoveredArea({ latitude: 37.3, longitude: -3.75 })).toBe(true);
  });

  it('is false beyond any side of the tiles', () => {
    expect(isInsideCoveredArea(MADRID)).toBe(false);
    expect(isInsideCoveredArea({ latitude: 36.27, longitude: -4.42 })).toBe(false);
    expect(isInsideCoveredArea({ latitude: 36.72, longitude: -5.63 })).toBe(false);
  });
});
