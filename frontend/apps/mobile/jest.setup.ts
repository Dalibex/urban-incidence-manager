// The session module refuses to load without the API URL (apps/mobile/.env in development). Tests
// never reach it: they mock the API client.
process.env.EXPO_PUBLIC_API_BASE_URL = 'http://api.test';
process.env.EXPO_PUBLIC_MAP_STYLE_URL = 'http://tiles.test/style.json';
// Quick sign-in off, whatever the local .env says; a test turns it on by setting the variables.
process.env.EXPO_PUBLIC_QUICK_SIGN_IN = 'false';
delete process.env.EXPO_PUBLIC_DEMO_PASSWORD;

// MapLibre is a native module that Jest cannot load. The mock renders plain views and keeps the
// props, so tests can read the style and fire the load events.
jest.mock('@maplibre/maplibre-react-native', () => {
  const { createElement } = jest.requireActual<typeof import('react')>('react');
  const { View } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    Map: (props: Record<string, unknown>) =>
      createElement(View, { testID: 'maplibre-map', ...props }),
    Camera: (props: Record<string, unknown>) =>
      createElement(View, { testID: 'maplibre-camera', ...props }),
  };
});
