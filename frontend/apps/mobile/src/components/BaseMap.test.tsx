import { act, fireEvent, render, screen } from '@testing-library/react-native';

import type { GpsFix } from '../location/report-location';
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

function renderMap(gpsFix: GpsFix | null = null) {
  return render(<BaseMap gpsFix={gpsFix} onPinChange={onPinChange} unavailableHint={HINT} />);
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
    await rerender(<BaseMap gpsFix={null} onPinChange={onPinChange} unavailableHint={HINT} />);
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

  it('centres the map and the pin on a new GPS fix', async () => {
    const { rerender } = await renderMap();
    await act(() => map().props.onDidFinishLoadingMap());
    await regionChanged([-4.4102, 36.7301], true);

    await rerender(<BaseMap gpsFix={FIX} onPinChange={onPinChange} unavailableHint={HINT} />);

    expect(cameraMethods.easeTo).toHaveBeenCalledWith(
      expect.objectContaining({ center: [-4.4214, 36.7213] }),
    );
    expect(onPinChange).toHaveBeenLastCalledWith({
      latitude: 36.7213,
      longitude: -4.4214,
      source: 'gpsFix',
    });
  });

  it('centres the map again on a new fix at the same place', async () => {
    const { rerender } = await renderMap(FIX);
    await act(() => map().props.onDidFinishLoadingMap());
    await regionChanged([-4.4102, 36.7301], true);

    await rerender(
      <BaseMap gpsFix={{ ...FIX }} onPinChange={onPinChange} unavailableHint={HINT} />,
    );

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
});
