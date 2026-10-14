import { useEffect, useRef, useState } from 'react';
import * as maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { MapReadyState } from '../hooks/useMapReady';
import './GlobeMap.css';

type MapLibreMap = maplibregl.Map;

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

interface GlobeMapProps {
  onLoad: () => void;
  onError: (message: string) => void;
  mapState: MapReadyState;
}

/**
 * Full-viewport MapLibre GL JS globe.
 *
 * Lifecycle:
 *   1. Mount: creates MapLibreMap, attaches AttributionControl.
 *   2. 'style.load': sets globe projection, configures sky, calls props.onLoad.
 *   3. 'error': calls props.onError with a human-readable message.
 *   4. Unmount: destroys the map instance to avoid leaks.
 *
 * No search, no driver markers, no backend calls — all deferred to 7B/7C/7D.
 */
export function GlobeMap({ onLoad, onError, mapState }: GlobeMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);

  // Controls whether the loading overlay is still mounted (fades out after ready)
  const [overlayVisible, setOverlayVisible] = useState(true);

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
      // The cast is required because @types/maplibre-gl may not yet include
      // all v5/v6 sky specification keys.
      map.setSky(SKY_CONFIG);

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
              <br /><br />
              <strong>The database transaction may already have committed</strong>{' '}
              if this error appears after a trip action — query the trip status
              directly rather than retrying.
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
