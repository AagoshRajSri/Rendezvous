/**
 * Phase 7B - usePlaceSearch hook.
 *
 * Manages the search query lifecycle:
 *   - 300ms debounce before dispatching a request.
 *   - AbortController cancels in-flight requests when the query changes.
 *   - Monotonic request ID prevents stale responses from overwriting newer results.
 *   - Shallow in-memory cache avoids redundant network calls for recently seen queries.
 *   - Returns loading, results, empty, and error states.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { geocodingService } from '../services/geocoding';
import type { GeocodingService, GeocodingResult, PlaceSuggestion } from '../types/place';

const DEBOUNCE_MS = 300;
const MIN_QUERY_LENGTH = 2;
/** Maximum number of distinct queries held in the in-memory cache. */
const CACHE_MAX_SIZE = 30;

// ── Types ────────────────────────────────────────────────────────────────────

export type SearchState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ok'; suggestions: PlaceSuggestion[] }
  | { status: 'empty' }
  | { status: 'error'; message: string; retryable: boolean };

export interface UsePlaceSearchReturn {
  query: string;
  state: SearchState;
  setQuery: (q: string) => void;
  clearQuery: () => void;
  /** Manually trigger a retry using the current query (useful after an error). */
  retry: () => void;
}

// ── Minimal LRU-ish cache (FIFO eviction at CACHE_MAX_SIZE) ─────────────────

class QueryCache {
  private readonly store = new Map<string, PlaceSuggestion[]>();

  get(key: string): PlaceSuggestion[] | undefined {
    return this.store.get(key.trim().toLowerCase());
  }

  set(key: string, value: PlaceSuggestion[]): void {
    const normalized = key.trim().toLowerCase();
    if (this.store.size >= CACHE_MAX_SIZE && !this.store.has(normalized)) {
      // Evict the oldest entry
      const first = this.store.keys().next().value;
      if (first !== undefined) this.store.delete(first);
    }
    this.store.set(normalized, [...value]);
  }
}

const cache = new QueryCache();

// ── Hook ─────────────────────────────────────────────────────────────────────

interface UsePlaceSearchOptions {
  /** Override the geocoding service (useful in tests). */
  service?: GeocodingService;
}

export function usePlaceSearch(
  options: UsePlaceSearchOptions = {},
): UsePlaceSearchReturn {
  const service = options.service ?? geocodingService;

  const [query, setQueryState] = useState('');
  const [state, setState] = useState<SearchState>({ status: 'idle' });

  /** Tracks the ID of the most recently dispatched request. */
  const latestRequestId = useRef(0);
  /** Holds the AbortController for the current in-flight fetch. */
  const abortRef = useRef<AbortController | null>(null);
  /** Holds the current debounce timer. */
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const doSearch = useCallback(
    async (q: string, requestId: number) => {
      // Abort any previous in-flight request
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      setState({ status: 'loading' });

      const result: GeocodingResult = await service.search(q, controller.signal);

      // Stale-response guard: discard if aborted or a newer request/cache-hit was dispatched
      if (controller.signal.aborted || requestId !== latestRequestId.current) return;

      switch (result.status) {
        case 'ok':
          cache.set(q, result.suggestions);
          setState({ status: 'ok', suggestions: result.suggestions });
          break;
        case 'empty':
          setState({ status: 'empty' });
          break;
        case 'error':
          setState({ status: 'error', message: result.message, retryable: result.retryable });
          break;
      }
    },
    [service],
  );

  useEffect(() => {
    const trimmed = query.trim();

    // Clear pending debounce whenever query changes
    if (debounceRef.current !== null) {
      clearTimeout(debounceRef.current);
      debounceRef.current = null;
    }

    if (trimmed.length < MIN_QUERY_LENGTH) {
      // Cancel any in-flight request and invalidate its requestId so resolving aborted responses cannot overwrite idle
      abortRef.current?.abort();
      abortRef.current = null;
      latestRequestId.current++;
      setState({ status: 'idle' });
      return;
    }

    // Serve from cache synchronously if available
    const cached = cache.get(trimmed);
    if (cached) {
      // Cancel in-flight request and invalidate its requestId so a stale network response cannot overwrite the cache hit
      abortRef.current?.abort();
      abortRef.current = null;
      latestRequestId.current++;
      setState({ status: 'ok', suggestions: cached });
      return;
    }

    debounceRef.current = setTimeout(() => {
      const id = ++latestRequestId.current;
      void doSearch(trimmed, id);
    }, DEBOUNCE_MS);

    return () => {
      if (debounceRef.current !== null) {
        clearTimeout(debounceRef.current);
      }
    };
  }, [query, doSearch]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  const setQuery = useCallback((q: string) => {
    setQueryState(q);
  }, []);

  const clearQuery = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    if (debounceRef.current !== null) {
      clearTimeout(debounceRef.current);
      debounceRef.current = null;
    }
    // Invalidate any in-flight request so resolving promises cannot overwrite idle state
    latestRequestId.current++;
    setQueryState('');
    setState({ status: 'idle' });
  }, []);

  const retry = useCallback(() => {
    const trimmed = query.trim();
    if (trimmed.length < MIN_QUERY_LENGTH) return;
    const id = ++latestRequestId.current;
    void doSearch(trimmed, id);
  }, [query, doSearch]);

  return { query, state, setQuery, clearQuery, retry };
}
