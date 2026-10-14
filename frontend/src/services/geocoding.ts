/**
 * Phase 7B — Photon geocoding service implementation.
 *
 * Photon (photon.komoot.io) is an open-source geocoding engine built on
 * OpenStreetMap data, provided by Komoot as a best-effort public service.
 * No API key required.
 *
 * Attribution: © OpenStreetMap contributors (rendered via the MapLibre
 * AttributionControl that is already present from Phase 7A).
 *
 * Terms: Use responsibly; heavy production traffic should be self-hosted.
 * This client applies 300ms debouncing at the call site, not here.
 */

import type {
  BBox,
  GeocodingResult,
  GeocodingService,
  PlaceSuggestion,
} from '../types/place';

const BASE_URL = 'https://photon.komoot.io/api/';
const RESULT_LIMIT = 6;
const LANG = 'en';

// ── Photon GeoJSON response shapes ──────────────────────────────────────────

interface PhotonProperties {
  name?: string;
  city?: string;
  state?: string;
  country?: string;
  countrycode?: string;
  type?: string;
  osm_key?: string;
  osm_value?: string;
  osm_id?: number;
  osm_type?: string;
  extent?: [number, number, number, number]; // [minLon, maxLat, maxLon, minLat]
}

interface PhotonGeometry {
  type: 'Point';
  coordinates: [number, number]; // [lon, lat]
}

interface PhotonFeature {
  type: 'Feature';
  properties: PhotonProperties;
  geometry: PhotonGeometry;
}

interface PhotonFeatureCollection {
  type: 'FeatureCollection';
  features: PhotonFeature[];
}

// ── Coordinate validation ─────────────────────────────────────────────────────

function isFiniteNumber(n: unknown): n is number {
  return typeof n === 'number' && isFinite(n);
}

function isValidLon(n: number): boolean {
  return isFiniteNumber(n) && n >= -180 && n <= 180;
}

function isValidLat(n: number): boolean {
  return isFiniteNumber(n) && n >= -90 && n <= 90;
}

/**
 * Converts a Photon extent [minLon, maxLat, maxLon, minLat] to a MapLibre
 * LngLatBoundsLike [west, south, east, north]. Returns undefined if the extent
 * is missing, has wrong length, contains non-finite values, or is degenerate
 * (zero-area box).
 */
function parseBBox(extent?: [number, number, number, number]): BBox | undefined {
  if (!Array.isArray(extent) || extent.length !== 4) return undefined;
  // Photon extent: [minLon, maxLat, maxLon, minLat]
  const [minLon, maxLat, maxLon, minLat] = extent;
  if (
    !isValidLon(minLon) ||
    !isValidLon(maxLon) ||
    !isValidLat(minLat) ||
    !isValidLat(maxLat)
  )
    return undefined;
  // Guard degenerate (point-like) boxes
  if (maxLon - minLon < 0.0001 || maxLat - minLat < 0.0001) return undefined;
  // Rearrange to MapLibre [west, south, east, north]
  return [minLon, minLat, maxLon, maxLat];
}

// ── Subtitle builder ─────────────────────────────────────────────────────────

function buildSubtitle(p: PhotonProperties): string {
  const parts: string[] = [];
  if (p.city && p.city !== p.name) parts.push(p.city);
  if (p.state) parts.push(p.state);
  if (p.country) parts.push(p.country);
  return parts.join(', ');
}

function buildType(p: PhotonProperties): string {
  // Use osm_value for finer-grained type hints where available
  return p.type ?? p.osm_value ?? p.osm_key ?? 'place';
}

// ── Feature normalisation ─────────────────────────────────────────────────────

function normaliseFeature(
  feature: PhotonFeature,
  index: number,
): PlaceSuggestion | null {
  const { properties: p, geometry: g } = feature;

  // Require a Point geometry with valid coordinates
  if (g.type !== 'Point') return null;
  const [lon, lat] = g.coordinates;
  if (!isValidLon(lon) || !isValidLat(lat)) return null;

  const name = p.name ?? p.city ?? p.country ?? 'Unknown place';
  const subtitle = buildSubtitle(p);
  const type = buildType(p);
  const bbox = parseBBox(p.extent);
  const id = `photon-${p.osm_type ?? 'x'}-${p.osm_id ?? index}-${index}`;

  return { id, name, subtitle, type, lon, lat, bbox };
}

// ── GeocodingService implementation ─────────────────────────────────────────

export class PhotonGeocodingService implements GeocodingService {
  async search(query: string, signal: AbortSignal): Promise<GeocodingResult> {
    const trimmed = query.trim();
    if (trimmed.length < 2) return { status: 'empty' };

    const url = new URL(BASE_URL);
    url.searchParams.set('q', trimmed);
    url.searchParams.set('limit', String(RESULT_LIMIT));
    url.searchParams.set('lang', LANG);

    let response: Response;
    try {
      response = await fetch(url.toString(), { signal });
    } catch (err: unknown) {
      // AbortError is not a failure — the caller intentionally cancelled.
      if (err instanceof DOMException && err.name === 'AbortError') {
        return { status: 'empty' };
      }
      return {
        status: 'error',
        message: 'Network error — check your connection.',
        retryable: true,
      };
    }

    if (response.status === 429) {
      return {
        status: 'error',
        message: 'Search service is temporarily busy. Please wait a moment.',
        retryable: true,
      };
    }

    if (!response.ok) {
      return {
        status: 'error',
        message: `Geocoding service returned ${response.status}.`,
        retryable: response.status >= 500,
      };
    }

    let data: unknown;
    try {
      data = await response.json();
    } catch {
      return {
        status: 'error',
        message: 'Invalid response from geocoding service.',
        retryable: false,
      };
    }

    // Validate top-level shape
    if (
      typeof data !== 'object' ||
      data === null ||
      !('features' in data) ||
      !Array.isArray((data as PhotonFeatureCollection).features)
    ) {
      return {
        status: 'error',
        message: 'Unexpected response format from geocoding service.',
        retryable: false,
      };
    }

    const suggestions = (data as PhotonFeatureCollection).features
      .map((f, i) => normaliseFeature(f, i))
      .filter((s): s is PlaceSuggestion => s !== null);

    return suggestions.length === 0
      ? { status: 'empty' }
      : { status: 'ok', suggestions };
  }
}

/** Singleton used throughout the application. */
export const geocodingService: GeocodingService = new PhotonGeocodingService();
