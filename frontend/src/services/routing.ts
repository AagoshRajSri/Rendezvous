import type {
  RideTierFare,
  RouteCoordinates,
  RouteSummary,
  RoutingResult,
  RoutingService,
} from '../types/routing';
import { calculateFares } from '../utils/fare';

const OSRM_BASE_URL = 'https://router.project-osrm.org/route/v1/driving';
const CACHE_MAX_SIZE = 50;

/**
 * Calculates great-circle Haversine distance between two points in meters.
 */
export function calculateHaversineDistance(
  c1: RouteCoordinates,
  c2: RouteCoordinates,
): number {
  const R = 6371000; // Earth radius in meters
  const lat1Rad = (c1.lat * Math.PI) / 180;
  const lat2Rad = (c2.lat * Math.PI) / 180;
  const deltaLat = ((c2.lat - c1.lat) * Math.PI) / 180;
  const deltaLon = ((c2.lon - c1.lon) * Math.PI) / 180;

  const a =
    Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
    Math.cos(lat1Rad) * Math.cos(lat2Rad) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

  return R * c;
}

/**
 * Interpolates great-circle points between two coordinates for smooth globe rendering.
 */
export function interpolateGreatCirclePoints(
  c1: RouteCoordinates,
  c2: RouteCoordinates,
  numPoints = 25,
): [number, number][] {
  const points: [number, number][] = [];
  const lat1 = (c1.lat * Math.PI) / 180;
  const lon1 = (c1.lon * Math.PI) / 180;
  const lat2 = (c2.lat * Math.PI) / 180;
  const lon2 = (c2.lon * Math.PI) / 180;

  const d =
    2 *
    Math.asin(
      Math.sqrt(
        Math.pow(Math.sin((lat1 - lat2) / 2), 2) +
          Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin((lon1 - lon2) / 2), 2),
      ),
    );

  if (d < 0.000001) {
    return [
      [c1.lon, c1.lat],
      [c2.lon, c2.lat],
    ];
  }

  for (let i = 0; i <= numPoints; i++) {
    const f = i / numPoints;
    const A = Math.sin((1 - f) * d) / Math.sin(d);
    const B = Math.sin(f * d) / Math.sin(d);
    const x = A * Math.cos(lat1) * Math.cos(lon1) + B * Math.cos(lat2) * Math.cos(lon2);
    const y = A * Math.cos(lat1) * Math.sin(lon1) + B * Math.cos(lat2) * Math.sin(lon2);
    const z = A * Math.sin(lat1) + B * Math.sin(lat2);
    const lat = Math.atan2(z, Math.sqrt(Math.pow(x, 2) + Math.pow(y, 2)));
    const lon = Math.atan2(y, x);

    points.push([
      Number(((lon * 180) / Math.PI).toFixed(6)),
      Number(((lat * 180) / Math.PI).toFixed(6)),
    ]);
  }

  return points;
}

/**
 * Synthesizes a realistic fallback route when the routing server is unreachable.
 * Uses Haversine distance with a 1.25x road tortuosity factor and a 36 km/h average speed.
 */
export function createFallbackRoute(
  origin: RouteCoordinates,
  destination: RouteCoordinates,
): RouteSummary {
  const straightLineMeters = calculateHaversineDistance(origin, destination);
  // Real road distances are ~25% longer than straight lines
  const distanceMeters = Math.max(100, Math.round(straightLineMeters * 1.25));
  const distanceKm = Number((distanceMeters / 1000).toFixed(2));

  // Average vehicle speed: 36 km/h = 10 m/s
  const durationSeconds = Math.max(60, Math.round(distanceMeters / 10));
  const durationMinutes = Math.max(1, Math.round(durationSeconds / 60));

  const coordinates = interpolateGreatCirclePoints(origin, destination, 24);
  const fares: RideTierFare[] = calculateFares(distanceKm, durationMinutes);

  return {
    distanceMeters,
    distanceKm,
    durationSeconds,
    durationMinutes,
    coordinates,
    fares,
    isFallback: true,
    attribution: 'Estimated Geodesic Route (OSRM Offline Fallback)',
  };
}

