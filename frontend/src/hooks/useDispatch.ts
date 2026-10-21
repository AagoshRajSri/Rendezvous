import { useCallback, useEffect, useRef, useState } from 'react';
import type { PlaceSuggestion } from '../types/place';
import type { DispatchState, TripResponse } from '../types/dispatch';
import type { SimulatedDriver } from '../types/driver';
import {
  beginEnRoute,
  cancelTrip,
  completeTrip,
  fetchEligibleDrivers,
  getRequestStatus,
  getTripStatus,
  registerDriver,
  startTrip,
  submitRideRequest,
} from '../services/dispatch';

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
  /** Phase 11.3: Transitions backend trip to EN_ROUTE when driver approach begins. */
  beginEnRoute: () => Promise<boolean>;
  /** Phase 11.3: Start trip upon passenger boarding (calls POST /api/trips/{requestId}/start). */
  startRide: () => Promise<boolean>;
  /** Phase 11.3: Whether a start trip request is in flight. */
  isStarting: boolean;
  /** Phase 11.3: Complete trip upon reaching destination (calls POST /api/trips/{requestId}/complete). */
  completeRide: () => Promise<boolean>;
  /** Phase 11.3: Whether a complete trip request is in flight. */
  isCompleting: boolean;
  /** Phase 11.3: Marks driver arrival at pickup (frontend visual milestone). */
  markArrived: () => void;
  /** Phase 11.3: Authoritatively queries backend trip status to reconcile state. */
  refreshTripStatus: () => Promise<TripResponse | null>;
  /** Phase 11.2: Cancels the ride prior to passenger pickup (permitted in matched, en_route, arrived). */
  cancelRide: () => Promise<boolean>;
  /** Phase 11.2: Whether a cancellation request is currently in flight. */
  isCancelling: boolean;
  /** Resets to idle (e.g., after place change, on user cancel, book another ride). */
  reset: () => void;
}

/**
 * Phase 11.3 - Frontend dispatch lifecycle hook with full trip progression.
 *
 * Orchestrates:
 *   1. Register available drivers from visible fleet (POST /api/register).
 *   2. Submit ride request (POST /api/request → 202).
 *   3. Poll GET /api/requests/{id} until MATCHED or NO_DRIVERS.
 *   4. Advance to EN_ROUTE via POST /api/trips/{requestId}/en-route when approach begins.
 *   5. Driver arrival marks visual arrival at pickup (phase: arrived).
 *   6. Passenger boards and rider starts trip via POST /api/trips/{requestId}/start (phase: in_progress).
 *   7. Trip completes via POST /api/trips/{requestId}/complete (phase: completed).
 *   8. Supports pre-pickup cancellation (ASSIGNED, EN_ROUTE) while locking out post-boarding cancellation.
 *   9. Protects against duplicate concurrent actions via synchronous ref guards.
 *  10. Reconciles state against GET /api/trips/{requestId} on uncertain outcomes (e.g., 503).
 */
