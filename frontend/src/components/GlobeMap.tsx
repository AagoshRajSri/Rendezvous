import { useEffect, useRef, useState } from 'react';
import * as maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import maplibreglWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import type { FeatureCollection } from 'geojson';
import type { MapReadyState } from '../hooks/useMapReady';
import type { PlaceSuggestion } from '../types/place';
import type { SimulatedDriver } from '../types/driver';
import type { DispatchPhase, DispatchTrace } from '../types/dispatch';
import {
  stagesToFeatureCollection,
  candidatesToFeatureCollection,
  candidatesToRaysFeatureCollection,
  EMPTY_TRACE_FEATURE_COLLECTION,
} from '../utils/traceGeoJson';
import { SimulatedFleetBadge } from './SimulatedFleetBadge';
import './GlobeMap.css';

// ── Real matched-driver layer constants (Phase 7E.2) ────────────────────────────
// Amber/gold dots — distinct from the green simulated fleet
const MATCHED_DRIVER_SOURCE_ID = 'rendezvous-matched-driver';
const MATCHED_DRIVER_HALO_LAYER_ID = 'rendezvous-matched-driver-halo';
const MATCHED_DRIVER_CORE_LAYER_ID = 'rendezvous-matched-driver-core';
// Dashed connector line between pickup pin and real driver marker
const CONNECTOR_SOURCE_ID = 'rendezvous-matched-connector';
const CONNECTOR_LAYER_ID = 'rendezvous-matched-connector-line';

// Ensure MapLibre web worker is explicitly resolved by Vite
maplibregl.setWorkerUrl(maplibreglWorkerUrl);

type MapLibreMap = maplibregl.Map;

// ── Simulated driver layer constants ─────────────────────────────────────────
const DRIVER_SOURCE_ID = 'rendezvous-drivers';
const DRIVER_HALO_LAYER_ID = 'rendezvous-drivers-halo';
const DRIVER_CORE_LAYER_ID = 'rendezvous-drivers-core';

const EMPTY_FEATURE_COLLECTION: FeatureCollection = {
  type: 'FeatureCollection',
  features: [],
};

function driversToFeatureCollection(drivers: SimulatedDriver[]): FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: drivers.map((d) => ({
      type: 'Feature',
      id: d.id,
      geometry: {
        type: 'Point',
        coordinates: [d.lon, d.lat],
      },
      properties: {
        id: d.id,
        status: d.status,
      },
    })),
  };
}

/**
 * Idempotently registers the driver GeoJSON source and layers on the MapLibre map.
 * Safe to call on initial style load and after style reloads.
 */
function ensureDriverLayers(map: MapLibreMap) {
  if (!map.isStyleLoaded()) return;

  if (!map.getSource(DRIVER_SOURCE_ID)) {
    map.addSource(DRIVER_SOURCE_ID, {
      type: 'geojson',
      data: EMPTY_FEATURE_COLLECTION,
    });
  }

  // Outer halo layer for visual breathing room and status indication
  if (!map.getLayer(DRIVER_HALO_LAYER_ID)) {
    map.addLayer({
      id: DRIVER_HALO_LAYER_ID,
      type: 'circle',
      source: DRIVER_SOURCE_ID,
      paint: {
        'circle-radius': 9,
        'circle-color': [
          'match',
          ['get', 'status'],
          'available',
          '#3dd68c', // var(--color-success)
          '#8a9bbf', // var(--color-text-muted)
        ],
        'circle-opacity': [
          'match',
          ['get', 'status'],
          'available',
          0.25,
          0.12,
        ],
        'circle-pitch-alignment': 'map',
      },
    });
  }

  // Inner core vehicle dot with dark stroke for contrast against any terrain
  if (!map.getLayer(DRIVER_CORE_LAYER_ID)) {
    map.addLayer({
      id: DRIVER_CORE_LAYER_ID,
      type: 'circle',
      source: DRIVER_SOURCE_ID,
      paint: {
        'circle-radius': 5,
        'circle-color': [
          'match',
          ['get', 'status'],
          'available',
          '#3dd68c',
          '#8a9bbf',
        ],
        'circle-stroke-width': 2,
        'circle-stroke-color': '#080b12',
        'circle-opacity': [
          'match',
          ['get', 'status'],
          'available',
          1.0,
          0.6,
        ],
        'circle-pitch-alignment': 'map',
      },
    });
  }
}

/**
 * Idempotently registers the real matched-driver GeoJSON source and layers.
 * Uses amber/gold colors to visually distinguish from simulated drivers.
 * Safe to call on initial style load and after style reloads.
 */
function ensureMatchedDriverLayers(map: MapLibreMap) {
  if (!map.isStyleLoaded()) return;

  if (!map.getSource(MATCHED_DRIVER_SOURCE_ID)) {
    map.addSource(MATCHED_DRIVER_SOURCE_ID, {
      type: 'geojson',
      data: EMPTY_FEATURE_COLLECTION,
    });
  }

  if (!map.getLayer(MATCHED_DRIVER_HALO_LAYER_ID)) {
    map.addLayer({
      id: MATCHED_DRIVER_HALO_LAYER_ID,
      type: 'circle',
      source: MATCHED_DRIVER_SOURCE_ID,
      paint: {
        'circle-radius': 18,
        'circle-color': '#ffc542', // amber — distinct from simulated fleet
        'circle-opacity': 0.2,
        'circle-pitch-alignment': 'map',
      },
    });
  }

  if (!map.getLayer(MATCHED_DRIVER_CORE_LAYER_ID)) {
    map.addLayer({
      id: MATCHED_DRIVER_CORE_LAYER_ID,
      type: 'circle',
      source: MATCHED_DRIVER_SOURCE_ID,
      paint: {
        'circle-radius': 8,
        'circle-color': '#ffc542',
        'circle-stroke-width': 2,
        'circle-stroke-color': '#080b12',
        'circle-opacity': 1.0,
        'circle-pitch-alignment': 'map',
      },
    });
  }
}

/**
 * Idempotently registers the connector (dashed line) GeoJSON source and layer.
 * Draws a dashed amber line from the pickup pin to the real driver marker.
 * Inserted below the matched-driver dot layers so the dots render on top.
 * Safe to call on initial style load and after style reloads.
 */
function ensureConnectorLayer(map: MapLibreMap) {
  if (!map.isStyleLoaded()) return;

  if (!map.getSource(CONNECTOR_SOURCE_ID)) {
    map.addSource(CONNECTOR_SOURCE_ID, {
      type: 'geojson',
      data: EMPTY_FEATURE_COLLECTION,
    });
  }

  if (!map.getLayer(CONNECTOR_LAYER_ID)) {
    map.addLayer(
      {
        id: CONNECTOR_LAYER_ID,
        type: 'line',
        source: CONNECTOR_SOURCE_ID,
        paint: {
          'line-color': '#ffc542',
          'line-width': 1.5,
          'line-dasharray': [4, 4],
          'line-opacity': 0.6,
        },
        layout: {
          'line-cap': 'round',
          'line-join': 'round',
        },
      },
      MATCHED_DRIVER_HALO_LAYER_ID,
    );
  }
}

