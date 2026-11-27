import type { ApiResult, Place } from '@urbanpulse/api-client';
import { act, fireEvent, render, screen } from '@testing-library/react-native';

import type { GpsFix } from '../location/report-location';
import { ADDRESS_LABEL, SEARCH } from './AddressSearch';
import {
  BaseMap,
  MAP_ATTRIBUTION,
  MAP_LOAD_FAILED,
  MAP_NOT_CONFIGURED,
  MOVE_THE_MAP,
  type BaseMapProps,
} from './BaseMap';

const STYLE_URL = 'http://tiles.test/style.json';
const HINT = 'The incident will use your GPS position.';
const FIX: GpsFix = { latitude: 36.7213, longitude: -4.4214, accuracy: 12.5 };

const { cameraMethods } = jest.requireMock<{
  cameraMethods: { easeTo: jest.Mock; jumpTo: jest.Mock };
}>('@maplibre/maplibre-react-native');

const onPinChange = jest.fn<void, Parameters<BaseMapProps['onPinChange']>>();

/** `gpsFixRequest` is the tap of "Use my position" the fix answers; the last tap defaults to it. */
function mapWith(
  gpsFix: GpsFix | null,
  gpsFixRequest: number | null = null,
  locationRequest = gpsFixRequest ?? 0,
) {
  return (
    <BaseMap
      gpsFix={gpsFix}
      gpsFixRequest={gpsFixRequest}
      locationRequest={locationRequest}
      onPinChange={onPinChange}
      unavailableHint={HINT}
    />
  );
}

function renderMap(gpsFix: GpsFix | null = null) {
  return render(mapWith(gpsFix));
}

function map() {
  return screen.getByTestId('maplibre-map');
}

/** The event MapLibre sends when the map stops moving. */
function regionChanged(center: [number, number], userInteraction: boolean) {
  return act(() => map().props.onRegionDidChange({ nativeEvent: { center, userInteraction } }));
}

beforeEach(() => {
  onPinChange.mockReset();
  cameraMethods.easeTo.mockReset();
  cameraMethods.jumpTo.mockReset();
});

afterEach(() => {
  process.env.EXPO_PUBLIC_MAP_STYLE_URL = STYLE_URL;
});

