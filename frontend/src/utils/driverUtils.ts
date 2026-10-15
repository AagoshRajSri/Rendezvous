import type { PlaceSuggestion } from '../types/place';
import type { DriverStatus, SimulatedDriver } from '../types/driver';
import { hashString, mulberry32 } from './seededRandom';

/**
 * Generation bounding specification for simulated drivers.
 */
export interface GenerationBounds {
  west: number;
  south: number;
  east: number;
  north: number;
  lonSpan: number;
  latSpan: number;
  crossesAntimeridian: boolean;
}

/** Maximum latitude span (in degrees) before a bbox is considered too large (e.g. country/continent). */
export const MAX_LAT_SPAN_DEG = 0.4; // ~44 km

/** Maximum longitude span (in degrees) before a bbox is considered too large. */
export const MAX_LON_SPAN_DEG = 0.6; // ~40-66 km depending on latitude

/** Minimum span below which a bbox is considered degenerate/point-like. */
export const MIN_SPAN_DEG = 0.002;

/** Default local radius in kilometers for synthesized bounds. */
export const DEFAULT_RADIUS_KM = 8;

/**
 * Validates that latitude and longitude are valid finite numbers in WGS-84 range.
 */
export function isValidCoordinate(lon: number, lat: number): boolean {
  return (
    Number.isFinite(lon) &&
    Number.isFinite(lat) &&
    lon >= -180 &&
    lon <= 180 &&
    lat >= -90 &&
    lat <= 90
  );
}

/**
 * Synthesizes a distance-aware bounding box in degrees around a center coordinate.
 * Corrects for longitude convergence at high latitudes using cos(latitude).
 */
export function synthesizeLocalBounds(
  centerLon: number,
  centerLat: number,
  radiusKm = DEFAULT_RADIUS_KM,
): GenerationBounds {
  // 1 degree latitude ≈ 111 km
  const deltaLat = radiusKm / 111.0;
  const clampedLat = Math.max(-85, Math.min(85, centerLat));
  const cosLat = Math.max(0.08, Math.cos((clampedLat * Math.PI) / 180));
  const deltaLon = radiusKm / (111.0 * cosLat);

  const south = Math.max(-89.9, centerLat - deltaLat);
  const north = Math.min(89.9, centerLat + deltaLat);

  let west = centerLon - deltaLon;
  let east = centerLon + deltaLon;

  let crossesAntimeridian = false;
  if (west < -180) {
    west += 360;
    crossesAntimeridian = true;
  } else if (east > 180) {
    east -= 360;
    crossesAntimeridian = true;
  }

  const lonSpan = crossesAntimeridian ? 360 - (west - east) : east - west;
  const latSpan = north - south;

  return {
    west,
    south,
    east,
    north,
    lonSpan,
    latSpan,
    crossesAntimeridian,
  };
}

/**
 * Derives a hardened generation bounding box for a place suggestion.
 *
 * Falls back to distance-aware synthesis when:
 * 1. Bbox is missing.
 * 2. Bbox contains non-finite or out-of-range coordinates.
 * 3. Bbox is degenerate (< MIN_SPAN_DEG).
 * 4. Bbox is excessively large (> MAX_LAT_SPAN_DEG or > MAX_LON_SPAN_DEG) to avoid
 *    scattering drivers across entire countries or provinces.
 */
export function deriveGenerationBounds(place: PlaceSuggestion): GenerationBounds {
  const { lon, lat, bbox } = place;

  if (!bbox) {
    return synthesizeLocalBounds(lon, lat);
  }

  const [west, south, east, north] = bbox;

  // Validate values
  if (
    !Number.isFinite(west) ||
    !Number.isFinite(south) ||
    !Number.isFinite(east) ||
    !Number.isFinite(north) ||
    south >= north ||
    south < -90 ||
    north > 90
  ) {
    return synthesizeLocalBounds(lon, lat);
  }

  const latSpan = north - south;
  const crossesAntimeridian = west > east;
  const lonSpan = crossesAntimeridian ? 360 - (west - east) : east - west;

  // Degenerate or negative span check
  if (latSpan < MIN_SPAN_DEG || lonSpan < MIN_SPAN_DEG) {
    return synthesizeLocalBounds(lon, lat);
  }

  // Excessively large bbox check (e.g. country, province, sea)
  if (latSpan > MAX_LAT_SPAN_DEG || lonSpan > MAX_LON_SPAN_DEG) {
    return synthesizeLocalBounds(lon, lat);
  }

  return {
    west,
    south,
    east,
    north,
    lonSpan,
    latSpan,
    crossesAntimeridian,
  };
}

/**
 * Deterministically generates 12–18 stationary simulated drivers for a given place.
 *
 * Guarantees:
 * - Deterministic: Same place produces the exact same set of drivers every time.
 * - Localized: Drivers are contained within the local area, even for country-level selections.
 * - Safe: All driver coordinates are verified valid WGS-84 numbers.
 * - Non-throwing: Returns an empty array if place coordinates are invalid.
 */
export function generateDrivers(place: PlaceSuggestion | null | undefined): SimulatedDriver[] {
  if (!place || !isValidCoordinate(place.lon, place.lat)) {
    return [];
  }

  const seed = hashString(place.id ? `${place.id}:${place.lon}:${place.lat}` : `${place.name}:${place.lon}:${place.lat}`);
  const rand = mulberry32(seed);

  const bounds = deriveGenerationBounds(place);

  // Requirement: 12–18 simulated drivers
  const count = 12 + Math.floor(rand() * 7);
  const drivers: SimulatedDriver[] = [];

  for (let i = 0; i < count; i++) {
    const latOffset = rand() * bounds.latSpan;
    const rawLat = bounds.south + latOffset;
    const lat = Math.max(-89.9, Math.min(89.9, Number(rawLat.toFixed(6))));

    let rawLon: number;
    if (bounds.crossesAntimeridian) {
      const lonOffset = rand() * bounds.lonSpan;
      rawLon = bounds.west + lonOffset;
      if (rawLon > 180) {
        rawLon -= 360;
      }
    } else {
      rawLon = bounds.west + rand() * bounds.lonSpan;
    }
    const lon = Math.max(-180, Math.min(180, Number(rawLon.toFixed(6))));

    // Distribute ~75% available, ~25% busy
    const status: DriverStatus = rand() < 0.25 ? 'busy' : 'available';

    drivers.push({
      id: `${place.id || 'place'}-sim-${i + 1}`,
      lon,
      lat,
      status,
    });
  }

  return drivers;
}
