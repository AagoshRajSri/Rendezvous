/**
 * Phase 11.4D - React hook for fetching the read-only dispatch execution trace.
 *
 * Fetches GET /api/requests/{requestId}/trace and exposes the result through a
 * stable state contract that consumers can react to without managing cancellation
 * or stale-response suppression themselves.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import type { DispatchTrace } from '../types/dispatch';
import { getDispatchTrace } from '../services/dispatch';

// ── State contract ────────────────────────────────────────────────────────────

/**
 * Loading and outcome status for the dispatch trace hook.
 *
 * idle - No valid requestId supplied; no request has been sent.
 * loading - Fetch is in flight.
 * ready - Trace successfully retrieved; state.trace is populated.
 * not_found - Backend returned HTTP 404 (trace not yet generated,
 *              expired, or evicted from the in-memory cache).
 * error - Network failure or non-404 server error; state.errorMessage is set.
 */
export type DispatchTraceStatus = 'idle' | 'loading' | 'ready' | 'not_found' | 'error';

export interface DispatchTraceState {
  status: DispatchTraceStatus;
  /** Populated only when status === 'ready'. Null otherwise. */
  trace: DispatchTrace | null;
  /** Populated only when status === 'error'. Null otherwise. */
  errorMessage: string | null;
}

export interface UseDispatchTraceReturn {
  state: DispatchTraceState;
  /**
   * Manually re-fetches the trace for the current requestId.
   * No-op when requestId is blank or absent.
   * Useful for callers that want to poll on demand or recover after a 404.
   */
  refresh: () => void;
}

// ── Constants ─────────────────────────────────────────────────────────────────

const IDLE_STATE: DispatchTraceState = {
  status: 'idle',
  trace: null,
  errorMessage: null,
};

// ── Hook ──────────────────────────────────────────────────────────────────────

/**
 * Fetches and manages the read-only dispatch execution trace for a ride request.
 *
 * Design decisions:
 * - Does not send a request when requestId is null, undefined, or blank.
 * - Uses AbortController so in-flight requests are cancelled on requestId
 *   change or unmount; stale responses are silently dropped via signal.aborted.
 * - HTTP 404 → status 'not_found' (distinct from 'error'); callers can prompt
 *   the user to retry after a delay when the trace is not yet available.
 * - A stable `refresh` callback increments an internal counter, which re-runs
 *   the effect while keeping the requestId unchanged.
 * - No automatic polling; repetitive fetch scheduling is left to the caller.
 */
export function useDispatchTrace(
  requestId: string | null | undefined,
): UseDispatchTraceReturn {
  const [state, setState] = useState<DispatchTraceState>(IDLE_STATE);
  // Incrementing refreshCounter re-triggers the effect for a manual refresh.
  const [refreshCounter, setRefreshCounter] = useState(0);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!requestId || requestId.trim() === '') {
      // Cancel any in-flight request before going idle
      abortRef.current?.abort();
      abortRef.current = null;
      setState(IDLE_STATE);
      return;
    }

    // Cancel the previous in-flight request before starting a new one.
    // This also suppresses stale state updates via the signal.aborted check.
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const { signal } = controller;

    // Reset state to loading without preserving prior request trace to prevent cross-request leakage.
    setState({ status: 'loading', trace: null, errorMessage: null });

    getDispatchTrace(requestId, signal)
      .then((trace) => {
        if (signal.aborted) return;
        if (trace === null) {
          setState({ status: 'not_found', trace: null, errorMessage: null });
        } else {
          setState({ status: 'ready', trace, errorMessage: null });
        }
      })
      .catch((err: unknown) => {
        if (signal.aborted) return;
        setState({
          status: 'error',
          trace: null,
          errorMessage: err instanceof Error ? err.message : String(err),
        });
      });

    return () => {
      controller.abort();
    };
  }, [requestId, refreshCounter]);

  const refresh = useCallback(() => {
    if (!requestId || requestId.trim() === '') return;
    setRefreshCounter((n) => n + 1);
  }, [requestId]);

  return { state, refresh };
}