class RouteCache {
  private readonly store = new Map<string, RouteSummary>();

  private makeKey(c1: RouteCoordinates, c2: RouteCoordinates): string {
    return `${c1.lon.toFixed(4)},${c1.lat.toFixed(4)}:${c2.lon.toFixed(4)},${c2.lat.toFixed(4)}`;
  }

  get(c1: RouteCoordinates, c2: RouteCoordinates): RouteSummary | undefined {
    return this.store.get(this.makeKey(c1, c2));
  }

  set(c1: RouteCoordinates, c2: RouteCoordinates, route: RouteSummary): void {
    const key = this.makeKey(c1, c2);
    if (this.store.size >= CACHE_MAX_SIZE && !this.store.has(key)) {
      const firstKey = this.store.keys().next().value;
      if (firstKey !== undefined) this.store.delete(firstKey);
    }
    this.store.set(key, route);
  }
}

export const routeCache = new RouteCache();

interface OsrmRouteGeometry {
  coordinates: [number, number][];
  type: string;
}

interface OsrmRoute {
  distance: number;
  duration: number;
  geometry: OsrmRouteGeometry;
}

interface OsrmResponse {
  code: string;
  routes?: OsrmRoute[];
  message?: string;
}

export class OsrmRoutingService implements RoutingService {
  async getRoute(
    origin: RouteCoordinates,
    destination: RouteCoordinates,
    signal?: AbortSignal,
  ): Promise<RoutingResult> {
    if (signal?.aborted) {
      return {
        status: 'error',
        message: 'Route calculation was aborted',
        retryable: false,
      };
    }

    // Validate inputs
    if (
      !Number.isFinite(origin.lon) ||
      !Number.isFinite(origin.lat) ||
      !Number.isFinite(destination.lon) ||
      !Number.isFinite(destination.lat)
    ) {
      return {
        status: 'error',
        message: 'Invalid origin or destination coordinates',
        retryable: false,
      };
    }

    // Check cache
    const cached = routeCache.get(origin, destination);
    if (cached) {
      return { status: 'ok', route: cached };
    }

    const url = `${OSRM_BASE_URL}/${origin.lon},${origin.lat};${destination.lon},${destination.lat}?overview=full&geometries=geojson`;

    try {
      const response = await fetch(url, { signal });

      if (!response.ok) {
        // Fall back gracefully on server error / rate limit
        const fallback = createFallbackRoute(origin, destination);
        routeCache.set(origin, destination, fallback);
        return { status: 'ok', route: fallback };
      }

      const data: OsrmResponse = await response.json();

      if (data.code !== 'Ok' || !data.routes || data.routes.length === 0) {
        // If OSRM can't find a driving route (e.g. across water), fall back gracefully
        const fallback = createFallbackRoute(origin, destination);
        routeCache.set(origin, destination, fallback);
        return { status: 'ok', route: fallback };
      }

      const primary = data.routes[0];
      const distanceMeters = Math.round(primary.distance);
      const distanceKm = Number((distanceMeters / 1000).toFixed(2));
      const durationSeconds = Math.round(primary.duration);
      const durationMinutes = Math.max(1, Math.round(durationSeconds / 60));
      const coordinates = primary.geometry.coordinates;

      const fares = calculateFares(distanceKm, durationMinutes);

      const route: RouteSummary = {
        distanceMeters,
        distanceKm,
        durationSeconds,
        durationMinutes,
        coordinates,
        fares,
        isFallback: false,
        attribution: 'Routes © OpenStreetMap contributors, OSRM',
      };

      routeCache.set(origin, destination, route);
      return { status: 'ok', route };
    } catch (err: unknown) {
      if (signal?.aborted || (err instanceof Error && err.name === 'AbortError')) {
        return {
          status: 'error',
          message: 'Route calculation was aborted',
          retryable: false,
        };
      }

      // Network failures fall back gracefully to offline geodesic route
      const fallback = createFallbackRoute(origin, destination);
      routeCache.set(origin, destination, fallback);
      return { status: 'ok', route: fallback };
    }
  }
}

export const routingService: RoutingService = new OsrmRoutingService();
