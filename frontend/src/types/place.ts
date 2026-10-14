/**
 * Phase 7B — Geocoding domain types.
 *
 * PlaceSuggestion is the normalised internal representation used across the
 * application. GeocodingService is the provider interface; swap implementations
 * without touching consumers.
 */

/**
 * A validated bounding box in MapLibre-ready form: [west, south, east, north].
 * All values are finite numbers in valid WGS-84 ranges.
 */
export type BBox = [west: number, south: number, east: number, north: number];

/**
 * Normalised place suggestion returned by a GeocodingService.
 */
export interface PlaceSuggestion {
  /** Unique identifier for this suggestion (opaque string). */
  id: string;
  /** Primary display name, e.g. "Paris", "Eiffel Tower". */
  name: string;
  /** Secondary display name: city, state, country hierarchy. */
  subtitle: string;
  /** Place category: city, town, village, street, house, other. */
  type: string;
  /** WGS-84 longitude, validated finite, -180 ≤ lon ≤ 180. */
  lon: number;
  /** WGS-84 latitude, validated finite, -90 ≤ lat ≤ 90. */
  lat: number;
  /**
   * Optional bounding box [west, south, east, north] in WGS-84.
   * Present only when the provider returns a valid, non-degenerate extent.
   * Callers must fall back to flyTo when undefined.
   */
  bbox?: BBox;
}

/**
 * Result shape returned by GeocodingService.search.
 * A discriminated union — always check `status` before accessing `suggestions`
 * or `error`.
 */
export type GeocodingResult =
  | { status: 'ok'; suggestions: PlaceSuggestion[] }
  | { status: 'empty' }
  | { status: 'error'; message: string; retryable: boolean };

/**
 * Provider-agnostic geocoding interface.
 *
 * Implementations must:
 * - Accept a query string and an AbortSignal.
 * - Return a GeocodingResult (never throw — internal errors are returned as
 *   { status: 'error' }).
 * - Respect the signal and return early without throwing on abort.
 */
export interface GeocodingService {
  search(query: string, signal: AbortSignal): Promise<GeocodingResult>;
}
