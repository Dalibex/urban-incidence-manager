import { render, screen, waitFor } from '@testing-library/react';
import { useEffect, useRef, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { IncidentMap } from './IncidentMap';
import { markerIconImages } from './incident-features';

// MapLibre draws with WebGL, which jsdom does not have. These stand-ins keep what the icons depend
// on: the map calls `onLoad` with itself, and each layer shows up by its id.
const map = { hasImage: vi.fn<(id: string) => boolean>(), addImage: vi.fn() };

vi.mock('maplibre-gl', () => ({ addProtocol: vi.fn(), setWorkerUrl: vi.fn() }));
vi.mock('pmtiles', () => ({ Protocol: class {} }));
vi.mock('react-map-gl/maplibre', () => ({
  default: function MapStandIn({
    children,
    onLoad,
  }: {
    children: ReactNode;
    onLoad: (event: { target: typeof map }) => void;
  }) {
    // MapLibre fires `load` once, whatever the component renders afterwards.
    const loaded = useRef(false);
    useEffect(() => {
      if (!loaded.current) {
        loaded.current = true;
        onLoad({ target: map });
      }
    }, [onLoad]);
    return <div>{children}</div>;
  },
  Source: ({ children }: { children: ReactNode }) => <>{children}</>,
  Layer: ({ id }: { id: string }) => <p>layer {id}</p>,
  NavigationControl: () => null,
  FullscreenControl: () => null,
}));

const decode = vi.fn<() => Promise<void>>();

beforeEach(() => {
  map.hasImage.mockReturnValue(false);
  // jsdom loads no images, so decoding one is what each test decides.
  Object.defineProperty(HTMLImageElement.prototype, 'decode', {
    configurable: true,
    value: decode,
  });
});

afterEach(() => {
  vi.clearAllMocks();
  delete (HTMLImageElement.prototype as Partial<HTMLImageElement>).decode;
});

function renderMap() {
  render(<IncidentMap positions={[]} selectedId={null} onSelect={() => {}} />);
}

// #267: the icon layer waits for its images; without them the markers keep colour and size.
describe('the category icons of the map', () => {
  it('adds an image of every icon in each glyph colour, then draws the icon layer', async () => {
    decode.mockResolvedValue();

    renderMap();

    expect(await screen.findByText('layer incident-icons')).toBeDefined();
    expect(map.addImage.mock.calls.map(([id]) => id)).toEqual(
      markerIconImages().map(({ id }) => id),
    );
  });

  it('does not add an image the map already has', async () => {
    decode.mockResolvedValue();
    map.hasImage.mockReturnValue(true);

    renderMap();

    expect(await screen.findByText('layer incident-icons')).toBeDefined();
    expect(map.addImage).not.toHaveBeenCalled();
  });

  it('leaves the icons out, with a warning, when an image cannot be loaded', async () => {
    decode.mockRejectedValueOnce(new Error('EncodingError')).mockResolvedValue();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    renderMap();

    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(screen.queryByText('layer incident-icons')).toBeNull();
    expect(screen.getByText('layer incident-points')).toBeDefined();
  });
});
