import { useEffect, useRef, useState } from 'react';
import * as maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import maplibreglWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import type { FeatureCollection } from 'geojson';
import type { MapReadyState } from '../hooks/useMapReady';
import type { PlaceSuggestion } from '../types/place';
import type { SimulatedDriver } from '../types/driver';
import { SimulatedFleetBadge } from './SimulatedFleetBadge';
import './GlobeMap.css';

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

interface GlobeMapProps {
  onLoad: () => void;
  onError: (message: string) => void;
  mapState: MapReadyState;
  /** When non-null, animates the globe to the selected place. */
  selectedPlace: PlaceSuggestion | null;
  /** Simulated drivers to render on the map. Empty array = no drivers shown. */
  drivers?: SimulatedDriver[];
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
  drivers = [],
}: GlobeMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markerRef = useRef<maplibregl.Marker | null>(null);
  const driversRef = useRef<SimulatedDriver[]>(drivers);
  driversRef.current = drivers;

  // Controls whether the loading overlay is still mounted (fades out after ready)
  const [overlayVisible, setOverlayVisible] = useState(true);

  // ── Map initialisation ──────────────────────────────────────────────────────
  useEffect(() => {
    if (!containerRef.current) return;
    if (mapRef.current) return; // already initialised (StrictMode double-invoke guard)

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

      onLoad();
    });

    map.on('error', (e: { error?: { message?: string } }) => {
      // e.error is a MapLibre ErrorEvent; message can be undefined for network errors
      const msg = e.error?.message ?? 'An unknown map error occurred.';
      onError(msg);
    });

    mapRef.current = map;

    return () => {
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
        source.setData(
          drivers.length > 0 ? driversToFeatureCollection(drivers) : EMPTY_FEATURE_COLLECTION,
        );
      }
    }
  }, [drivers]);

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
      <SimulatedFleetBadge drivers={drivers} placeName={selectedPlace?.name} />

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