// ── Road route layer constants (Phase 11.1) ──────────────────────────────────
const ROUTE_SOURCE_ID = 'rendezvous-road-route';
const ROUTE_CASING_LAYER_ID = 'rendezvous-road-route-casing';
const ROUTE_CORE_LAYER_ID = 'rendezvous-road-route-core';

function ensureRouteLayers(map: MapLibreMap) {
  if (!map.isStyleLoaded()) return;

  if (!map.getSource(ROUTE_SOURCE_ID)) {
    map.addSource(ROUTE_SOURCE_ID, {
      type: 'geojson',
      data: EMPTY_FEATURE_COLLECTION,
    });
  }

  // Insert below driver halo layer so drivers sit on top of the road line
  const beforeLayer = map.getLayer(DRIVER_HALO_LAYER_ID) ? DRIVER_HALO_LAYER_ID : undefined;

  if (!map.getLayer(ROUTE_CASING_LAYER_ID)) {
    map.addLayer(
      {
        id: ROUTE_CASING_LAYER_ID,
        type: 'line',
        source: ROUTE_SOURCE_ID,
        paint: {
          'line-color': '#047857', // Deep Jade Casing
          'line-width': 7,
          'line-opacity': 0.75,
          'line-blur': 1,
        },
        layout: {
          'line-cap': 'round',
          'line-join': 'round',
        },
      },
      beforeLayer,
    );
  }

  if (!map.getLayer(ROUTE_CORE_LAYER_ID)) {
    map.addLayer(
      {
        id: ROUTE_CORE_LAYER_ID,
        type: 'line',
        source: ROUTE_SOURCE_ID,
        paint: {
          'line-color': '#10b981', // Luminescent Aurora Mint Core
          'line-width': 4,
          'line-opacity': 0.95,
        },
        layout: {
          'line-cap': 'round',
          'line-join': 'round',
        },
      },
      beforeLayer,
    );
  }
}

// ── Driver approach route layer constants (Phase 11.2) ────────────────────────
const DRIVER_APPROACH_SOURCE_ID = 'rendezvous-driver-approach-route';
const DRIVER_APPROACH_CASING_LAYER_ID = 'rendezvous-driver-approach-casing';
const DRIVER_APPROACH_CORE_LAYER_ID = 'rendezvous-driver-approach-core';

function ensureDriverApproachLayers(map: MapLibreMap) {
  if (!map.isStyleLoaded()) return;

  if (!map.getSource(DRIVER_APPROACH_SOURCE_ID)) {
    map.addSource(DRIVER_APPROACH_SOURCE_ID, {
      type: 'geojson',
      data: EMPTY_FEATURE_COLLECTION,
    });
  }

  const beforeLayer = map.getLayer(DRIVER_HALO_LAYER_ID) ? DRIVER_HALO_LAYER_ID : undefined;

  if (!map.getLayer(DRIVER_APPROACH_CASING_LAYER_ID)) {
    map.addLayer(
      {
        id: DRIVER_APPROACH_CASING_LAYER_ID,
        type: 'line',
        source: DRIVER_APPROACH_SOURCE_ID,
        paint: {
          'line-color': '#b45309',
          'line-width': 6.5,
          'line-opacity': 0.75,
          'line-blur': 1,
        },
        layout: {
          'line-cap': 'round',
          'line-join': 'round',
        },
      },
      beforeLayer,
    );
  }

  if (!map.getLayer(DRIVER_APPROACH_CORE_LAYER_ID)) {
    map.addLayer(
      {
        id: DRIVER_APPROACH_CORE_LAYER_ID,
        type: 'line',
        source: DRIVER_APPROACH_SOURCE_ID,
        paint: {
          'line-color': '#f59e0b',
          'line-width': 3.5,
          'line-opacity': 0.95,
        },
        layout: {
          'line-cap': 'round',
          'line-join': 'round',
        },
      },
      beforeLayer,
    );
  }
}

// ── Electric wave route layers (Phase 11.5 — enhanced) ───────────────────────
// Three layers stacked on the same GeoJSON source:
//   1. Corona glow   — wide blurred halo for neon bloom
//   2. Core wire     — bright narrow centre line
//   3. Spark dashes  — animated flowing electricity (rAF-driven dash offset)
export const PATH_PULSE_SOURCE_ID = 'rendezvous-path-pulse';
export const PATH_PULSE_LAYER_ID = 'rendezvous-path-pulse-layer'; // kept for compat
const PATH_ELECTRIC_CORONA_LAYER_ID = 'rendezvous-path-electric-corona';
const PATH_ELECTRIC_CORE_LAYER_ID   = 'rendezvous-path-electric-core';
const PATH_ELECTRIC_SPARK_LAYER_ID  = 'rendezvous-path-electric-spark';

function ensurePathPulseLayer(map: MapLibreMap) {
  if (!map.isStyleLoaded()) return;

  if (!map.getSource(PATH_PULSE_SOURCE_ID)) {
    map.addSource(PATH_PULSE_SOURCE_ID, {
      type: 'geojson',
      data: EMPTY_FEATURE_COLLECTION,
    });
  }

  // Insert below driver dots so route is always behind interactive markers
  const beforeLayer = map.getLayer(DRIVER_HALO_LAYER_ID) ? DRIVER_HALO_LAYER_ID : undefined;

  // ── 1. Corona bloom glow ──────────────────────────────────────────────────
  if (!map.getLayer(PATH_ELECTRIC_CORONA_LAYER_ID)) {
    map.addLayer(
      {
        id: PATH_ELECTRIC_CORONA_LAYER_ID,
        type: 'line',
        source: PATH_PULSE_SOURCE_ID,
        paint: {
          'line-color': '#059669', // Auroral Emerald
          'line-width': 16,
          'line-opacity': 0.32,
          'line-blur': 9,
        },
        layout: { 'line-cap': 'round', 'line-join': 'round' },
      },
      beforeLayer,
    );
  }

  // ── 2. Core wire ──────────────────────────────────────────────────────────
  if (!map.getLayer(PATH_ELECTRIC_CORE_LAYER_ID)) {
    map.addLayer(
      {
        id: PATH_ELECTRIC_CORE_LAYER_ID,
        type: 'line',
        source: PATH_PULSE_SOURCE_ID,
        paint: {
          'line-color': '#ecfdf5', // Seafoam White Plasma
          'line-width': 2.5,
          'line-opacity': 0.98,
          'line-blur': 0.3,
        },
        layout: { 'line-cap': 'round', 'line-join': 'round' },
      },
      beforeLayer,
    );
  }

  // ── 3. Travelling spark dashes ─────────────────────────────────────────────
  // Initial dasharray — offset animation driven externally via setPaintProperty.
  if (!map.getLayer(PATH_ELECTRIC_SPARK_LAYER_ID)) {
    map.addLayer(
      {
        id: PATH_ELECTRIC_SPARK_LAYER_ID,
        type: 'line',
        source: PATH_PULSE_SOURCE_ID,
        paint: {
          'line-color': '#ffffff',
          'line-width': 3.5,
          'line-opacity': 0.9,
          'line-dasharray': [2, 6],
        },
        layout: { 'line-cap': 'butt', 'line-join': 'round' },
      },
      beforeLayer,
    );
  }

  // Legacy alias layer — keep exported ID pointing at something real
  // (external code only reads PATH_PULSE_LAYER_ID; we alias to spark)
  // No-op if already registered above.
  if (!map.getLayer(PATH_PULSE_LAYER_ID)) {
    // Thin mid-tone layer between corona and core for extra depth
    map.addLayer(
      {
        id: PATH_PULSE_LAYER_ID,
        type: 'line',
        source: PATH_PULSE_SOURCE_ID,
        paint: {
          'line-color': '#a855f7', // Neon Purple depth
          'line-width': 8,
          'line-opacity': 0.45,
          'line-blur': 2.5,
        },
        layout: { 'line-cap': 'round', 'line-join': 'round' },
      },
      beforeLayer,
    );
  }
}

