import { useCallback, useEffect, useRef, useState } from 'react';
import { calculateHaversineDistance } from '../services/routing';

export interface VehicleAnimationState {
  /** Current interpolated [lon, lat] coordinate of the vehicle. */
  currentPosition: [number, number] | null;
  /** Bearing in degrees [0, 360) representing vehicle heading. */
  bearing: number;
  /** Normalized animation progress [0.0, 1.0]. */
  progress: number;
  /** Remaining road distance along the route in meters. */
  remainingDistanceMeters: number;
  /** Remaining estimated time of arrival in seconds. */
  remainingDurationSeconds: number;
  /** Whether the vehicle has reached the final destination point. */
  isArrived: boolean;
}

export interface UseVehicleAnimationOptions {
  /** Ordered route coordinates [[lon, lat], ...] from driver origin to pickup. */
  routeCoordinates: [number, number][] | null;
  /** Total expected trip duration in seconds from routing service. */
  nominalDurationSeconds?: number;
  /** Simulation speed multiplier (e.g. 10 = 10x faster than real-time). Default: 8. */
  speedMultiplier?: number;
  /** Whether animation is currently active. When false, resets or pauses. */
  isActive: boolean;
  /** Optional callback fired when vehicle reaches 100% of the route. */
  onArrival?: () => void;
}

export interface CumulativeRoute {
  totalDistance: number;
  cumulativeDistances: number[];
}

/**
 * Calculates the forward azimuth / bearing in degrees [0, 360) from coordinate A to B.
 */
export function calculateBearing(start: [number, number], end: [number, number]): number {
  const [lon1, lat1] = start;
  const [lon2, lat2] = end;

  const lat1Rad = (lat1 * Math.PI) / 180;
  const lat2Rad = (lat2 * Math.PI) / 180;
  const deltaLonRad = ((lon2 - lon1) * Math.PI) / 180;

  const y = Math.sin(deltaLonRad) * Math.cos(lat2Rad);
  const x =
    Math.cos(lat1Rad) * Math.sin(lat2Rad) -
    Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(deltaLonRad);

  const radians = Math.atan2(y, x);
  const degrees = (radians * 180) / Math.PI;
  return (degrees + 360) % 360;
}

/**
 * Computes cumulative Haversine distances along a coordinate sequence.
 */
export function buildCumulativeRoute(coordinates: [number, number][]): CumulativeRoute {
  if (coordinates.length === 0) {
    return { totalDistance: 0, cumulativeDistances: [] };
  }

  const cumulativeDistances = [0];
  let total = 0;

  for (let i = 0; i < coordinates.length - 1; i++) {
    const p1 = coordinates[i];
    const p2 = coordinates[i + 1];
    const dist = calculateHaversineDistance(
      { lon: p1[0], lat: p1[1] },
      { lon: p2[0], lat: p2[1] },
    );
    total += dist;
    cumulativeDistances.push(total);
  }

  return { totalDistance: total, cumulativeDistances };
}

/**
 * Interpolates vehicle position and bearing along a cumulative route given traveled distance.
 */
export function interpolateRoutePosition(
  coordinates: [number, number][],
  cumulative: CumulativeRoute,
  distanceTraveledMeters: number,
  fallbackBearing = 0,
): { position: [number, number]; bearing: number } {
  if (coordinates.length === 0) {
    return { position: [0, 0], bearing: fallbackBearing };
  }
  if (coordinates.length === 1 || cumulative.totalDistance <= 0) {
    return { position: coordinates[0], bearing: fallbackBearing };
  }

  const clampedDist = Math.max(0, Math.min(cumulative.totalDistance, distanceTraveledMeters));

  // Find segment [k, k + 1]
  let k = 0;
  while (
    k < cumulative.cumulativeDistances.length - 2 &&
    cumulative.cumulativeDistances[k + 1] < clampedDist
  ) {
    k++;
  }

  const segStartDist = cumulative.cumulativeDistances[k];
  const segEndDist = cumulative.cumulativeDistances[k + 1];
  const segSpan = segEndDist - segStartDist;

  const p1 = coordinates[k];
  const p2 = coordinates[k + 1];

  const bearing = calculateBearing(p1, p2);

  if (segSpan <= 0) {
    return { position: p1, bearing };
  }

  const fraction = (clampedDist - segStartDist) / segSpan;
  const lon = p1[0] + fraction * (p2[0] - p1[0]);
  const lat = p1[1] + fraction * (p2[1] - p1[1]);

  return {
    position: [Number(lon.toFixed(6)), Number(lat.toFixed(6))],
    bearing,
  };
}

