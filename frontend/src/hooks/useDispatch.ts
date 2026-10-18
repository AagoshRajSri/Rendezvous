import { useCallback, useEffect, useRef, useState } from 'react';
import type { PlaceSuggestion } from '../types/place';
import type { DispatchState } from '../types/dispatch';
import type { SimulatedDriver } from '../types/driver';
import { cancelTrip, getRequestStatus, registerDriver, submitRideRequest } from '../services/dispatch';

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
  matchedDriverLocationAt: null,
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
  /** Call to begin the full dispatch flow: register visible fleet → submit request → poll. */
  requestDispatch: () => void;
  /** Phase 11.2: Cancels the ride prior to passenger pickup (when phase is matched). */
  cancelRide: () => Promise<boolean>;
  /** Phase 11.2: Whether a cancellation request is currently in flight. */
  isCancelling: boolean;
  /** Resets to idle (e.g., after place change, on user cancel). */
  reset: () => void;
}

/**
 * Phase 11.2 — Frontend dispatch lifecycle hook with fleet synchronization.
 *
 * Orchestrates:
 *   1. Register available drivers from the visible fleet (or demo driver fallback)
 *      with their actual map coordinates and stable IDs (POST /api/register).
 *   2. Submit a ride request at the selected place (POST /api/request → 202).
 *   3. Poll GET /api/requests/{id} with bounded exponential backoff until a
 *      terminal state (MATCHED or NO_DRIVERS) is reached, or until exhausted.
 *   4. Supports cancellation via POST /api/trips/{requestId}/cancel while matched.
 *
 * @param selectedPlace - The currently selected map location.
 * @param visibleDrivers - Optional visible simulated drivers to register in the backend registry.
 */
