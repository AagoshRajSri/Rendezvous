import type { PlaceSuggestion } from './place';

export interface RouteCoordinates {
  lon: number;
  lat: number;
}

export interface RideTierFare {
  tierId: 'standard' | 'premier' | 'xl';
  name: string;
  tagline: string;
  capacity: number;
  baseFare: number;
  distanceFare: number;
  timeFare: number;
  totalFare: number;
  currency: string;
  etaMinutes: number;
  icon: string;
}

export interface RouteSummary {
  /** Total driving distance in meters */
  distanceMeters: number;
  /** Distance in kilometers formatted or raw */
  distanceKm: number;
  /** Estimated duration in seconds */
  durationSeconds: number;
  /** Estimated duration in minutes */
  durationMinutes: number;
  /** GeoJSON LineString coordinates: array of [lon, lat] */
  coordinates: [number, number][];
  /** Tiered fare estimates */
  fares: RideTierFare[];
  /** True if fallback geodesic interpolation was used because routing API was unavailable */
  isFallback: boolean;
  /** Data attribution for route */
  attribution: string;
}

export type RoutingResult =
  | { status: 'ok'; route: RouteSummary }
  | { status: 'error'; message: string; retryable: boolean };

export interface RoutingService {
  getRoute(
    origin: RouteCoordinates,
    destination: RouteCoordinates,
    signal?: AbortSignal,
  ): Promise<RoutingResult>;
}

export interface TripEndpoints {
  pickup: PlaceSuggestion | null;
  destination: PlaceSuggestion | null;
}
