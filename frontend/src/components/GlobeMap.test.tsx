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
import type { DispatchTrace } from '../types/dispatch';

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

  it('updates road route GeoJSON source and frames camera when routeCoordinates are provided (Phase 11.1)', () => {
    const routeCoords: [number, number][] = [
      [2.3522, 48.8566],
      [2.3550, 48.8580],
      [2.2945, 48.8584],
    ];

    const destPlace: PlaceSuggestion = {
      id: 'eiffel',
      name: 'Eiffel Tower',
      subtitle: 'Paris',
      lat: 48.8584,
      lon: 2.2945,
      type: 'monument',
    };

    render(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        destinationPlace={destPlace}
        routeCoordinates={routeCoords}
      />
    );

    const routeSource = mockSources.get('rendezvous-road-route');
    expect(routeSource).toBeDefined();
    expect(routeSource?.setData).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'FeatureCollection',
        features: [
          expect.objectContaining({
            geometry: {
              type: 'LineString',
              coordinates: routeCoords,
            },
          }),
        ],
      })
    );

    // fitBounds was called to frame the entire route
    expect(mockMapInstance.fitBounds).toHaveBeenCalled();
  });

  // ── Phase 11.4F: Spatial Trace Overlay Tests ───────────────────────────────

  const sampleTrace: DispatchTrace = {
    requestId: 'REQ-MAP-1',
    algorithm: 'AuctionMatcher',
    pickupLatitude: 48.8566,
    pickupLongitude: 2.3522,
    startedAt: '2026-10-19T02:00:00Z',
    durationNanos: 2_500_000,
    stages: [
      {
        stageIndex: 0,
        stageType: 'QUAD_TREE_SEARCH',
        windowSize: 16,
        minLatitude: 48.8500,
        minLongitude: 2.3400,
        maxLatitude: 48.8650,
        maxLongitude: 2.3650,
        visitedNodeCount: 10,
        prunedNodeCount: 3,
        candidatesDiscovered: 2,
      },
      {
        stageIndex: 1,
        stageType: 'UNBOUNDED_STAGE',
        windowSize: 32,
        minLatitude: null,
        minLongitude: null,
        maxLatitude: null,
        maxLongitude: null,
        visitedNodeCount: 4,
        prunedNodeCount: 1,
        candidatesDiscovered: 0,
      },
    ],
    candidates: [
      {
        driverId: 'D-WIN',
        latitude: 48.8580,
        longitude: 2.3550,
        distanceKm: 0.35,
        etaCost: 1.1,
        densityCost: 0.2,
        opportunityCost: 0.1,
        totalCost: 1.4,
        rank: 0,
        outcome: 'MATCHED',
        rejectionReason: null,
      },
      {
        driverId: 'D-OUT',
        latitude: 48.8620,
        longitude: 2.3600,
        distanceKm: 1.1,
        etaCost: null,
        densityCost: null,
        opportunityCost: null,
        totalCost: null,
        rank: 1,
        outcome: 'OUTCOMPETED',
        rejectionReason: 'Higher total cost',
      },
    ],
    winnerDriverId: 'D-WIN',
    matched: true,
    totalFleetSize: 100,
    decisionSummary: 'Matched driver D-WIN',
  };

  it('registers spatial trace stages and candidates sources and layers on initialization (Phase 11.4F)', () => {
    render(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={null}
      />
    );

    // Verify trace sources registered
    expect(mockMapInstance.addSource).toHaveBeenCalledWith('rendezvous-trace-stages', expect.anything());
    expect(mockMapInstance.addSource).toHaveBeenCalledWith('rendezvous-trace-candidates', expect.anything());

    // Verify trace layers registered
    expect(mockMapInstance.addLayer).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'rendezvous-trace-stages-fill' }),
      expect.anything()
    );
    expect(mockMapInstance.addLayer).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'rendezvous-trace-stages-outline' }),
      expect.anything()
    );
    expect(mockMapInstance.addLayer).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'rendezvous-trace-candidates-halo' }),
      expect.anything()
    );
    expect(mockMapInstance.addLayer).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'rendezvous-trace-candidates-core' }),
      expect.anything()
    );
  });

  it('updates spatial stage and candidate sources with GeoJSON when showTraceOverlay is true (Phase 11.4F)', () => {
    mockMapInstance.fitBounds.mockClear();

    render(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        trace={sampleTrace}
        showTraceOverlay={true}
      />
    );

    const stagesSource = mockSources.get('rendezvous-trace-stages');
    const candidatesSource = mockSources.get('rendezvous-trace-candidates');

    expect(stagesSource).toBeDefined();
    expect(candidatesSource).toBeDefined();

    // Verify stages: only 1 feature emitted (stage 1 with null bbox was safely skipped)
    expect(stagesSource?.setData).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'FeatureCollection',
        features: [
          expect.objectContaining({
            id: 'stage-0',
            geometry: {
              type: 'Polygon',
              coordinates: [
                [
                  [2.3400, 48.8500],
                  [2.3650, 48.8500],
                  [2.3650, 48.8650],
                  [2.3400, 48.8650],
                  [2.3400, 48.8500], // closed ring
                ],
              ],
            },
            properties: expect.objectContaining({
              stageIndex: 0,
              stageType: 'QUAD_TREE_SEARCH',
            }),
          }),
        ],
      })
    );

    // Verify candidates: 2 features with Point [lon, lat], winner tag and outcome
    expect(candidatesSource?.setData).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'FeatureCollection',
        features: [
          expect.objectContaining({
            id: 'D-WIN',
            geometry: {
              type: 'Point',
              coordinates: [2.3550, 48.8580], // [lon, lat]
            },
            properties: expect.objectContaining({
              driverId: 'D-WIN',
              isWinner: true,
              outcome: 'MATCHED',
            }),
          }),
          expect.objectContaining({
            id: 'D-OUT',
            geometry: {
              type: 'Point',
              coordinates: [2.3600, 48.8620], // [lon, lat]
            },
            properties: expect.objectContaining({
              driverId: 'D-OUT',
              isWinner: false,
              outcome: 'OUTCOMPETED',
            }),
          }),
        ],
      })
    );

    // Bounding boxes and candidates do NOT trigger automatic camera framing
    expect(mockMapInstance.fitBounds).not.toHaveBeenCalled();
  });

  it('clears trace overlay sources when showTraceOverlay is false or trace is null (Phase 11.4F)', () => {
    const { rerender } = render(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        trace={sampleTrace}
        showTraceOverlay={false}
      />
    );

    const stagesSource = mockSources.get('rendezvous-trace-stages');
    const candidatesSource = mockSources.get('rendezvous-trace-candidates');

    expect(stagesSource?.setData).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'FeatureCollection',
        features: [],
      })
    );
    expect(candidatesSource?.setData).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'FeatureCollection',
        features: [],
      })
    );

    // Now expand: showTraceOverlay becomes true
    rerender(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        trace={sampleTrace}
        showTraceOverlay={true}
      />
    );

    expect(stagesSource?.setData).toHaveBeenLastCalledWith(
      expect.objectContaining({
        type: 'FeatureCollection',
        features: expect.arrayContaining([expect.objectContaining({ id: 'stage-0' })]),
      })
    );

    // Now collapse: showTraceOverlay becomes false -> cleared again
    rerender(
      <GlobeMap
        mapState={{ status: 'ready' }}
        onLoad={vi.fn()}
        onError={vi.fn()}
        selectedPlace={dummyPlace}
        trace={sampleTrace}
        showTraceOverlay={false}
      />
    );

    expect(stagesSource?.setData).toHaveBeenLastCalledWith(
      expect.objectContaining({
        type: 'FeatureCollection',
        features: [],
      })
    );
    expect(candidatesSource?.setData).toHaveBeenLastCalledWith(
      expect.objectContaining({
        type: 'FeatureCollection',
        features: [],
      })
    );
  });
});

