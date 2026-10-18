import { useEffect, useState } from 'react';
import type { RouteSummary } from '../types/routing';
import type { PlaceSuggestion } from '../types/place';
import { routingService } from '../services/routing';

export type DriverApproachStatus = 'idle' | 'loading' | 'ready' | 'error';

export interface DriverApproachState {
  status: DriverApproachStatus;
  route: RouteSummary | null;
  errorMessage: string | null;
}

/**
 * Hook to request and maintain the real road route from the assigned driver's
 * verified coordinates to the pickup location.
 *
 * Safe:
 * - Only queries when phase is 'matched' and both driver and pickup coordinates are valid.
 * - Automatically falls back to geodesic route if OSRM service is unreachable.
 * - Cancels in-flight requests when coordinates change, ride is cancelled, or on unmount.
 */
export function useDriverApproach(
  matchedDriverLocation: { lat: number; lon: number } | null,
  pickupPlace: PlaceSuggestion | null,
  isMatched: boolean,
): DriverApproachState {
  const [state, setState] = useState<DriverApproachState>({
    status: 'idle',
    route: null,
    errorMessage: null,
  });

  useEffect(() => {
    if (!isMatched || !matchedDriverLocation || !pickupPlace) {
      setState({ status: 'idle', route: null, errorMessage: null });
      return;
    }

    const controller = new AbortController();
    const signal = controller.signal;

    setState((s) => ({ ...s, status: 'loading', errorMessage: null }));

    const origin = { lon: matchedDriverLocation.lon, lat: matchedDriverLocation.lat };
    const destination = { lon: pickupPlace.lon, lat: pickupPlace.lat };

    routingService
      .getRoute(origin, destination, signal)
      .then((res) => {
        if (signal.aborted) return;
        if (res.status === 'ok') {
          setState({
            status: 'ready',
            route: res.route,
            errorMessage: null,
          });
        } else {
          setState({
            status: 'error',
            route: null,
            errorMessage: res.message,
          });
        }
      })
      .catch((err: unknown) => {
        if (signal.aborted) return;
        setState({
          status: 'error',
          route: null,
          errorMessage: err instanceof Error ? err.message : String(err),
        });
      });

    return () => {
      controller.abort();
    };
  }, [isMatched, matchedDriverLocation?.lat, matchedDriverLocation?.lon, pickupPlace?.lat, pickupPlace?.lon]);

  return state;
}