describe('BaseMap', () => {
  it('loads the configured style and shows the attribution of the tiles', async () => {
    await renderMap();

    expect(map()).toHaveProp('mapStyle', STYLE_URL);
    expect(screen.getByText(MAP_ATTRIBUTION)).toBeOnTheScreen();
  });

  it('shows a pin in its centre and explains how to move it', async () => {
    await renderMap();

    expect(screen.getByTestId('map-pin')).toBeOnTheScreen();
    expect(screen.getByText(MOVE_THE_MAP)).toBeOnTheScreen();
  });

  it('opens on Málaga, with the longitude first, when there is no GPS fix', async () => {
    await renderMap();

    expect(screen.getByTestId('maplibre-camera')).toHaveProp('initialViewState', {
      center: [-4.42, 36.72],
      zoom: 12,
    });
  });

  it('opens on the GPS fix when there is one', async () => {
    await renderMap(FIX);

    expect(screen.getByTestId('maplibre-camera')).toHaveProp('initialViewState', {
      center: [-4.4214, 36.7213],
      zoom: 16,
    });
  });

  it('cannot leave the area the tiles cover', async () => {
    await renderMap();

    expect(screen.getByTestId('maplibre-camera')).toHaveProp(
      'maxBounds',
      [-5.62, 36.28, -3.75, 37.3],
    );
  });

  it('puts the pin on Málaga once loaded without a GPS fix', async () => {
    await renderMap();

    await act(() => map().props.onDidFinishLoadingMap());

    expect(onPinChange).toHaveBeenLastCalledWith({
      latitude: 36.72,
      longitude: -4.42,
      source: 'startPoint',
    });
  });

  it('puts the pin on the GPS fix once loaded', async () => {
    await renderMap(FIX);

    await act(() => map().props.onDidFinishLoadingMap());

    expect(onPinChange).toHaveBeenLastCalledWith({
      latitude: 36.7213,
      longitude: -4.4214,
      source: 'gpsFix',
    });
  });

  it('moves the pin off the GPS fix when the citizen moves the map', async () => {
    await renderMap(FIX);
    await act(() => map().props.onDidFinishLoadingMap());

    await regionChanged([-4.4102, 36.7301], true);

    expect(onPinChange).toHaveBeenLastCalledWith({
      latitude: 36.7301,
      longitude: -4.4102,
      source: 'citizen',
    });
  });

  it('gives the pin to the citizen once they move the map from the start point', async () => {
    await renderMap();
    await act(() => map().props.onDidFinishLoadingMap());

    await regionChanged([-4.4102, 36.7301], true);

    expect(onPinChange).toHaveBeenLastCalledWith({
      latitude: 36.7301,
      longitude: -4.4102,
      source: 'citizen',
    });
  });

  it('keeps the pin on the start point when the map moves on its own', async () => {
    await renderMap();
    await act(() => map().props.onDidFinishLoadingMap());

    await regionChanged([-4.4201, 36.7202], false);

    expect(onPinChange).toHaveBeenLastCalledWith(expect.objectContaining({ source: 'startPoint' }));
  });

  it('does not go back to a fix that is gone when the map loads again', async () => {
    const { rerender } = await renderMap(FIX);
    await rerender(mapWith(null));
    await act(() => map().props.onDidFailLoadingMap());
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    cameraMethods.jumpTo.mockReset();

    await act(() => map().props.onDidFinishLoadingMap());

    expect(cameraMethods.jumpTo).not.toHaveBeenCalled();
    expect(onPinChange).toHaveBeenLastCalledWith(expect.objectContaining({ source: 'startPoint' }));
  });

  it('keeps a drag on the map from scrolling the form around it', async () => {
    await renderMap();

    // A grant that returns `true` is how React Native blocks the native ScrollView on Android.
    expect(map().props.onStartShouldSetResponder()).toBe(true);
    expect(map().props.onResponderGrant()).toBe(true);
    expect(map().props.onResponderTerminationRequest()).toBe(false);
  });

  it('keeps the pin on the fix when the map moves on its own', async () => {
    await renderMap(FIX);
    await act(() => map().props.onDidFinishLoadingMap());

    await regionChanged([-4.42140001, 36.72130001], false);

    expect(onPinChange).toHaveBeenLastCalledWith(expect.objectContaining({ source: 'gpsFix' }));
  });

  it('centres the map and the pin on a GPS fix that arrives after it loaded', async () => {
    const { rerender } = await renderMap();
    await act(() => map().props.onDidFinishLoadingMap());

    await rerender(mapWith(FIX));

    expect(cameraMethods.easeTo).toHaveBeenCalledWith(
      expect.objectContaining({ center: [-4.4214, 36.7213] }),
    );
    expect(onPinChange).toHaveBeenLastCalledWith({
      latitude: 36.7213,
      longitude: -4.4214,
      source: 'gpsFix',
    });
  });

  it('keeps the pin where the citizen moved it when a fix they did not ask for arrives', async () => {
    const { rerender } = await renderMap();
    await act(() => map().props.onDidFinishLoadingMap());
    await regionChanged([-4.4102, 36.7301], true);

    await rerender(mapWith(FIX));

    expect(cameraMethods.easeTo).not.toHaveBeenCalled();
    expect(onPinChange).toHaveBeenLastCalledWith({
      latitude: 36.7301,
      longitude: -4.4102,
      source: 'citizen',
    });
  });

  it('puts the pin back on the fix the citizen asks for with "Use my position"', async () => {
    const { rerender } = await renderMap();
    await act(() => map().props.onDidFinishLoadingMap());
    await regionChanged([-4.4102, 36.7301], true);

    await rerender(mapWith(FIX, 1));

    expect(cameraMethods.easeTo).toHaveBeenCalledWith(
      expect.objectContaining({ center: [-4.4214, 36.7213] }),
    );
    expect(onPinChange).toHaveBeenLastCalledWith({
      latitude: 36.7213,
      longitude: -4.4214,
      source: 'gpsFix',
    });
  });

  it('centres the map again on a fix asked for at the same place', async () => {
    const { rerender } = await renderMap(FIX);
    await act(() => map().props.onDidFinishLoadingMap());
    await regionChanged([-4.4102, 36.7301], true);

    await rerender(mapWith({ ...FIX }, 1));

    expect(cameraMethods.easeTo).toHaveBeenCalledTimes(1);
    expect(onPinChange).toHaveBeenLastCalledWith(expect.objectContaining({ source: 'gpsFix' }));
  });

  it('takes the pin away and explains what is left when the tiles cannot be reached', async () => {
    await renderMap();
    await act(() => map().props.onDidFinishLoadingMap());

    await act(() => map().props.onDidFailLoadingMap());

    expect(screen.queryByTestId('maplibre-map')).not.toBeOnTheScreen();
    expect(screen.getByText(`${MAP_LOAD_FAILED} ${HINT}`)).toBeOnTheScreen();
    expect(onPinChange).toHaveBeenLastCalledWith(null);
  });

  it('loads the map again when the citizen retries', async () => {
    await renderMap();
    await act(() => map().props.onDidFailLoadingMap());

    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));

    expect(map()).toBeOnTheScreen();
    expect(screen.queryByText(`${MAP_LOAD_FAILED} ${HINT}`)).not.toBeOnTheScreen();
  });

  it('shows a notice instead of the map when the style is not configured', async () => {
    delete process.env.EXPO_PUBLIC_MAP_STYLE_URL;

    await renderMap();

    expect(screen.queryByTestId('maplibre-map')).not.toBeOnTheScreen();
    expect(screen.getByText(`${MAP_NOT_CONFIGURED} ${HINT}`)).toBeOnTheScreen();
    expect(onPinChange).not.toHaveBeenCalled();
  });
  describe('with the address search (#179)', () => {
    const ALAMEDA: Place = {
      displayName: 'Alameda Principal, Centro, Málaga, 29001, España',
      latitude: 36.7179,
      longitude: -4.4237,
    };
    const searchPlaces = jest.fn<Promise<ApiResult<Place[]>>, [string]>();

    function mapWithSearch(
      gpsFix: GpsFix | null,
      gpsFixRequest: number | null = null,
      locationRequest = gpsFixRequest ?? 0,
    ) {
      return (
        <BaseMap
          gpsFix={gpsFix}
          gpsFixRequest={gpsFixRequest}
          locationRequest={locationRequest}
          onPinChange={onPinChange}
          unavailableHint={HINT}
          searchPlaces={searchPlaces}
        />
      );
    }

    function renderWithSearch(gpsFix: GpsFix | null = null) {
      return render(mapWithSearch(gpsFix));
    }

    async function choose(place: Place) {
      await act(() => fireEvent.changeText(screen.getByLabelText(ADDRESS_LABEL), 'Alameda'));
      await act(() => fireEvent.press(screen.getByRole('button', { name: SEARCH })));
      await act(() => fireEvent.press(screen.getByRole('button', { name: place.displayName })));
    }

    beforeEach(() => {
      searchPlaces.mockReset();
      searchPlaces.mockResolvedValue({ ok: true, data: [ALAMEDA] });
    });

    it('shows the search only once the map is on screen', async () => {
      await renderWithSearch();
      expect(screen.queryByLabelText(ADDRESS_LABEL)).not.toBeOnTheScreen();

      await act(() => map().props.onDidFinishLoadingMap());

      expect(screen.getByLabelText(ADDRESS_LABEL)).toBeOnTheScreen();
    });

    it('moves the map and the pin to the place the citizen chooses, as their position', async () => {
      await renderWithSearch(FIX);
      await act(() => map().props.onDidFinishLoadingMap());
      cameraMethods.easeTo.mockReset();

      await choose(ALAMEDA);

      expect(cameraMethods.easeTo).toHaveBeenCalledWith(
        expect.objectContaining({ center: [-4.4237, 36.7179], zoom: 16 }),
      );
      expect(onPinChange).toHaveBeenLastCalledWith({
        latitude: 36.7179,
        longitude: -4.4237,
        source: 'citizen',
      });

      // The camera then stops on its own; the position stays the citizen's.
      await regionChanged([-4.4237, 36.7179], false);
      expect(onPinChange).toHaveBeenLastCalledWith(expect.objectContaining({ source: 'citizen' }));
    });

    it('keeps the chosen place when the first GPS fix arrives later (#434)', async () => {
      const { rerender } = await renderWithSearch();
      await act(() => map().props.onDidFinishLoadingMap());
      await choose(ALAMEDA);
      cameraMethods.easeTo.mockReset();

      await rerender(mapWithSearch(FIX));

      expect(cameraMethods.easeTo).not.toHaveBeenCalled();
      expect(onPinChange).toHaveBeenLastCalledWith({
        latitude: 36.7179,
        longitude: -4.4237,
        source: 'citizen',
      });
    });

    it('leaves the chosen place for the fix of "Use my position"', async () => {
      const { rerender } = await renderWithSearch(FIX);
      await act(() => map().props.onDidFinishLoadingMap());
      await choose(ALAMEDA);
      cameraMethods.easeTo.mockReset();

      await rerender(mapWithSearch({ ...FIX }, 1));

      expect(cameraMethods.easeTo).toHaveBeenCalledWith(
        expect.objectContaining({ center: [-4.4214, 36.7213] }),
      );
      expect(onPinChange).toHaveBeenLastCalledWith(expect.objectContaining({ source: 'gpsFix' }));
    });

    it('keeps a place chosen while the fix of "Use my position" was on its way', async () => {
      const { rerender } = await renderWithSearch(FIX);
      await act(() => map().props.onDidFinishLoadingMap());
      // The citizen taps "Use my position" and, while it reads, chooses a place.
      await rerender(mapWithSearch(FIX, null, 1));
      await choose(ALAMEDA);
      cameraMethods.easeTo.mockReset();

      await rerender(mapWithSearch({ ...FIX }, 1));

      expect(cameraMethods.easeTo).not.toHaveBeenCalled();
      expect(onPinChange).toHaveBeenLastCalledWith({
        latitude: 36.7179,
        longitude: -4.4237,
        source: 'citizen',
      });
    });

    it('keeps a pan made after choosing a place when a fix arrives', async () => {
      const { rerender } = await renderWithSearch();
      await act(() => map().props.onDidFinishLoadingMap());
      await choose(ALAMEDA);
      await regionChanged([-4.4102, 36.7301], true);

      await rerender(mapWithSearch(FIX));

      expect(onPinChange).toHaveBeenLastCalledWith({
        latitude: 36.7301,
        longitude: -4.4102,
        source: 'citizen',
      });
    });

    it('hides the search with the map when the tiles cannot be reached', async () => {
      await renderWithSearch();
      await act(() => map().props.onDidFinishLoadingMap());

      await act(() => map().props.onDidFailLoadingMap());

      expect(screen.queryByLabelText(ADDRESS_LABEL)).not.toBeOnTheScreen();
    });
  });
});