export function useDispatch(
  selectedPlace: PlaceSuggestion | null,
  visibleDrivers?: SimulatedDriver[],
): UseDispatchReturn {
  const [dispatchState, setDispatchState] = useState<DispatchState>(IDLE_STATE);
  const [isCancelling, setIsCancelling] = useState(false);

  // Ref to the current AbortController so we can cancel on place change / reset / unmount
  const abortRef = useRef<AbortController | null>(null);

  // Track whether a dispatch flow is active to prevent duplicate submissions
  const activeRef = useRef(false);
  // Track whether a cancellation request is in-flight to prevent duplicate cancellations
  const isCancellingRef = useRef(false);

  const visibleDriversRef = useRef(visibleDrivers);
  visibleDriversRef.current = visibleDrivers;

  // ── Reset when selected place changes ─────────────────────────────────────

  const selectedPlaceId = selectedPlace?.id;

  useEffect(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    activeRef.current = false;
    isCancellingRef.current = false;
    setIsCancelling(false);
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

    runDispatchFlow(
      selectedPlace,
      signal,
      setDispatchState,
      () => {
        // Only release active lock if this flow is still the current active one
        if (abortRef.current === controller) {
          activeRef.current = false;
        }
      },
      visibleDriversRef.current,
    ).catch((err) => {
      const isCurrent = abortRef.current === controller;
      // Only release active lock if this flow is still the current active one
      if (isCurrent) {
        activeRef.current = false;
      }

      // AbortErrors from intentional cancellation (reset / place change / unmount)
      // are silently discarded.
      const isAbort = err instanceof DOMException && err.name === 'AbortError';
      if (isAbort) {
        return;
      }

      // Preserve visibility for unexpected errors via logging even if signal was aborted
      console.error('Unhandled error in dispatch flow:', err);

      // Only update UI error state if this dispatch is still the current active one and not aborted
      if (isCurrent && !signal.aborted) {
        setDispatchState({
          ...IDLE_STATE,
          phase: 'error',
          errorMessage: toMessage(err) || 'Unexpected dispatch error',
        });
      }
    });
  }, [selectedPlace]);

  // ── Trip cancellation (Phase 11.2) ────────────────────────────────────────

  const cancelRide = useCallback(async (): Promise<boolean> => {
    if (
      isCancellingRef.current ||
      dispatchState.phase !== 'matched' ||
      !dispatchState.requestId ||
      !dispatchState.matchedDriverId
    ) {
      return false;
    }

    const requestId = dispatchState.requestId;
    const driverId = dispatchState.matchedDriverId;

    isCancellingRef.current = true;
    setIsCancelling(true);

    try {
      await cancelTrip(requestId, driverId, abortRef.current?.signal);
      setDispatchState((s) => ({
        ...s,
        phase: 'cancelled',
        errorMessage: null,
      }));
      return true;
    } catch (err) {
      if (abortRef.current?.signal?.aborted) return false;
      setDispatchState((s) => ({
        ...s,
        errorMessage: toMessage(err) || 'Failed to cancel trip',
      }));
      return false;
    } finally {
      isCancellingRef.current = false;
      setIsCancelling(false);
    }
  }, [dispatchState.phase, dispatchState.requestId, dispatchState.matchedDriverId]);

  // ── Reset ─────────────────────────────────────────────────────────────────

  const reset = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    activeRef.current = false;
    isCancellingRef.current = false;
    setIsCancelling(false);
    setDispatchState(IDLE_STATE);
  }, []);

  return { dispatchState, requestDispatch, cancelRide, isCancelling, reset };
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
  visibleDrivers?: SimulatedDriver[],
): Promise<void> {
  // Step 1: Register available drivers from the visible fleet (or fallback single demo driver)
  setState((s) => ({ ...s, phase: 'registering' }));

  const availableVisible = (visibleDrivers ?? []).filter((d) => d.status === 'available');

  try {
    if (availableVisible.length > 0) {
      // Register all visible available drivers with stable IDs and their actual coordinates
      await Promise.all(
        availableVisible.map((d) =>
          registerDriver(
            {
              driverId: d.id,
              latitude: d.lat,
              longitude: d.lon,
              action: 'REGISTER',
              capturedAt: Date.now(),
            },
            signal,
          ),
        ),
      );
    } else {
      const driverId = generateDriverId(place.name);
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
    }
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
    try {
      await delay(intervalMs, signal);
    } catch (_e) {
      // An AbortError here means the signal was fired during the sleep window
      // (reset, place change, or unmount). Terminate the flow quietly.
      if (signal.aborted) return;
      // Any other rejection is unexpected; re-throw so the unhandled-rejection
      // handler can report it rather than silently swallowing it.
      throw _e;
    }
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
      // Phase 7E.2 — Extract and validate optional driver telemetry.
      // Telemetry represents the last-known fresh fix at status-query time.
      // Null telemetry does not invalidate the match.
      const rawLat = statusResponse.driverLatitude;
      const rawLon = statusResponse.driverLongitude;
      const rawAt = statusResponse.driverLocationAt;
      const validCoords = isValidDriverCoords(rawLat, rawLon);
      setState({
        phase: 'matched',
        requestId,
        matchedDriverId: statusResponse.driverId,
        matchedDriverLocation: validCoords
          ? { lat: rawLat as number, lon: rawLon as number }
          : null,
        matchedDriverLocationAt: validCoords && rawAt != null ? rawAt : null,
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
        matchedDriverLocationAt: null,
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
    matchedDriverLocationAt: null,
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
    const onAbort = () => {
      clearTimeout(timer);
      reject(new DOMException('Aborted', 'AbortError'));
    };
    // Use { once: true } so the listener self-removes after the first abort
    // event. The timer callback also removes it explicitly so no listener
    // remains on the signal after a normal (non-aborted) resolution.
    signal.addEventListener('abort', onAbort, { once: true });
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
  });
}

function toMessage(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}

/**
 * Phase 7E.2 — Validates that raw latitude and longitude values from the
 * backend status response are safe to render on the map.
 *
 * Accepts:
 *   - typeof number (not NaN, not Infinity)
 *   - lat ∈ [-90, 90], lon ∈ [-180, 180]
 *   - (0, 0) is explicitly permitted (Null Island is a valid coordinate)
 *
 * Rejects:
 *   - null or undefined
 *   - non-numeric types (string, boolean, object, etc.)
 *   - NaN, +Infinity, -Infinity
 *   - out-of-range geographic bounds
 */
export function isValidDriverCoords(
  lat: number | null | undefined,
  lon: number | null | undefined,
): boolean {
  if (lat == null || lon == null) return false;
  if (typeof lat !== 'number' || typeof lon !== 'number') return false;
  if (!isFinite(lat) || !isFinite(lon)) return false;
  if (lat < -90 || lat > 90) return false;
  if (lon < -180 || lon > 180) return false;
  return true;
}