// ── Spatial trace overlay layer constants (Phase 11.4F & 11.5) ────────────────
export const TRACE_STAGES_SOURCE_ID = 'rendezvous-trace-stages';
export const TRACE_STAGES_FILL_LAYER_ID = 'rendezvous-trace-stages-fill';
export const TRACE_STAGES_OUTLINE_LAYER_ID = 'rendezvous-trace-stages-outline';

export const TRACE_CANDIDATES_SOURCE_ID = 'rendezvous-trace-candidates';
export const TRACE_CANDIDATES_HALO_LAYER_ID = 'rendezvous-trace-candidates-halo';
export const TRACE_CANDIDATES_CORE_LAYER_ID = 'rendezvous-trace-candidates-core';

export const TRACE_RAYS_SOURCE_ID = 'rendezvous-trace-rays';
export const TRACE_RAYS_LAYER_ID = 'rendezvous-trace-rays-core';

/**
 * Idempotently registers the spatial trace stage, candidate, and laser ray GeoJSON sources and layers.
 * Safe to call on initial style load and after style reloads.
 */
function ensureTraceLayers(map: MapLibreMap) {
  if (!map.isStyleLoaded()) return;

  // Stages GeoJSON source
  if (!map.getSource(TRACE_STAGES_SOURCE_ID)) {
    map.addSource(TRACE_STAGES_SOURCE_ID, {
      type: 'geojson',
      data: EMPTY_FEATURE_COLLECTION,
    });
  }

  // Insert stage bounding boxes below road routes so routes remain visible
  const beforeRouteOrDriverLayer =
    map.getLayer(ROUTE_CASING_LAYER_ID)
      ? ROUTE_CASING_LAYER_ID
      : (map.getLayer(DRIVER_HALO_LAYER_ID) ? DRIVER_HALO_LAYER_ID : undefined);

  if (!map.getLayer(TRACE_STAGES_FILL_LAYER_ID)) {
    map.addLayer(
      {
        id: TRACE_STAGES_FILL_LAYER_ID,
        type: 'fill',
        source: TRACE_STAGES_SOURCE_ID,
        paint: {
          'fill-color': '#065f46',
          'fill-opacity': 0.12,
        },
      },
      beforeRouteOrDriverLayer,
    );
  }

  if (!map.getLayer(TRACE_STAGES_OUTLINE_LAYER_ID)) {
    map.addLayer(
      {
        id: TRACE_STAGES_OUTLINE_LAYER_ID,
        type: 'line',
        source: TRACE_STAGES_SOURCE_ID,
        paint: {
          'line-color': '#10b981',
          'line-width': 1.5,
          'line-opacity': 0.85,
          'line-dasharray': [3, 2],
        },
        layout: {
          'line-cap': 'round',
          'line-join': 'round',
        },
      },
      beforeRouteOrDriverLayer,
    );
  }

  // Candidate laser rays source and layer (spider rays connecting pickup to candidates)
  if (!map.getSource(TRACE_RAYS_SOURCE_ID)) {
    map.addSource(TRACE_RAYS_SOURCE_ID, {
      type: 'geojson',
      data: EMPTY_FEATURE_COLLECTION,
    });
  }

  if (!map.getLayer(TRACE_RAYS_LAYER_ID)) {
    map.addLayer(
      {
        id: TRACE_RAYS_LAYER_ID,
        type: 'line',
        source: TRACE_RAYS_SOURCE_ID,
        paint: {
          'line-color': [
            'case',
            ['boolean', ['get', 'isWinner'], false],
            '#10b981', // glowing emerald for winner
            '#64748b', // subtle slate for outcompeted
          ],
          'line-width': [
            'case',
            ['boolean', ['get', 'isWinner'], false],
            2.5,
            1.2,
          ],
          'line-opacity': [
            'case',
            ['boolean', ['get', 'isWinner'], false],
            0.9,
            0.4,
          ],
          'line-dasharray': [
            'case',
            ['boolean', ['get', 'isWinner'], false],
            ['literal', [1, 0]],
            ['literal', [3, 3]],
          ],
        },
        layout: {
          'line-cap': 'round',
          'line-join': 'round',
        },
      },
      beforeRouteOrDriverLayer,
    );
  }

  // Candidates GeoJSON source
  if (!map.getSource(TRACE_CANDIDATES_SOURCE_ID)) {
    map.addSource(TRACE_CANDIDATES_SOURCE_ID, {
      type: 'geojson',
      data: EMPTY_FEATURE_COLLECTION,
    });
  }

  const beforeMatchedDriverLayer = map.getLayer(MATCHED_DRIVER_HALO_LAYER_ID)
    ? MATCHED_DRIVER_HALO_LAYER_ID
    : undefined;

  if (!map.getLayer(TRACE_CANDIDATES_HALO_LAYER_ID)) {
    map.addLayer(
      {
        id: TRACE_CANDIDATES_HALO_LAYER_ID,
        type: 'circle',
        source: TRACE_CANDIDATES_SOURCE_ID,
        paint: {
          'circle-radius': [
            'case',
            ['boolean', ['get', 'isWinner'], false],
            14,
            8,
          ],
          'circle-color': [
            'match',
            ['get', 'outcome'],
            'MATCHED', '#34d399',
            'OUTCOMPETED', '#94a3b8',
            'UNAVAILABLE', '#fbbf24',
            'CLAIM_LOST', '#f87171',
            '#64748b',
          ],
          'circle-opacity': [
            'case',
            ['boolean', ['get', 'isWinner'], false],
            0.35,
            0.2,
          ],
          'circle-pitch-alignment': 'map',
        },
      },
      beforeMatchedDriverLayer,
    );
  }

  if (!map.getLayer(TRACE_CANDIDATES_CORE_LAYER_ID)) {
    map.addLayer(
      {
        id: TRACE_CANDIDATES_CORE_LAYER_ID,
        type: 'circle',
        source: TRACE_CANDIDATES_SOURCE_ID,
        paint: {
          'circle-radius': [
            'case',
            ['boolean', ['get', 'isWinner'], false],
            6,
            3.5,
          ],
          'circle-color': [
            'match',
            ['get', 'outcome'],
            'MATCHED', '#34d399',
            'OUTCOMPETED', '#94a3b8',
            'UNAVAILABLE', '#fbbf24',
            'CLAIM_LOST', '#f87171',
            '#64748b',
          ],
          'circle-stroke-width': [
            'case',
            ['boolean', ['get', 'isWinner'], false],
            2,
            1.2,
          ],
          'circle-stroke-color': '#080b12',
          'circle-opacity': 1.0,
          'circle-pitch-alignment': 'map',
        },
      },
      beforeMatchedDriverLayer,
    );
  }
}

