import { fromLngLat, locationToReport, toLngLat } from './report-location';

const GPS = { latitude: 36.7213, longitude: -4.4214, accuracy: 12.5 };

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
});