/**
 * Hook to smoothly animate vehicle marker along real road geometry using requestAnimationFrame.
 * Uses performance.now() elapsed time rather than frame counts for frame-rate independence.
 */
export function useVehicleAnimation({
  routeCoordinates,
  nominalDurationSeconds = 60,
  speedMultiplier = 8,
  isActive,
  onArrival,
}: UseVehicleAnimationOptions) {
  const [animState, setAnimState] = useState<VehicleAnimationState>(() => {
    const firstCoord = routeCoordinates && routeCoordinates.length > 0 ? routeCoordinates[0] : null;
    return {
      currentPosition: firstCoord,
      bearing: 0,
      progress: 0,
      remainingDistanceMeters: 0,
      remainingDurationSeconds: nominalDurationSeconds,
      isArrived: false,
    };
  });

  const animFrameRef = useRef<number | null>(null);
  const startTimeRef = useRef<number | null>(null);
  const onArrivalRef = useRef(onArrival);
  onArrivalRef.current = onArrival;

  const reset = useCallback(() => {
    if (animFrameRef.current !== null) {
      cancelAnimationFrame(animFrameRef.current);
      animFrameRef.current = null;
    }
    startTimeRef.current = null;
    const firstCoord = routeCoordinates && routeCoordinates.length > 0 ? routeCoordinates[0] : null;
    setAnimState({
      currentPosition: firstCoord,
      bearing: 0,
      progress: 0,
      remainingDistanceMeters: 0,
      remainingDurationSeconds: nominalDurationSeconds,
      isArrived: false,
    });
  }, [routeCoordinates, nominalDurationSeconds]);

  useEffect(() => {
    if (!isActive || !routeCoordinates || routeCoordinates.length < 2) {
      reset();
      return;
    }

    const cumulative = buildCumulativeRoute(routeCoordinates);
    const totalDist = cumulative.totalDistance;

    // Simulation duration in seconds
    const effectiveSpeedMultiplier = Math.max(0.1, speedMultiplier);
    // Baseline nominal trip duration or estimated duration based on 40 km/h (11.1 m/s)
    const baseDuration =
      nominalDurationSeconds > 0 ? nominalDurationSeconds : Math.max(10, Math.round(totalDist / 11.1));
    const simDurationMs = Math.max(1000, (baseDuration / effectiveSpeedMultiplier) * 1000);

    let lastBearing = 0;
    if (routeCoordinates.length >= 2) {
      lastBearing = calculateBearing(routeCoordinates[0], routeCoordinates[1]);
    }

    // Set initial start position
    setAnimState({
      currentPosition: routeCoordinates[0],
      bearing: lastBearing,
      progress: 0,
      remainingDistanceMeters: totalDist,
      remainingDurationSeconds: baseDuration,
      isArrived: false,
    });

    let hasFiredArrival = false;

    const frameCallback = (now: number) => {
      if (startTimeRef.current === null) {
        startTimeRef.current = now;
      }

      const elapsedMs = now - startTimeRef.current;
      const progress = Math.min(1.0, elapsedMs / simDurationMs);
      const traveledDist = progress * totalDist;
      const remainingDist = Math.max(0, totalDist - traveledDist);
      const remainingSecs = Math.max(0, Math.round((1 - progress) * baseDuration));

      const { position, bearing } = interpolateRoutePosition(
        routeCoordinates,
        cumulative,
        traveledDist,
        lastBearing,
      );
      lastBearing = bearing;

      const arrived = progress >= 1.0;

      setAnimState({
        currentPosition: position,
        bearing,
        progress,
        remainingDistanceMeters: Math.round(remainingDist),
        remainingDurationSeconds: remainingSecs,
        isArrived: arrived,
      });

      if (arrived) {
        if (!hasFiredArrival) {
          hasFiredArrival = true;
          onArrivalRef.current?.();
        }
        animFrameRef.current = null;
      } else {
        animFrameRef.current = requestAnimationFrame(frameCallback);
      }
    };

    animFrameRef.current = requestAnimationFrame(frameCallback);

    return () => {
      if (animFrameRef.current !== null) {
        cancelAnimationFrame(animFrameRef.current);
        animFrameRef.current = null;
      }
      startTimeRef.current = null;
    };
  }, [isActive, routeCoordinates, nominalDurationSeconds, speedMultiplier, reset]);

  return {
    ...animState,
    reset,
  };
}
