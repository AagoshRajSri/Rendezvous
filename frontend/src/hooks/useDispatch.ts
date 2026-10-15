import { useCallback, useEffect, useRef, useState } from 'react';
import type { PlaceSuggestion } from '../types/place';
import type { DispatchState } from '../types/dispatch';
import { getRequestStatus, registerDriver, submitRideRequest } from '../services/dispatch';

// ── Polling configuration ────────────────────────────────────────────────────

/** Milliseconds between successive status polls (exponential backoff base). */
const POLL_BASE_MS = 1_500;
/** Multiplier applied to each subsequent poll interval. */
const POLL_BACKOFF = 1.5;
/** Maximum poll interval in milliseconds. */
const POLL_MAX_MS = 12_000;
/** Stop polling after this many attempts regardless of outcome. */
const POLL_MAX_ATTEMPTS = 20;

// ── Initial state ─────────────────────────────────────────────────────────────

const IDLE_STATE: DispatchState = {
  phase: 'idle',
  requestId: null,
  matchedDriverId: null,
  matchedDriverLocation: null,
  errorMessage: null,
  updatedAt: null,
};

// ── Unique ID generator ───────────────────────────────────────────────────────

function generateRequestId(): string {
  return `req-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

function generateDriverId(placeName: string): string {
  // Deterministic prefix from place name; suffix prevents collision across sessions
  const slug = placeName.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 8);
  return `demo-driver-${slug}-${Math.random().toString(36).slice(2, 7)}`;
}

// ── Hook ──────────────────────────────────────────────────────────────────────

export interface UseDispatchReturn {
  dispatchState: DispatchState;
  /** Call to begin the full dispatch flow: register demo driver → submit request → poll. */
  requestDispatch: () => void;
  /** Resets to idle (e.g., after place change, on user cancel). */
  reset: () => void;
}

/**
 * Phase 7D — Frontend dispatch lifecycle hook.
 *
 * Orchestrates three sequential steps:
 *   1. Register a demo driver near the selected place (POST /api/register).
 *   2. Submit a ride request at the selected place (POST /api/request → 202).
 *   3. Poll GET /api/requests/{id} with bounded exponential backoff until a
 *      terminal state (MATCHED or NO_DRIVERS) is reached, or until exhausted.
 *
 * Safety guarantees:
 * - Duplicate submissions are prevented: a second requestDispatch() call while
 *   a flow is in-flight is a no-op.
 * - AbortController cancels all in-flight fetch requests when the selected place
 *   changes, when reset() is called, or when the component unmounts.
 * - Cancellation notice: aborting frontend polling cancels client-side network
 *   operations only; it does not cancel an already-submitted dispatch request
 *   in the backend Kafka/PostgreSQL pipeline.
 * - Driver registration HTTP 202 is treated strictly as queued acceptance, not
 *   verified readiness. No artificial delay is presumed as readiness proof.
 *   If no driver is ready when matching runs, NO_DRIVERS is handled cleanly with
 *   a safe manual retry action without automatic resubmission.
 * - State-changing POST calls (register, request) are never automatically retried.
 * - Polling is retried up to POLL_MAX_ATTEMPTS times with exponential backoff.
 * - Simulated drivers are never fed into this pipeline; they are managed
 *   entirely by useSimulatedDrivers.
 *
 * @param selectedPlace - The currently selected map location. The hook auto-resets
 *   when this changes, cancelling any in-flight requests.
 */
export function useDispatch(selectedPlace: PlaceSuggestion | null): UseDispatchReturn {
  const [dispatchState, setDispatchState] = useState<DispatchState>(IDLE_STATE);

  // Ref to the current AbortController so we can cancel on place change / reset / unmount
  const abortRef = useRef<AbortController | null>(null);

  // Track whether a dispatch flow is active to prevent duplicate submissions
  const activeRef = useRef(false);

  // ── Reset when selected place changes ─────────────────────────────────────

  const selectedPlaceId = selectedPlace?.id;

  useEffect(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    activeRef.current = false;
    setDispatchState(IDLE_STATE);
  }, [selectedPlaceId]);

  // ── Cleanup on unmount ────────────────────────────────────────────────────

  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  // ── Core dispatch flow ────────────────────────────────────────────────────

  const requestDispatch = useCallback(() => {
    // Prevent duplicate submissions and guard against no place selected
    if (activeRef.current || !selectedPlace) return;
    activeRef.current = true;

    // Cancel any previous request chain (guard against re-invocations)
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const signal = controller.signal;

    void runDispatchFlow(selectedPlace, signal, setDispatchState, () => {
      activeRef.current = false;
    });
  }, [selectedPlace]);

  // ── Reset ─────────────────────────────────────────────────────────────────

  const reset = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    activeRef.current = false;
    setDispatchState(IDLE_STATE);
  }, []);

  return { dispatchState, requestDispatch, reset };
}

// ── Flow orchestration (outside the hook for testability) ────────────────────

/**
 * Runs the full three-step dispatch flow.
 * Called as a fire-and-forget async from the hook; errors are captured into state.
 */
async function runDispatchFlow(
  place: PlaceSuggestion,
  signal: AbortSignal,
  setState: React.Dispatch<React.SetStateAction<DispatchState>>,
  onComplete: () => void,
): Promise<void> {
  // Step 1: Register a demo driver near the selected location.
  const driverId = generateDriverId(place.name);
  setState((s) => ({ ...s, phase: 'registering' }));

  try {
    await registerDriver(
      {
        driverId,
        latitude: place.lat,
        longitude: place.lon,
        action: 'REGISTER',
        capturedAt: Date.now(),
      },
      signal,
    );
  } catch (err) {
    if (signal.aborted) return;
    setState({
      ...IDLE_STATE,
      phase: 'error',
      errorMessage: toMessage(err) || 'Driver registration failed',
    });
    onComplete();
    return;
  }

  if (signal.aborted) return;

  // Notice: HTTP 202 from /api/register indicates queued receipt by Kafka,
  // NOT verified driver readiness in the in-memory registry. No existing
  // endpoint supports driver readiness lookup. We treat registration as
  // queued and do not presume readiness via an artificial delay. If matching
  // runs before the consumer processes the driver, NO_DRIVERS is received
  // and handled with a clear explanation and safe manual retry action.

  // Step 2: Submit the ride request.
  const requestId = generateRequestId();
  setState((s) => ({ ...s, phase: 'submitting', requestId }));

  try {
    await submitRideRequest({ requestId, latitude: place.lat, longitude: place.lon }, signal);
  } catch (err) {
    if (signal.aborted) return;
    setState({
      ...IDLE_STATE,
      phase: 'error',
      requestId,
      errorMessage: toMessage(err) || 'Ride request submission failed',
    });
    onComplete();
    return;
  }

  if (signal.aborted) return;

  // Step 3: Poll for the terminal outcome.
  setState((s) => ({ ...s, phase: 'polling' }));

  let attempt = 0;
  let intervalMs = POLL_BASE_MS;

  while (attempt < POLL_MAX_ATTEMPTS) {
    await delay(intervalMs, signal);
    if (signal.aborted) return;

    let statusResponse;
    try {
      statusResponse = await getRequestStatus(requestId, signal);
    } catch (err) {
      if (signal.aborted) return;
      // Non-404 error; treat as transient and keep polling unless near the limit
      if (attempt >= POLL_MAX_ATTEMPTS - 1) {
        setState({
          ...IDLE_STATE,
          phase: 'error',
          requestId,
          errorMessage: toMessage(err) || 'Status check failed',
        });
        onComplete();
        return;
      }
      attempt++;
      intervalMs = Math.min(intervalMs * POLL_BACKOFF, POLL_MAX_MS);
      continue;
    }

    if (signal.aborted) return;

    if (statusResponse === null) {
      // 404 → Kafka consumer has not persisted the row yet; keep polling
      attempt++;
      intervalMs = Math.min(intervalMs * POLL_BACKOFF, POLL_MAX_MS);
      continue;
    }

    const status = statusResponse.status;

    if (status === 'MATCHED') {
      setState({
        phase: 'matched',
        requestId,
        matchedDriverId: statusResponse.driverId,
        matchedDriverLocation: null, // Backend does not supply verified driver coordinates
        errorMessage: null,
        updatedAt: statusResponse.updatedAt,
      });
      onComplete();
      return;
    }

    if (status === 'NO_DRIVERS') {
      setState({
        phase: 'no_drivers',
        requestId,
        matchedDriverId: null,
        matchedDriverLocation: null,
        errorMessage: null,
        updatedAt: statusResponse.updatedAt,
      });
      onComplete();
      return;
    }

    // PENDING → keep polling
    attempt++;
    intervalMs = Math.min(intervalMs * POLL_BACKOFF, POLL_MAX_MS);
  }

  // Exhausted polling budget without a terminal result
  setState({
    phase: 'error',
    requestId,
    matchedDriverId: null,
    matchedDriverLocation: null,
    errorMessage:
      'No match result received after maximum polling attempts. The backend may be unavailable.',
    updatedAt: null,
  });
  onComplete();
}

// ── Utilities ─────────────────────────────────────────────────────────────────

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException('Aborted', 'AbortError'));
      return;
    }
    const timer = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => {
      clearTimeout(timer);
      reject(new DOMException('Aborted', 'AbortError'));
    });
  });
}

function toMessage(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}
