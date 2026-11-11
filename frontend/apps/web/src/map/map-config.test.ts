import { afterEach, describe, expect, it, vi } from 'vitest';

import { mapStyleUrl } from './map-config';

const PAGE = 'https://urbanpulse.test/map?status=REPORTED';

describe('mapStyleUrl', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('loads the style from the web own origin by default, never from an external host (#76 T3)', () => {
    vi.stubEnv('VITE_MAP_STYLE_URL', '');

    expect(mapStyleUrl(PAGE)).toBe('https://urbanpulse.test/tiles/style.json');
  });

  it('takes VITE_MAP_STYLE_URL, resolving a relative one against the page', () => {
    vi.stubEnv('VITE_MAP_STYLE_URL', '/base-map/style.json');
    expect(mapStyleUrl(PAGE)).toBe('https://urbanpulse.test/base-map/style.json');

    vi.stubEnv('VITE_MAP_STYLE_URL', 'http://192.168.1.20:5173/tiles/style.json');
    expect(mapStyleUrl(PAGE)).toBe('http://192.168.1.20:5173/tiles/style.json');
  });
});