// ── OpenFreeMap "liberty" style ──────────────────────────────────────────────
// Free, no API key, OSM data. Attribution is rendered automatically by the
// MapLibre AttributionControl when the style JSON includes attribution metadata.
const STYLE_URL = 'https://tiles.openfreemap.org/styles/liberty';

// ── Globe projection constants (MapLibre GL JS ≥ 5.0.0) ─────────────────────
// Initial view: centred on 0°N 20°E (Atlantic/Africa) — no predetermined city.
const INITIAL_CENTER: [number, number] = [20, 5];
const INITIAL_ZOOM = 1.8;

// Globe → Mercator transition happens automatically above zoom ≈ 9.
// The sky/atmosphere blend is configured to be visible at low zoom and fade
// out once the user zooms into street level.
const SKY_CONFIG: maplibregl.SkySpecification = {
  'atmosphere-blend': [
    'interpolate',
    ['linear'],
    ['zoom'],
    0, 1,  // fully atmospheric at global view
    5, 1,
    7, 0,  // transparent by zoom 7 (street level)
  ] as maplibregl.ExpressionSpecification,
};

// ── Camera constants for flyTo ────────────────────────────────────────────────
const FLY_DURATION_MS = 2500;
const FIT_BOUNDS_PADDING = 80;
const FIT_BOUNDS_MAX_ZOOM = 14;

/**
 * Safe adaptive padding calculator for route camera framing.
 * Guarantees horizontal and vertical padding never exceed canvas dimensions,
 * eliminating MapLibre's "Map cannot fit within canvas with the given bounds, padding, and/or offset."
 */
function getAdaptiveRoutePadding(map: MapLibreMap) {
  const canvas = map.getCanvas?.();
  const width = canvas?.clientWidth || (typeof window !== 'undefined' ? window.innerWidth : 1024);
  const height = canvas?.clientHeight || (typeof window !== 'undefined' ? window.innerHeight : 768);
  const isMobile = width < 768;

  if (isMobile) {
    const topPad = Math.max(16, Math.min(120, Math.floor(height * 0.18)));
    const botPad = Math.max(32, Math.min(240, Math.floor(height * 0.36)));
    const sidePad = Math.max(12, Math.min(24, Math.floor(width * 0.06)));
    return {
      top: topPad,
      bottom: botPad,
      left: sidePad,
      right: sidePad,
    };
  }

  const rightPad = Math.min(380, Math.floor(width * 0.35));
  return {
    top: 90,
    bottom: 90,
    left: 90,
    right: rightPad,
  };
}

interface GlobeMapProps {
  onLoad: () => void;
  onError: (message: string) => void;
  mapState: MapReadyState;
  /** When non-null, animates the globe to the selected place. */
  selectedPlace: PlaceSuggestion | null;
  /** Destination place for route preview (Phase 11.1). */
  destinationPlace?: PlaceSuggestion | null;
  /** GeoJSON coordinates [[lon, lat], ...] for road route preview (Phase 11.1). */
  routeCoordinates?: [number, number][] | null;
  /** Simulated drivers to render on the map (Phase 7C). Empty array = no drivers shown. */
  drivers?: SimulatedDriver[];
  /**
   * Phase 7E.2 — Verified coordinates of the matched driver's last-known fresh fix.
   * Only rendered when explicitly non-null with validated lat/lon.
   * Never falls back to pickup coordinates or simulated-driver positions.
   */
  matchedDriverLocation?: { lat: number; lon: number } | null;
  /**
   * Phase 11.2 — Road coordinates [[lon, lat], ...] from assigned driver to pickup.
   */
  driverApproachRoute?: [number, number][] | null;
  /**
   * Phase 11.2 — Current animated vehicle position [lon, lat] along approach route.
   */
  animatedVehiclePosition?: [number, number] | null;
  /**
   * Phase 11.2 — Current vehicle bearing in degrees [0, 360).
   */
  animatedVehicleBearing?: number;
  /**
   * Phase 11.3 — Active lifecycle phase to trigger destination camera framing.
   */
  activePhase?: DispatchPhase;
  /**
   * Phase 11.4F — Read-only dispatch execution trace containing spatial exploration
   * stages and evaluated candidates.
   */
  trace?: DispatchTrace | null;
  /**
   * Phase 11.4F — Whether to render spatial trace overlays (bounding boxes and candidates).
   * Typically active when the Dispatch Trace accordion is expanded or Algorithm Mode is ON.
   */
  showTraceOverlay?: boolean;
  /**
   * Active matched driver ID to filter out from stationary simulated fleet.
   */
  matchedDriverId?: string | null;
  /**
   * Numeric token bumped to re-trigger the shortest-path wavefront discovery animation.
   */
  replayPulseToken?: number;
}

/**
 * Full-viewport MapLibre GL JS globe.
 *
 * Lifecycle:
 *   1. Mount: creates MapLibreMap, attaches AttributionControl.
 *   2. 'style.load': sets globe projection, configures sky, sets up driver layers, calls props.onLoad.
 *   3. 'error': calls props.onError with a human-readable message.
 *   4. Unmount: destroys the map instance to avoid leaks.
 *
 * When selectedPlace changes, animates the camera without recreating the map.
 * A pulsing pin marker marks the selected location.
 * When drivers change, updates the GeoJSON source and renders the simulated fleet badge.
 */