export function useDispatch(
  selectedPlace: PlaceSuggestion | null,
  visibleDrivers?: SimulatedDriver[],
): UseDispatchReturn {
  const [dispatchState, setDispatchState] = useState<DispatchState>(IDLE_STATE);
  const [isCancelling, setIsCancelling] = useState(false);
  const [isStarting, setIsStarting] = useState(false);
  const [isCompleting, setIsCompleting] = useState(false);

  // Ref to the current AbortController so we can cancel on place change / reset / unmount
  const abortRef = useRef<AbortController | null>(null);

  // Track whether a dispatch flow is active to prevent duplicate submissions
  const activeRef = useRef(false);
  // Track whether action requests are in-flight to prevent duplicate clicks
  const isCancellingRef = useRef(false);
  const isStartingRef = useRef(false);
  const isCompletingRef = useRef(false);
  // Track whether en-route transition has been initiated for the active trip
  const enRouteTripIdRef = useRef<string | null>(null);

  const visibleDriversRef = useRef(visibleDrivers);
  visibleDriversRef.current = visibleDrivers;

  // ── Reset when selected place changes ─────────────────────────────────────

  const selectedPlaceId = selectedPlace?.id;

  useEffect(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    activeRef.current = false;
    isCancellingRef.current = false;
    isStartingRef.current = false;
    isCompletingRef.current = false;
    enRouteTripIdRef.current = null;
    setIsCancelling(false);
    setIsStarting(false);
    setIsCompleting(false);
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

      const isAbort = err instanceof DOMException && err.name === 'AbortError';
      if (isAbort) {
        return;
      }

      console.error('Unhandled error in dispatch flow:', err);

      if (isCurrent && !signal.aborted) {
        setDispatchState({
          ...IDLE_STATE,
          phase: 'error',
          errorMessage: toMessage(err) || 'Unexpected dispatch error',
        });
      }
    });
  }, [selectedPlace]);

  // ── Automatic transition to EN_ROUTE when driver approach begins ───────────

  useEffect(() => {
    if (
      dispatchState.phase === 'matched' &&
      dispatchState.requestId &&
      dispatchState.matchedDriverId &&
      enRouteTripIdRef.current !== dispatchState.requestId
    ) {
      const reqId = dispatchState.requestId;
      const drvId = dispatchState.matchedDriverId;
      enRouteTripIdRef.current = reqId;

      beginEnRoute(reqId, drvId, abortRef.current?.signal)
        .then(() => {
          setDispatchState((s) => (s.requestId === reqId ? { ...s, phase: 'en_route', errorMessage: null } : s));
        })
        .catch(async (err) => {
          if (abortRef.current?.signal?.aborted) return;
          // Reconcile status on uncertain error (e.g. 503)
          try {
            const trip = await getTripStatus(reqId, abortRef.current?.signal);
            if (trip?.status === 'EN_ROUTE') {
              setDispatchState((s) => (s.requestId === reqId ? { ...s, phase: 'en_route', errorMessage: null } : s));
              return;
            }
          } catch {
            // ignore reconciliation fetch failure
          }
          setDispatchState((s) => (s.requestId === reqId ? { ...s, errorMessage: toMessage(err) || 'Failed to start driver approach' } : s));
        });
    }
  }, [dispatchState.phase, dispatchState.requestId, dispatchState.matchedDriverId]);

  // ── Manual beginEnRoute action (for retries after failure) ─────────────────

  const handleBeginEnRoute = useCallback(async (): Promise<boolean> => {
    if (
      dispatchState.phase !== 'matched' ||
      !dispatchState.requestId ||
      !dispatchState.matchedDriverId
    ) {
      return false;
    }
    const reqId = dispatchState.requestId;
    const drvId = dispatchState.matchedDriverId;

    try {
      await beginEnRoute(reqId, drvId, abortRef.current?.signal);
      setDispatchState((s) => (s.requestId === reqId ? { ...s, phase: 'en_route', errorMessage: null } : s));
      return true;
    } catch (err) {
      if (abortRef.current?.signal?.aborted) return false;
      let reconciled = false;
      try {
        const trip = await getTripStatus(reqId, abortRef.current?.signal);
        if (trip?.status === 'EN_ROUTE') {
          setDispatchState((s) => (s.requestId === reqId ? { ...s, phase: 'en_route', errorMessage: null } : s));
          reconciled = true;
        }
      } catch {
        // ignore
      }
      if (!reconciled) {
        setDispatchState((s) => (s.requestId === reqId ? { ...s, errorMessage: toMessage(err) || 'Failed to start driver approach' } : s));
      }
      return reconciled;
    }
  }, [dispatchState.phase, dispatchState.requestId, dispatchState.matchedDriverId]);

  // ── Mark arrived at pickup (frontend visual milestone) ────────────────────

  const markArrived = useCallback(() => {
    setDispatchState((s) => {
      if (s.phase === 'en_route' || s.phase === 'matched') {
        return { ...s, phase: 'arrived' };
      }
      return s;
    });
  }, []);

  // ── Start trip (passenger boarding) ───────────────────────────────────────

  const startRide = useCallback(async (): Promise<boolean> => {
    if (
      isStartingRef.current ||
      (dispatchState.phase !== 'arrived' && dispatchState.phase !== 'en_route') ||
      !dispatchState.requestId ||
      !dispatchState.matchedDriverId
    ) {
      return false;
    }

    const requestId = dispatchState.requestId;
    const driverId = dispatchState.matchedDriverId;

    isStartingRef.current = true;
    setIsStarting(true);

    try {
      await startTrip(requestId, driverId, abortRef.current?.signal);
      setDispatchState((s) => {
        if (s.requestId !== requestId) return s;
        return {
          ...s,
          phase: 'in_progress',
          errorMessage: null,
        };
      });
      return true;
    } catch (err) {
      if (abortRef.current?.signal?.aborted) return false;
      let reconciled = false;
      try {
        const trip = await getTripStatus(requestId, abortRef.current?.signal);
        if (trip?.status === 'IN_PROGRESS') {
          setDispatchState((s) => (s.requestId === requestId ? { ...s, phase: 'in_progress', errorMessage: null } : s));
          reconciled = true;
        }
      } catch {
        // ignore
      }
      if (!reconciled) {
        setDispatchState((s) => (s.requestId === requestId ? { ...s, errorMessage: toMessage(err) || 'Failed to start trip' } : s));
      }
      return reconciled;
    } finally {
      isStartingRef.current = false;
      setIsStarting(false);
    }
  }, [dispatchState.phase, dispatchState.requestId, dispatchState.matchedDriverId]);

  // ── Complete trip ─────────────────────────────────────────────────────────

  const completeRide = useCallback(async (): Promise<boolean> => {
    if (
      isCompletingRef.current ||
      dispatchState.phase !== 'in_progress' ||
      !dispatchState.requestId ||
      !dispatchState.matchedDriverId
    ) {
      return false;
    }

    const requestId = dispatchState.requestId;
    const driverId = dispatchState.matchedDriverId;

    isCompletingRef.current = true;
    setIsCompleting(true);

    try {
      await completeTrip(requestId, driverId, abortRef.current?.signal);
      setDispatchState((s) => {
        if (s.requestId !== requestId) return s;
        return {
          ...s,
          phase: 'completed',
          errorMessage: null,
        };
      });
      return true;
    } catch (err) {
      if (abortRef.current?.signal?.aborted) return false;
      let reconciled = false;
      try {
        const trip = await getTripStatus(requestId, abortRef.current?.signal);
        if (trip?.status === 'COMPLETED') {
          setDispatchState((s) => (s.requestId === requestId ? { ...s, phase: 'completed', errorMessage: null } : s));
          reconciled = true;
        }
      } catch {
        // ignore
      }
      if (!reconciled) {
        setDispatchState((s) => (s.requestId === requestId ? { ...s, errorMessage: toMessage(err) || 'Failed to complete trip' } : s));
      }
      return reconciled;
    } finally {
      isCompletingRef.current = false;
      setIsCompleting(false);
    }
  }, [dispatchState.phase, dispatchState.requestId, dispatchState.matchedDriverId]);

  // ── Trip cancellation (Phase 11.2 & 11.3) ─────────────────────────────────

  const cancelRide = useCallback(async (): Promise<boolean> => {
    // Strictly disallowed if trip is in_progress, completed, or already cancelling
    if (
      isCancellingRef.current ||
      (dispatchState.phase !== 'matched' &&
        dispatchState.phase !== 'en_route' &&
        dispatchState.phase !== 'arrived') ||
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
      setDispatchState((s) => (s.requestId === requestId ? {
        ...s,
        phase: 'cancelled',
        errorMessage: null,
      } : s));
      return true;
    } catch (err) {
      if (abortRef.current?.signal?.aborted) return false;
      let reconciled = false;
      try {
        const trip = await getTripStatus(requestId, abortRef.current?.signal);
        if (trip?.status === 'CANCELLED') {
          setDispatchState((s) => (s.requestId === requestId ? { ...s, phase: 'cancelled', errorMessage: null } : s));
          reconciled = true;
        }
      } catch {
        // ignore
      }
      if (!reconciled) {
        setDispatchState((s) => (s.requestId === requestId ? {
          ...s,
          errorMessage: toMessage(err) || 'Failed to cancel trip',
        } : s));
      }
      return reconciled;
    } finally {
      isCancellingRef.current = false;
      setIsCancelling(false);
    }
  }, [dispatchState.phase, dispatchState.requestId, dispatchState.matchedDriverId]);

  // ── Authoritative trip status check / reconciliation ──────────────────────

  const refreshTripStatus = useCallback(async (): Promise<TripResponse | null> => {
    if (!dispatchState.requestId) return null;
    try {
      const trip = await getTripStatus(dispatchState.requestId, abortRef.current?.signal);
      if (trip && trip.requestId === dispatchState.requestId) {
        if (trip.status === 'COMPLETED' && dispatchState.phase !== 'completed') {
          setDispatchState((s) => (s.requestId === trip.requestId ? { ...s, phase: 'completed', errorMessage: null } : s));
        } else if (trip.status === 'IN_PROGRESS' && dispatchState.phase !== 'in_progress') {
          setDispatchState((s) => (s.requestId === trip.requestId ? { ...s, phase: 'in_progress', errorMessage: null } : s));
        } else if (trip.status === 'CANCELLED' && dispatchState.phase !== 'cancelled') {
          setDispatchState((s) => (s.requestId === trip.requestId ? { ...s, phase: 'cancelled', errorMessage: null } : s));
        } else if (trip.status === 'EN_ROUTE' && dispatchState.phase === 'matched') {
          setDispatchState((s) => (s.requestId === trip.requestId ? { ...s, phase: 'en_route', errorMessage: null } : s));
        }
      }
      return trip;
    } catch {
      return null;
    }
  }, [dispatchState.requestId, dispatchState.phase]);

  // ── Reset ─────────────────────────────────────────────────────────────────

  const reset = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    activeRef.current = false;
    isCancellingRef.current = false;
    isStartingRef.current = false;
    isCompletingRef.current = false;
    enRouteTripIdRef.current = null;
    setIsCancelling(false);
    setIsStarting(false);
    setIsCompleting(false);
    setDispatchState(IDLE_STATE);
  }, []);

  return {
    dispatchState,
    requestDispatch,
    beginEnRoute: handleBeginEnRoute,
    startRide,
    isStarting,
    completeRide,
    isCompleting,
    markArrived,
    refreshTripStatus,
    cancelRide,
    isCancelling,
    reset,
  };
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
  const sessionNonce = Math.random().toString(36).slice(2, 7);
  const registeredDriverIds = new Set<string>();

  try {
    if (availableVisible.length > 0) {
      // Register all visible available drivers with session-fresh unique IDs to guarantee freshness
      // in DriverRegistry and prevent duplicate REGISTER drops.
      await Promise.all(
        availableVisible.map((d) => {
          const freshId = `${d.id}-${sessionNonce}`;
          registeredDriverIds.add(freshId);
          return registerDriver(
            {
              driverId: freshId,
              latitude: d.lat,
              longitude: d.lon,
              action: 'REGISTER',
              capturedAt: Date.now(),
            },
            signal,
          );
        }),
      );
    } else {
      const driverId = generateDriverId(place.name);
      registeredDriverIds.add(driverId);
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

  // Wait briefly for Kafka DriverPingConsumer to process registrations into DriverRegistry.
  // This eliminates the consumer race condition where RideRequestConsumer runs matching
  // before DriverPingConsumer enters the drivers into in-memory storage.
  const pollStart = Date.now();
  const MAX_WAIT_MS = 2_000;
  while (!signal.aborted && Date.now() - pollStart < MAX_WAIT_MS) {
    try {
      const eligible = await fetchEligibleDrivers(signal);
      const hasRegisteredDriver = (eligible ?? []).some(
        (ed) => registeredDriverIds.has(ed.driverId) || ed.status === 'AVAILABLE',
      );
      if (hasRegisteredDriver) {
        break;
      }
    } catch {
      // If fetchEligibleDrivers fails transiently or isn't mocked in tests, proceed to submit
      break;
    }
    try {
      await delay(80, signal);
    } catch {
      if (signal.aborted) return;
      break;
    }
  }

  if (signal.aborted) return;

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
      // Phase 7E.2 - Extract and validate optional driver telemetry.
      // Telemetry represents the last-known fresh fix at status-query time.
      // Null telemetry does not invalidate the match.
      const rawLat = statusResponse.driverLatitude;
      const rawLon = statusResponse.driverLongitude;
      const rawAt = statusResponse.driverLocationAt;
      const validCoords = isValidDriverCoords(rawLat, rawLon);

      // If backend telemetry is null, fall back to looking up matched driver in visibleDrivers
      const localDriver = (!validCoords && statusResponse.driverId)
        ? visibleDrivers?.find((d) => d.id === statusResponse.driverId || statusResponse.driverId!.startsWith(d.id))
        : null;

      const finalLocation = validCoords
        ? { lat: rawLat as number, lon: rawLon as number }
        : localDriver
          ? { lat: localDriver.lat, lon: localDriver.lon }
          : null;

      setState({
        phase: 'matched',
        requestId,
        matchedDriverId: statusResponse.driverId,
        matchedDriverLocation: finalLocation,
        matchedDriverLocationAt: validCoords && rawAt != null ? rawAt : (localDriver ? new Date().toISOString() : null),
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
 * Phase 7E.2 - Validates that raw latitude and longitude values from the
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
