/**
 * Phase 7E.3 — Unit and component tests for GlobeMap.
 *
 * Verifies:
 * - Real matched driver and connector GeoJSON source updates.
 * - Strict WGS-84 coordinate ordering [longitude, latitude].
 * - Camera guards (location-key guard to prevent re-render jumps, and near-identical coordinate guard).
 * - Layer and camera cleanup on reset and null telemetry.
 * - Strict independence between simulated fleet and real matched driver layers.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { GlobeMap } from './GlobeMap';
import type { PlaceSuggestion } from '../types/place';
import type { SimulatedDriver } from '../types/driver';

// ── Mock MapLibre GL JS ───────────────────────────────────────────────────────

interface MockSource {
  setData: ReturnType<typeof vi.fn>;
}

const mockSources = new Map<string, MockSource>();
const mockLayers = new Set<string>();

const mockMapInstance = {
  isStyleLoaded: vi.fn(() => true),
  setProjection: vi.fn(),
  setSky: vi.fn(),
  getSource: vi.fn((id: string) => mockSources.get(id)),
  addSource: vi.fn((id: string) => {
    const src: MockSource = { setData: vi.fn() };
    mockSources.set(id, src);
    return src;
  }),
  getLayer: vi.fn((id: string) => (mockLayers.has(id) ? {} : undefined)),
  addLayer: vi.fn((layer: { id: string }) => {
    mockLayers.add(layer.id);
  }),
  addControl: vi.fn(),
  fitBounds: vi.fn(),
  easeTo: vi.fn(),
  flyTo: vi.fn(),
  getZoom: vi.fn(() => 10),
  on: vi.fn((event: string, callback: () => void) => {
    if (event === 'style.load') {
      // Simulate style load immediately on registration
      callback();
    }
  }),
  remove: vi.fn(),
};

const mockMarkerInstance = {
  setLngLat: vi.fn().mockReturnThis(),
  addTo: vi.fn().mockReturnThis(),
  remove: vi.fn(),
};

vi.mock('maplibre-gl', () => {
  return {
    setWorkerUrl: vi.fn(),
    Map: vi.fn(function () {
      return mockMapInstance;
    }),
    AttributionControl: vi.fn(),
    NavigationControl: vi.fn(),
    Marker: vi.fn(function () {
      return mockMarkerInstance;
    }),
  };
});

// Mock CSS and worker URL import
vi.mock('maplibre-gl/dist/maplibre-gl.css', () => ({}));
vi.mock('maplibre-gl/dist/maplibre-gl-worker.mjs?url', () => ({ default: 'worker-url' }));

describe('GlobeMap component', () => {
  const dummyPlace: PlaceSuggestion = {
    id: 'place-1',
    name: 'Paris',
    subtitle: 'Île-de-France, France',
    type: 'city',
    lat: 48.8566,
    lon: 2.3522,
  };

  beforeEach(() => {
    vi.clearAllMocks();
    mockSources.clear();
    mockLayers.clear();
    mockMapInstance.isStyleLoaded.mockReturnValue(true);
    mockMapInstance.getZoom.mockReturnValue(10);
  });

  it('initializes MapLibre map and idempotently registers driver and connector layers', () => {
    const onLoad = vi.fn();
    const onError = vi.fn();

    render(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={onLoad}
        onError={onError}
        selectedPlace={null}
        drivers={[]}
        matchedDriverLocation={null}
      />
    );

    expect(onLoad).toHaveBeenCalled();
    // Sources registered: rendezvous-drivers, rendezvous-matched-driver, rendezvous-matched-connector
    expect(mockMapInstance.addSource).toHaveBeenCalledWith('rendezvous-matched-driver', expect.anything());
    expect(mockMapInstance.addSource).toHaveBeenCalledWith('rendezvous-matched-connector', expect.anything());
    // Layers registered
    expect(mockMapInstance.addLayer).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'rendezvous-matched-connector-line' }),
      'rendezvous-matched-driver-halo'
    );
  });

  it('updates GeoJSON sources with correct [lon, lat] coordinate ordering when matchedDriverLocation is provided', () => {
    const matchedLocation = { lat: 48.8600, lon: 2.3600 };

    render(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        drivers={[]}
        matchedDriverLocation={matchedLocation}
      />
    );

    const driverSource = mockSources.get('rendezvous-matched-driver');
    const connectorSource = mockSources.get('rendezvous-matched-connector');

    expect(driverSource).toBeDefined();
    expect(connectorSource).toBeDefined();

    // Verify driver point GeoJSON order is [longitude, latitude]
    expect(driverSource?.setData).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'FeatureCollection',
        features: [
          expect.objectContaining({
            geometry: {
              type: 'Point',
              coordinates: [2.3600, 48.8600], // [lon, lat]
            },
          }),
        ],
      })
    );

    // Verify connector line GeoJSON order is [[pickupLon, pickupLat], [driverLon, driverLat]]
    expect(connectorSource?.setData).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'FeatureCollection',
        features: [
          expect.objectContaining({
            geometry: {
              type: 'LineString',
              coordinates: [
                [dummyPlace.lon, dummyPlace.lat],
                [matchedLocation.lon, matchedLocation.lat],
              ],
            },
          }),
        ],
      })
    );
  });

  it('animates camera with fitBounds framing both pickup and driver locations', () => {
    const matchedLocation = { lat: 48.8600, lon: 2.3600 };

    render(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        drivers={[]}
        matchedDriverLocation={matchedLocation}
      />
    );

    expect(mockMapInstance.fitBounds).toHaveBeenCalledWith(
      [
        [Math.min(dummyPlace.lon, matchedLocation.lon), Math.min(dummyPlace.lat, matchedLocation.lat)],
        [Math.max(dummyPlace.lon, matchedLocation.lon), Math.max(dummyPlace.lat, matchedLocation.lat)],
      ],
      expect.objectContaining({
        padding: 80,
        maxZoom: 14,
      })
    );
  });

  it('guards against repeated camera animations on unrelated re-renders with identical coordinates', () => {
    const matchedLocation = { lat: 48.8600, lon: 2.3600 };

    const { rerender } = render(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        drivers={[]}
        matchedDriverLocation={matchedLocation}
      />
    );

    expect(mockMapInstance.fitBounds).toHaveBeenCalledTimes(1);

    // Re-render with new object reference but identical coordinates
    rerender(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        drivers={[]}
        matchedDriverLocation={{ lat: 48.8600, lon: 2.3600 }}
      />
    );

    // fitBounds should NOT be called again
    expect(mockMapInstance.fitBounds).toHaveBeenCalledTimes(1);
  });

  it('safely handles near-identical coordinates using easeTo instead of fitBounds to avoid extreme zoom', () => {
    // Coordinate difference < 0.0001 degrees
    const nearLocation = {
      lat: dummyPlace.lat + 0.00005,
      lon: dummyPlace.lon + 0.00005,
    };

    render(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        drivers={[]}
        matchedDriverLocation={nearLocation}
      />
    );

    expect(mockMapInstance.easeTo).toHaveBeenCalledWith(
      expect.objectContaining({
        center: [nearLocation.lon, nearLocation.lat],
        zoom: expect.any(Number),
      })
    );
    expect(mockMapInstance.fitBounds).not.toHaveBeenCalled();
  });

  it('clears driver and connector layers with EMPTY_FEATURE_COLLECTION when matchedDriverLocation is null', () => {
    const matchedLocation = { lat: 48.8600, lon: 2.3600 };

    const { rerender } = render(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        drivers={[]}
        matchedDriverLocation={matchedLocation}
      />
    );

    const driverSource = mockSources.get('rendezvous-matched-driver');
    const connectorSource = mockSources.get('rendezvous-matched-connector');

    // Reset matchedDriverLocation to null
    rerender(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        drivers={[]}
        matchedDriverLocation={null}
      />
    );

    expect(driverSource?.setData).toHaveBeenLastCalledWith({
      type: 'FeatureCollection',
      features: [],
    });
    expect(connectorSource?.setData).toHaveBeenLastCalledWith({
      type: 'FeatureCollection',
      features: [],
    });
  });

  it('clears layers when selectedPlace is cleared even if matchedDriverLocation is truthy', () => {
    const { rerender } = render(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        drivers={[]}
        matchedDriverLocation={{ lat: 48.8600, lon: 2.3600 }}
      />
    );

    const driverSource = mockSources.get('rendezvous-matched-driver');
    const connectorSource = mockSources.get('rendezvous-matched-connector');

    rerender(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={null}
        drivers={[]}
        matchedDriverLocation={{ lat: 48.8600, lon: 2.3600 }}
      />
    );

    expect(driverSource?.setData).toHaveBeenLastCalledWith({
      type: 'FeatureCollection',
      features: [],
    });
    expect(connectorSource?.setData).toHaveBeenLastCalledWith({
      type: 'FeatureCollection',
      features: [],
    });
  });

  it('maintains strict independence between simulated fleet and real driver layers', () => {
    const simulatedDrivers: SimulatedDriver[] = [
      { id: 'sim-1', lat: 48.85, lon: 2.35, status: 'available' },
      { id: 'sim-2', lat: 48.86, lon: 2.34, status: 'busy' },
    ];

    const { rerender } = render(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        drivers={simulatedDrivers}
        matchedDriverLocation={null}
      />
    );

    const simSource = mockSources.get('rendezvous-drivers');
    const matchedSource = mockSources.get('rendezvous-matched-driver');

    expect(simSource).toBeDefined();
    // Simulated source updated with 2 features
    expect(simSource?.setData).toHaveBeenCalledWith(
      expect.objectContaining({
        features: expect.arrayContaining([
          expect.objectContaining({ id: 'sim-1' }),
          expect.objectContaining({ id: 'sim-2' }),
        ]),
      })
    );

    const initialCalls = simSource?.setData.mock.calls.length ?? 0;

    // Now update matched real driver
    rerender(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        drivers={simulatedDrivers}
        matchedDriverLocation={{ lat: 48.87, lon: 2.36 }}
      />
    );

    // Matched source updated
    expect(matchedSource?.setData).toHaveBeenCalledWith(
      expect.objectContaining({
        features: [
          expect.objectContaining({
            geometry: { type: 'Point', coordinates: [2.36, 48.87] },
          }),
        ],
      })
    );

    // Simulated source was not modified again by the matched driver change
    expect(simSource?.setData).toHaveBeenCalledTimes(initialCalls);
  });
});
