import { useCallback, useEffect, useRef, useState } from 'react';
import type { PlaceSuggestion } from '../types/place';
import type { RouteSummary } from '../types/routing';
import { routingService } from '../services/routing';

export type RouteLoadingStatus = 'idle' | 'loading' | 'ready' | 'error';

export interface RouteState {
  status: RouteLoadingStatus;
  route: RouteSummary | null;
  selectedTier: 'standard' | 'premier' | 'xl';
  errorMessage: string | null;
}

export interface UseRouteReturn {
  state: RouteState;
  setSelectedTier: (tier: 'standard' | 'premier' | 'xl') => void;
  clearRoute: () => void;
}

export function useRoute(
  pickup: PlaceSuggestion | null,
  destination: PlaceSuggestion | null,
): UseRouteReturn {
  const [state, setState] = useState<RouteState>({
    status: 'idle',
    route: null,
    selectedTier: 'standard',
    errorMessage: null,
  });

  const abortRef = useRef<AbortController | null>(null);

  const calculateRoute = useCallback(async (origin: PlaceSuggestion, dest: PlaceSuggestion) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setState((prev) => ({
      ...prev,
      status: 'loading',
      errorMessage: null,
    }));

    const result = await routingService.getRoute(
      { lon: origin.lon, lat: origin.lat },
      { lon: dest.lon, lat: dest.lat },
      controller.signal,
    );

    if (controller.signal.aborted) return;

    if (result.status === 'ok') {
      setState((prev) => ({
        ...prev,
        status: 'ready',
        route: result.route,
        errorMessage: null,
      }));
    } else {
      setState((prev) => ({
        ...prev,
        status: 'error',
        errorMessage: result.message,
      }));
    }
  }, []);

  useEffect(() => {
    if (pickup && destination) {
      void calculateRoute(pickup, destination);
    } else {
      abortRef.current?.abort();
      abortRef.current = null;
      setState((prev) => ({
        ...prev,
        status: 'idle',
        route: null,
        errorMessage: null,
      }));
    }

    return () => {
      abortRef.current?.abort();
    };
  }, [pickup, destination, calculateRoute]);

  const setSelectedTier = useCallback((tier: 'standard' | 'premier' | 'xl') => {
    setState((prev) => ({ ...prev, selectedTier: tier }));
  }, []);

  const clearRoute = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setState({
      status: 'idle',
      route: null,
      selectedTier: 'standard',
      errorMessage: null,
    });
  }, []);

  return { state, setSelectedTier, clearRoute };
}