export function GlobeMap({
  onLoad,
  onError,
  mapState,
  selectedPlace,
  destinationPlace = null,
  routeCoordinates = null,
  drivers = [],
  matchedDriverLocation = null,
  matchedDriverId = null,
  driverApproachRoute = null,
  animatedVehiclePosition = null,
  animatedVehicleBearing = 0,
  activePhase = 'idle',
  trace = null,
  showTraceOverlay = false,
  replayPulseToken = 0,
}: GlobeMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markerRef = useRef<maplibregl.Marker | null>(null);
  const destinationMarkerRef = useRef<maplibregl.Marker | null>(null);
  const vehicleMarkerRef = useRef<maplibregl.Marker | null>(null);
  const vehicleElRef = useRef<HTMLElement | null>(null);
  const driversRef = useRef<SimulatedDriver[]>(drivers);
  driversRef.current = drivers;
  const traceRef = useRef<DispatchTrace | null | undefined>(trace);
  traceRef.current = trace;
  const showTraceOverlayRef = useRef<boolean>(showTraceOverlay);
  showTraceOverlayRef.current = showTraceOverlay;

  /**
   * Phase 7E.2 camera guard: tracks the last driver location that triggered a
   * fitBounds/easeTo so we never repeat the same camera animation on unrelated
   * React re-renders. Keyed as "lat,lon" string; null means no animation done.
   */
  const lastFittedDriverLocationRef = useRef<string | null>(null);
  const lastFittedApproachRouteRef = useRef<string | null>(null);
  const lastFittedTripRouteRef = useRef<string | null>(null);

  // Controls whether the loading overlay is still mounted (fades out after ready)
  const [overlayVisible, setOverlayVisible] = useState(true);

  // ── Map initialisation ──────────────────────────────────────────────────────
  useEffect(() => {
    if (!containerRef.current) return;
    if (mapRef.current) return; // already initialised (StrictMode double-invoke guard)

    // Filter benign vector tile style expression warnings from remote OpenFreeMap Liberty style
    const origWarn = console.warn;
    console.warn = (...args: unknown[]) => {
      const msg = args.map((a) => (typeof a === 'string' ? a : (a ? String(a) : ''))).join(' ');
      if (
        msg.includes('highway-shield') ||
        msg.includes('road_shield') ||
        msg.includes('Expected value to be of type number, but found null instead')
      ) {
        return;
      }
      origWarn.apply(console, args);
    };

    const map = new maplibregl.Map({
      container: containerRef.current,
      style: STYLE_URL,
      center: INITIAL_CENTER,
      zoom: INITIAL_ZOOM,
      minZoom: 0.5,
      maxZoom: 20,
      attributionControl: false, // We add a custom-positioned one below
    });

    // Add attribution in the bottom-right — required by OSM & OpenFreeMap licences.
    map.addControl(
      new maplibregl.AttributionControl({ compact: true }),
      'bottom-right',
    );

    // Add navigation controls (zoom +/− and compass)
    map.addControl(
      new maplibregl.NavigationControl({ showCompass: true, showZoom: true }),
      'bottom-left',
    );

    map.on('style.load', () => {
      // ── Enable globe projection (MapLibre GL JS ≥ 5.0.0) ──────────────────
      map.setProjection({ type: 'globe' });

      // ── Atmospheric sky layer ──────────────────────────────────────────────
      // setSky is available from MapLibre GL JS v4+.
      map.setSky(SKY_CONFIG);

      // ── Initialize simulated driver layers idempotently ───────────────────
      ensureDriverLayers(map);
      if (driversRef.current && driversRef.current.length > 0) {
        const source = map.getSource(DRIVER_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
        source?.setData(driversToFeatureCollection(driversRef.current));
      }

      // -- Initialize real matched-driver and connector layers idempotently (Phase 7E.2) --
      ensureMatchedDriverLayers(map);
      ensureConnectorLayer(map);
      ensureRouteLayers(map);
      ensureDriverApproachLayers(map);
      ensureTraceLayers(map);

      if (showTraceOverlayRef.current && traceRef.current) {
        const stagesSource = map.getSource(TRACE_STAGES_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
        const candidatesSource = map.getSource(TRACE_CANDIDATES_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
        stagesSource?.setData(stagesToFeatureCollection(traceRef.current.stages));
        candidatesSource?.setData(
          candidatesToFeatureCollection(traceRef.current.candidates, traceRef.current.winnerDriverId),
        );
      }

      onLoad();
    });

    map.on('error', (e: { error?: { message?: string } }) => {
      // e.error is a MapLibre ErrorEvent; message can be undefined for network errors
      const msg = e.error?.message ?? 'An unknown map error occurred.';
      onError(msg);
    });

    mapRef.current = map;

    return () => {
      console.warn = origWarn;
      vehicleMarkerRef.current?.remove();
      vehicleMarkerRef.current = null;
      map.remove();
      mapRef.current = null;
    };
    // onLoad and onError are stable refs from useCallback in useMapReady
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Globe fly-to animation when a place is selected ──────────────────────────
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    // Remove previous marker
    if (markerRef.current) {
      markerRef.current.remove();
      markerRef.current = null;
    }

    if (!selectedPlace) return;

    const { lon, lat, bbox } = selectedPlace;

    if (bbox) {
      // Validated bounding box: [west, south, east, north]
      map.fitBounds(
        [[bbox[0], bbox[1]], [bbox[2], bbox[3]]],
        {
          padding: FIT_BOUNDS_PADDING,
          maxZoom: FIT_BOUNDS_MAX_ZOOM,
          duration: FLY_DURATION_MS,
          essential: true,
        },
      );
    } else {
      // Fall back to flyTo when bbox is absent or invalid
      map.flyTo({
        center: [lon, lat],
        zoom: 12,
        duration: FLY_DURATION_MS,
        essential: true,
      });
    }

    // Drop a pulsing pin marker at the selected coordinates
    const markerEl = document.createElement('div');
    markerEl.className = 'globe-map__pin';
    markerEl.setAttribute('aria-label', `Selected: ${selectedPlace.name}`);

    const marker = new maplibregl.Marker({ element: markerEl, anchor: 'bottom' })
      .setLngLat([lon, lat])
      .addTo(map);

    markerRef.current = marker;
  }, [selectedPlace]);

  // ── Sync simulated drivers with GeoJSON source ──────────────────────────────
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    if (map.isStyleLoaded()) {
      ensureDriverLayers(map);
      const source = map.getSource(DRIVER_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
      if (source) {
        // Exclude the matched driver from the simulated fleet so they don't remain stationary in an off-road park
        const activeDrivers = matchedDriverId
          ? drivers.filter((d) => d.id !== matchedDriverId && !matchedDriverId.startsWith(d.id))
          : drivers;
        source.setData(
          activeDrivers.length > 0 ? driversToFeatureCollection(activeDrivers) : EMPTY_FEATURE_COLLECTION,
        );
      }
    }
  }, [drivers, matchedDriverId]);

  // -- Sync real matched-driver marker and connector (Phase 7E.2) --
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !map.isStyleLoaded()) return;

    ensureMatchedDriverLayers(map);
    ensureConnectorLayer(map);

    const driverSource = map.getSource(MATCHED_DRIVER_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
    const connectorSource = map.getSource(CONNECTOR_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
    if (!driverSource || !connectorSource) return;

    if (matchedDriverLocation && selectedPlace) {
      // Render the amber matched-driver dot.
      driverSource.setData({
        type: 'FeatureCollection',
        features: [{
          type: 'Feature',
          id: 'matched-driver',
          geometry: { type: 'Point', coordinates: [matchedDriverLocation.lon, matchedDriverLocation.lat] },
          properties: {},
        }],
      });

      // Render the dashed connector ONLY if no real road approach route is available yet.
      // Once the road route is active, hide the straight line to avoid cutting across terrain.
      if (!driverApproachRoute || driverApproachRoute.length < 2) {
        connectorSource.setData({
          type: 'FeatureCollection',
          features: [{
            type: 'Feature',
            geometry: {
              type: 'LineString',
              coordinates: [
                [selectedPlace.lon, selectedPlace.lat],
                [matchedDriverLocation.lon, matchedDriverLocation.lat],
              ],
            },
            properties: {},
          }],
        });
      } else {
        connectorSource.setData(EMPTY_FEATURE_COLLECTION);
      }

      // Camera: fit both points, but only animate once per unique driver fix
      // to prevent repeated camera jumps on unrelated React re-renders.
      const locationKey = `${matchedDriverLocation.lat},${matchedDriverLocation.lon}`;
      if (lastFittedDriverLocationRef.current !== locationKey) {
        lastFittedDriverLocationRef.current = locationKey;

        const west  = Math.min(selectedPlace.lon, matchedDriverLocation.lon);
        const east  = Math.max(selectedPlace.lon, matchedDriverLocation.lon);
        const south = Math.min(selectedPlace.lat, matchedDriverLocation.lat);
        const north = Math.max(selectedPlace.lat, matchedDriverLocation.lat);

        // Near-identical guard: fitBounds on a degenerate bounding box causes
        // extreme zoom; use easeTo when the two points are within ~11 m.
        const NEAR_IDENTICAL_THRESHOLD = 0.0001;
        const tooClose =
          (east - west) < NEAR_IDENTICAL_THRESHOLD &&
          (north - south) < NEAR_IDENTICAL_THRESHOLD;

        if (tooClose) {
          map.easeTo({
            center: [matchedDriverLocation.lon, matchedDriverLocation.lat],
            zoom: Math.max(map.getZoom(), 14),
            duration: FLY_DURATION_MS,
            essential: true,
          });
        } else {
          map.fitBounds(
            [[west, south], [east, north]],
            {
              padding: FIT_BOUNDS_PADDING,
              maxZoom: FIT_BOUNDS_MAX_ZOOM,
              duration: FLY_DURATION_MS,
              essential: true,
            },
          );
        }
      }
    } else {
      // No valid driver location or no pickup selected -- clear both layers.
      driverSource.setData(EMPTY_FEATURE_COLLECTION);
      connectorSource.setData(EMPTY_FEATURE_COLLECTION);
      lastFittedDriverLocationRef.current = null;
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [matchedDriverLocation, selectedPlace, driverApproachRoute]);

  // ── Destination pin marker synchronization (Phase 11.1) ─────────────────────
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    if (destinationMarkerRef.current) {
      destinationMarkerRef.current.remove();
      destinationMarkerRef.current = null;
    }

    if (!destinationPlace) return;

    const { lon, lat } = destinationPlace;
    const markerEl = document.createElement('div');
    markerEl.className = 'globe-map__pin globe-map__pin--destination';
    markerEl.setAttribute('aria-label', `Destination: ${destinationPlace.name}`);

    const marker = new maplibregl.Marker({ element: markerEl, anchor: 'bottom' })
      .setLngLat([lon, lat])
      .addTo(map);

    destinationMarkerRef.current = marker;
  }, [destinationPlace]);

  // ── Sync road route layer and camera framing (Phase 11.1) ───────────────────
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !map.isStyleLoaded()) return;

    ensureRouteLayers(map);
    const source = map.getSource(ROUTE_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
    if (!source) return;

    if (routeCoordinates && routeCoordinates.length >= 2) {
      source.setData({
        type: 'FeatureCollection',
        features: [
          {
            type: 'Feature',
            geometry: {
              type: 'LineString',
              coordinates: routeCoordinates,
            },
            properties: {},
          },
        ],
      });

      let minLon = Infinity;
      let maxLon = -Infinity;
      let minLat = Infinity;
      let maxLat = -Infinity;

      for (const [lon, lat] of routeCoordinates) {
        if (lon < minLon) minLon = lon;
        if (lon > maxLon) maxLon = lon;
        if (lat < minLat) minLat = lat;
        if (lat > maxLat) maxLat = lat;
      }

      if (
        isFinite(minLon) &&
        isFinite(maxLon) &&
        isFinite(minLat) &&
        isFinite(maxLat)
      ) {
        map.fitBounds(
          [
            [minLon, minLat],
            [maxLon, maxLat],
          ],
          {
            padding: getAdaptiveRoutePadding(map),
            maxZoom: 15,
            duration: FLY_DURATION_MS,
            essential: true,
          },
        );
      }
    } else {
      source.setData(EMPTY_FEATURE_COLLECTION);
    }
  }, [routeCoordinates]);

  // ── Sync destination road route camera framing on in_progress (Phase 11.3) ──
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !map.isStyleLoaded()) return;

    if (activePhase !== 'in_progress') {
      if (activePhase === 'idle') {
        lastFittedTripRouteRef.current = null;
      }
      return;
    }

    if (!routeCoordinates || routeCoordinates.length < 2) return;

    const firstCoord = routeCoordinates[0];
    const lastCoord = routeCoordinates[routeCoordinates.length - 1];
    const routeKey = `trip:${firstCoord[0]},${firstCoord[1]}:${lastCoord[0]},${lastCoord[1]}`;

    if (lastFittedTripRouteRef.current !== routeKey) {
      lastFittedTripRouteRef.current = routeKey;

      let minLon = Infinity;
      let maxLon = -Infinity;
      let minLat = Infinity;
      let maxLat = -Infinity;

      for (const [lon, lat] of routeCoordinates) {
        if (lon < minLon) minLon = lon;
        if (lon > maxLon) maxLon = lon;
        if (lat < minLat) minLat = lat;
        if (lat > maxLat) maxLat = lat;
      }

      if (
        isFinite(minLon) &&
        isFinite(maxLon) &&
        isFinite(minLat) &&
        isFinite(maxLat)
      ) {
        map.fitBounds(
          [
            [minLon, minLat],
            [maxLon, maxLat],
          ],
          {
            padding: getAdaptiveRoutePadding(map),
            maxZoom: 15,
            duration: FLY_DURATION_MS,
            essential: true,
          },
        );
      }
    }
  }, [activePhase, routeCoordinates]);

  // ── Sync driver approach road route and camera framing (Phase 11.2) ────────
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    if (map.isStyleLoaded()) {
      ensureDriverApproachLayers(map);
    }
    const source = map.getSource(DRIVER_APPROACH_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
    if (!source) return;

    const isApproachActive = activePhase === 'matched' || activePhase === 'en_route';

    if (driverApproachRoute && driverApproachRoute.length >= 2 && isApproachActive) {
      source.setData({
        type: 'FeatureCollection',
        features: [
          {
            type: 'Feature',
            geometry: {
              type: 'LineString',
              coordinates: driverApproachRoute,
            },
            properties: {},
          },
        ],
      });

      // Fit camera once per unique approach route
      const firstCoord = driverApproachRoute[0];
      const lastCoord = driverApproachRoute[driverApproachRoute.length - 1];
      const routeKey = `${firstCoord[0]},${firstCoord[1]}:${lastCoord[0]},${lastCoord[1]}`;

      if (lastFittedApproachRouteRef.current !== routeKey) {
        lastFittedApproachRouteRef.current = routeKey;

        let minLon = Infinity;
        let maxLon = -Infinity;
        let minLat = Infinity;
        let maxLat = -Infinity;

        for (const [lon, lat] of driverApproachRoute) {
          if (lon < minLon) minLon = lon;
          if (lon > maxLon) maxLon = lon;
          if (lat < minLat) minLat = lat;
          if (lat > maxLat) maxLat = lat;
        }

        if (
          isFinite(minLon) &&
          isFinite(maxLon) &&
          isFinite(minLat) &&
          isFinite(maxLat)
        ) {
          map.fitBounds(
            [
              [minLon, minLat],
              [maxLon, maxLat],
            ],
            {
              padding: getAdaptiveRoutePadding(map),
              maxZoom: 16,
              duration: FLY_DURATION_MS,
              essential: true,
            },
          );
        }
      }
    } else {
      source.setData(EMPTY_FEATURE_COLLECTION);
      lastFittedApproachRouteRef.current = null;
    }
  }, [driverApproachRoute, activePhase]);

  // ── Animated vehicle marker along driver approach route (Phase 11.2) ───────
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    if (!animatedVehiclePosition) {
      if (vehicleMarkerRef.current) {
        vehicleMarkerRef.current.remove();
        vehicleMarkerRef.current = null;
        vehicleElRef.current = null;
      }
      return;
    }

    const [lon, lat] = animatedVehiclePosition;

    if (!vehicleMarkerRef.current) {
      const el = document.createElement('div');
      el.className = 'globe-map__vehicle-marker';
      el.setAttribute('aria-label', 'Assigned dispatch vehicle approaching pickup');
      el.innerHTML = `
        <div class="globe-map__vehicle-icon" style="transform: rotate(${Math.round(animatedVehicleBearing)}deg)">
          <svg width="28" height="28" viewBox="0 0 28 28" fill="none" xmlns="http://www.w3.org/2000/svg">
            <circle cx="14" cy="14" r="12" fill="#080b12" fill-opacity="0.85" stroke="#f59e0b" stroke-width="2"/>
            <path d="M14 6L20 21L14 18L8 21L14 6Z" fill="#f59e0b"/>
          </svg>
        </div>
      `;
      const marker = new maplibregl.Marker({ element: el, anchor: 'center' })
        .setLngLat([lon, lat])
        .addTo(map);
      vehicleMarkerRef.current = marker;
      vehicleElRef.current = el;
    } else {
      vehicleMarkerRef.current.setLngLat([lon, lat]);
      const iconEl = vehicleElRef.current?.querySelector<HTMLElement>('.globe-map__vehicle-icon');
      if (iconEl) {
        iconEl.style.transform = `rotate(${Math.round(animatedVehicleBearing)}deg)`;
      }
    }
  }, [animatedVehiclePosition, animatedVehicleBearing]);

  // ── Electric-wave animated route (Phase 11.5 — enhanced) ────────────────────
  // Phase A: wavefront sweep — progressively reveals route geometry (1.4 s)
  // Phase B: continuous spark loop — travelling dashes simulate live electricity
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !map.isStyleLoaded()) return;

    ensurePathPulseLayer(map);
    const pulseSource = map.getSource(PATH_PULSE_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
    if (!pulseSource) return;

    const isApproachActive = (activePhase === 'matched' || activePhase === 'en_route') && Boolean(driverApproachRoute && driverApproachRoute.length >= 2);
    const activeCoords = isApproachActive ? driverApproachRoute : routeCoordinates;

    if (!activeCoords || activeCoords.length < 2) {
      pulseSource.setData(EMPTY_FEATURE_COLLECTION);
      return;
    }

    // Colour-theme the electric wire:
    // Trip route: Cyber Emerald & Seafoam Plasma
    // Approach route: Solar Amber & Warm Gold
    const coronaColour = isApproachActive ? '#f59e0b' : '#059669';
    const depthColour  = isApproachActive ? '#fbbf24' : '#10b981';
    const coreColour   = isApproachActive ? '#fffbeb' : '#ecfdf5';
    const sparkColour  = isApproachActive ? '#ffffff' : '#d1fae5';

    ([
      [PATH_ELECTRIC_CORONA_LAYER_ID, 'line-color', coronaColour],
      [PATH_PULSE_LAYER_ID,           'line-color', depthColour],
      [PATH_ELECTRIC_CORE_LAYER_ID,   'line-color', coreColour],
      [PATH_ELECTRIC_SPARK_LAYER_ID,  'line-color', sparkColour],
    ] as [string, string, string][]).forEach(([id, prop, val]) => {
      if (map.getLayer(id)) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (map as any).setPaintProperty(id, prop, val);
      }
    });

    let animId: number | null = null;
    const sweepStart = performance.now();
    const sweepDuration = 1500; // ms — smooth wavefront sweep

    // Fine-grained dash patterns that cycle to create the fluid "electricity flowing" illusion
    const SPARK_PATTERNS: [number, number][] = [
      [1, 7],
      [2, 6],
      [3, 5],
      [4, 4],
      [5, 3],
      [6, 2],
      [7, 1],
      [8, 0],
      [7, 1],
      [6, 2],
      [5, 3],
      [4, 4],
      [3, 5],
      [2, 6],
    ];
    let sparkFrame = 0;
    const SPARK_FRAME_MS = 32; // ~30 fps spark shifts
    let lastSparkTime = 0;

    const animateRoute = (now: number) => {
      const elapsed = now - sweepStart;
      const progress = Math.min(1.0, elapsed / sweepDuration);

      if (progress < 1.0) {
        // Phase A: Sub-segment smooth wavefront interpolation (no coordinate stutter)
        const eased = 1 - Math.pow(1 - progress, 3);
        const floatIndex = eased * (activeCoords.length - 1);
        const baseIndex = Math.floor(floatIndex);
        const subFraction = floatIndex - baseIndex;

        const partial: [number, number][] = activeCoords.slice(0, baseIndex + 1);
        if (baseIndex < activeCoords.length - 1 && subFraction > 0.001) {
          const p1 = activeCoords[baseIndex];
          const p2 = activeCoords[baseIndex + 1];
          partial.push([
            p1[0] + (p2[0] - p1[0]) * subFraction,
            p1[1] + (p2[1] - p1[1]) * subFraction,
          ]);
        }

        pulseSource.setData({
          type: 'FeatureCollection',
          features: [{
            type: 'Feature',
            geometry: { type: 'LineString', coordinates: partial },
            properties: {},
          }],
        });
      } else {
        // Phase B: Full route geometry locked with continuous current
        pulseSource.setData({
          type: 'FeatureCollection',
          features: [{
            type: 'Feature',
            geometry: { type: 'LineString', coordinates: activeCoords },
            properties: {},
          }],
        });
      }

      // Smooth optical breathing (continuous 60fps wave)
      const pulseSine = (Math.sin(now * 0.003) + 1) / 2; // [0, 1] smooth sine
      if (map.getLayer(PATH_ELECTRIC_CORONA_LAYER_ID)) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (map as any).setPaintProperty(
          PATH_ELECTRIC_CORONA_LAYER_ID,
          'line-opacity',
          0.22 + 0.16 * pulseSine,
        );
      }
      if (map.getLayer(PATH_PULSE_LAYER_ID)) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (map as any).setPaintProperty(
          PATH_PULSE_LAYER_ID,
          'line-opacity',
          0.35 + 0.15 * pulseSine,
        );
      }

      // Smooth spark dash translation
      if (now - lastSparkTime > SPARK_FRAME_MS) {
        lastSparkTime = now;
        sparkFrame = (sparkFrame + 1) % SPARK_PATTERNS.length;
        if (map.getLayer(PATH_ELECTRIC_SPARK_LAYER_ID)) {
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          (map as any).setPaintProperty(
            PATH_ELECTRIC_SPARK_LAYER_ID,
            'line-dasharray',
            SPARK_PATTERNS[sparkFrame],
          );
        }
      }

      animId = requestAnimationFrame(animateRoute);
    };

    animId = requestAnimationFrame(animateRoute);

    return () => {
      if (animId !== null) cancelAnimationFrame(animId);
    };
  }, [routeCoordinates, driverApproachRoute, activePhase, replayPulseToken]);

  // ── Sync spatial trace overlays & candidate rays (Phase 11.4F & 11.5) ──────
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !map.isStyleLoaded()) return;

    ensureTraceLayers(map);

    const stagesSource = map.getSource(TRACE_STAGES_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
    const candidatesSource = map.getSource(TRACE_CANDIDATES_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;
    const raysSource = map.getSource(TRACE_RAYS_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;

    if (!stagesSource || !candidatesSource) return;

    if (showTraceOverlay && trace) {
      stagesSource.setData(stagesToFeatureCollection(trace.stages));
      candidatesSource.setData(candidatesToFeatureCollection(trace.candidates, trace.winnerDriverId));
      if (raysSource && selectedPlace) {
        raysSource.setData(candidatesToRaysFeatureCollection(trace.candidates, selectedPlace, trace.winnerDriverId));
      }
    } else {
      stagesSource.setData(EMPTY_TRACE_FEATURE_COLLECTION);
      candidatesSource.setData(EMPTY_TRACE_FEATURE_COLLECTION);
      if (raysSource) {
        raysSource.setData(EMPTY_TRACE_FEATURE_COLLECTION);
      }
    }
  }, [trace, showTraceOverlay, selectedPlace]);

  // Once the map reports ready, keep the overlay mounted briefly so the
  // fade-out animation can complete, then unmount it entirely.
  useEffect(() => {
    if (mapState.status === 'ready') {
      const timer = setTimeout(() => setOverlayVisible(false), 700);
      return () => clearTimeout(timer);
    }
  }, [mapState.status]);

  const showOverlay = overlayVisible && mapState.status !== 'ready';
  const fadingOut = overlayVisible && mapState.status === 'ready';

  return (
    <div className="globe-map">
      {/* ── Map canvas ─────────────────────────────────────────────────────── */}
      <div ref={containerRef} className="globe-map__container" id="globe-map-container" />

      {/* ── Simulated fleet badge & legend (Phase 7C) ──────────────────────── */}
      <SimulatedFleetBadge drivers={destinationPlace ? [] : drivers} placeName={selectedPlace?.name} />

      {/* ── Loading overlay ────────────────────────────────────────────────── */}
      {(showOverlay || fadingOut) && mapState.status !== 'error' && (
        <div
          className={`globe-map__overlay${fadingOut ? ' globe-map__overlay--fading' : ''}`}
          role="status"
          aria-label="Loading globe map"
        >
          <div className="globe-map__spinner" aria-hidden="true" />
          <p className="globe-map__loading-label">Initialising globe…</p>
        </div>
      )}

      {/* ── Error overlay ──────────────────────────────────────────────────── */}
      {mapState.status === 'error' && (
        <div className="globe-map__overlay" role="alert">
          <div className="globe-map__error">
            <div className="globe-map__error-icon" aria-hidden="true">⚠</div>

            <h2 className="globe-map__error-title">Globe failed to load</h2>

            <p className="globe-map__error-body">
              The map could not retrieve its tile data. This is usually a
              network issue or a temporary outage of the tile server.
            </p>

            {mapState.status === 'error' && mapState.message && (
              <code className="globe-map__error-detail">{mapState.message}</code>
            )}

            <div className="globe-map__error-actions">
              <button
                id="globe-retry-btn"
                className="globe-map__btn globe-map__btn--primary"
                onClick={() => window.location.reload()}
                type="button"
              >
                Retry
              </button>
              <a
                id="globe-status-link"
                className="globe-map__btn globe-map__btn--secondary"
                href="https://openfreemap.org"
                target="_blank"
                rel="noopener noreferrer"
              >
                Tile server status
              </a>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
