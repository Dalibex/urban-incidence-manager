import { act, fireEvent, render, screen } from '@testing-library/react-native';

import { BaseMap, MAP_ATTRIBUTION, MAP_LOAD_FAILED, MAP_NOT_CONFIGURED } from './BaseMap';

const STYLE_URL = 'http://tiles.test/style.json';

describe('BaseMap', () => {
  afterEach(() => {
    process.env.EXPO_PUBLIC_MAP_STYLE_URL = STYLE_URL;
  });

  it('loads the configured style and shows the attribution of the tiles', async () => {
    await render(<BaseMap />);

    expect(screen.getByTestId('maplibre-map')).toHaveProp('mapStyle', STYLE_URL);
    expect(screen.getByText(MAP_ATTRIBUTION)).toBeOnTheScreen();
  });

  it('opens on Málaga, with the longitude first', async () => {
    await render(<BaseMap />);

    expect(screen.getByTestId('maplibre-camera')).toHaveProp('initialViewState', {
      center: [-4.42, 36.72],
      zoom: 12,
    });
  });

  it('explains that reporting still works when the tiles cannot be reached', async () => {
    await render(<BaseMap />);

    await act(() => screen.getByTestId('maplibre-map').props.onDidFailLoadingMap());

    expect(screen.queryByTestId('maplibre-map')).not.toBeOnTheScreen();
    expect(screen.getByText(MAP_LOAD_FAILED)).toBeOnTheScreen();
  });

  it('loads the map again when the citizen retries', async () => {
    await render(<BaseMap />);
    await act(() => screen.getByTestId('maplibre-map').props.onDidFailLoadingMap());

    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));

    expect(screen.getByTestId('maplibre-map')).toBeOnTheScreen();
    expect(screen.queryByText(MAP_LOAD_FAILED)).not.toBeOnTheScreen();
  });

  it('shows a notice instead of the map when the style is not configured', async () => {
    delete process.env.EXPO_PUBLIC_MAP_STYLE_URL;

    await render(<BaseMap />);

    expect(screen.queryByTestId('maplibre-map')).not.toBeOnTheScreen();
    expect(screen.getByText(MAP_NOT_CONFIGURED)).toBeOnTheScreen();
  });
});
