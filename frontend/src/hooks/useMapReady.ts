import { useCallback, useState } from 'react';

export type MapReadyState =
  | { status: 'loading' }
  | { status: 'ready' }
  | { status: 'error'; message: string };

export interface UseMapReadyReturn {
  state: MapReadyState;
  onLoad: () => void;
  onError: (message: string) => void;
}

/**
 * Tracks whether the MapLibre map style has finished loading.
 *
 * - 'loading' : initial state; style tiles are being fetched
 * - 'ready'   : style.load fired successfully
 * - 'error'   : map emitted an error event before load completed
 */
export function useMapReady(): UseMapReadyReturn {
  const [state, setState] = useState<MapReadyState>({ status: 'loading' });

  const onLoad = useCallback(() => {
    setState({ status: 'ready' });
  }, []);

  const onError = useCallback((message: string) => {
    setState({ status: 'error', message });
  }, []);

  return { state, onLoad, onError };
}
