/**
 * Phase 7E.2 — Unit tests for the useDispatch hook.
 *
 * Uses vi.mock to replace the dispatch service so no real HTTP requests are made.
 * Tests cover phase transitions, duplicate-submission prevention, abort on place
 * change, no_drivers terminal state, error handling, reset behaviour, and
 * Phase 7E.2 driver coordinate validation and telemetry propagation.
 */

import { act, renderHook } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useDispatch, isValidDriverCoords } from './useDispatch';
import type { PlaceSuggestion } from '../types/place';

// ── Mock the dispatch service ─────────────────────────────────────────────────

vi.mock('../services/dispatch', () => ({
  registerDriver: vi.fn(),
  submitRideRequest: vi.fn(),
  getRequestStatus: vi.fn(),
  cancelTrip: vi.fn(),
  beginEnRoute: vi.fn(),
  startTrip: vi.fn(),
  completeTrip: vi.fn(),
  getTripStatus: vi.fn(),
  fetchEligibleDrivers: vi.fn(),
}));

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

// ── Test helpers ──────────────────────────────────────────────────────────────

function makePlaceSuggestion(name = 'London'): PlaceSuggestion {
  return {
    id: `place-${name.toLowerCase()}`,
    name,
    subtitle: 'United Kingdom',
    type: 'city',
    lat: 51.5074,
    lon: -0.1278,
    bbox: [-0.5103, 51.2867, 0.3340, 51.6918],
  };
}

/** Advance fake timers far enough to flush all delays in the hook. */
async function drainHook(): Promise<void> {
  // Advance well beyond POLL_MAX_MS * POLL_MAX_ATTEMPTS + registration delay
  await act(async () => {
    await vi.advanceTimersByTimeAsync(300_000);
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ shouldAdvanceTime: false });
  vi.mocked(beginEnRoute).mockResolvedValue({
    requestId: 'req-default',
    driverId: 'drv-default',
    status: 'EN_ROUTE',
    outcome: 'TRANSITIONED',
    message: 'OK',
  });
  vi.mocked(fetchEligibleDrivers).mockResolvedValue([
    {
      driverId: 'd-mock',
      latitude: 51.5,
      longitude: -0.1,
      status: 'AVAILABLE',
      updatedAt: new Date().toISOString(),
    },
  ]);
});

afterEach(() => {
  vi.useRealTimers();
});

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('useDispatch — initial state', () => {
  it('starts in idle phase with null values', () => {
    const { result } = renderHook(() => useDispatch(null));
    expect(result.current.dispatchState.phase).toBe('idle');
    expect(result.current.dispatchState.requestId).toBeNull();
    expect(result.current.dispatchState.matchedDriverId).toBeNull();
    expect(result.current.dispatchState.matchedDriverLocation).toBeNull();
    expect(result.current.dispatchState.errorMessage).toBeNull();
  });

  it('is idle even when a place is selected (no dispatch triggered)', () => {
    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    expect(result.current.dispatchState.phase).toBe('idle');
  });
});

describe('useDispatch — happy path: MATCHED', () => {
  it('transitions through registering → submitting → polling → matched → en_route', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'DRIVER-1', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-1', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-1',
      status: 'MATCHED',
      driverId: 'DRIVER-42',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
    });

    const place = makePlaceSuggestion();
    const { result } = renderHook(() => useDispatch(place));

    act(() => { result.current.requestDispatch(); });
    expect(result.current.dispatchState.phase).toBe('registering');

    await drainHook();

    expect(result.current.dispatchState.phase).toBe('en_route');
    expect(result.current.dispatchState.matchedDriverId).toBe('DRIVER-42');
    // No telemetry in this response — coordinates remain null
    expect(result.current.dispatchState.matchedDriverLocation).toBeNull();
    expect(result.current.dispatchState.matchedDriverLocationAt).toBeNull();
    expect(result.current.dispatchState.errorMessage).toBeNull();
  });

  it('populates matchedDriverLocation and matchedDriverLocationAt when backend supplies valid telemetry', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'DRIVER-1', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-2', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-2',
      status: 'MATCHED',
      driverId: 'DRIVER-42',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: 48.8566,
      driverLongitude: 2.3522,
      driverLocationAt: '2026-01-01T00:00:55Z',
    });

    const place = makePlaceSuggestion();
    const { result } = renderHook(() => useDispatch(place));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('en_route');
    expect(result.current.dispatchState.matchedDriverLocation).toEqual({ lat: 48.8566, lon: 2.3522 });
    expect(result.current.dispatchState.matchedDriverLocationAt).toBe('2026-01-01T00:00:55Z');
  });

  it('accepts (0, 0) as a valid coordinate pair (Null Island)', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-3', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-3',
      status: 'MATCHED',
      driverId: 'DRIVER-NULL-ISLAND',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: 0,
      driverLongitude: 0,
      driverLocationAt: '2026-01-01T00:00:50Z',
    });

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.matchedDriverLocation).toEqual({ lat: 0, lon: 0 });
  });

  it('leaves matchedDriverLocation null when telemetry coordinates are out of WGS-84 range', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-4', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-4',
      status: 'MATCHED',
      driverId: 'DRIVER-BAD',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: 91,   // out of range
      driverLongitude: 0,
      driverLocationAt: '2026-01-01T00:00:50Z',
    });

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('en_route');
    expect(result.current.dispatchState.matchedDriverLocation).toBeNull();
    expect(result.current.dispatchState.matchedDriverLocationAt).toBeNull();
  });

  it('leaves matchedDriverLocation null when telemetry is null (match still valid)', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-5', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-5',
      status: 'MATCHED',
      driverId: 'DRIVER-NO-GPS',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: null,
      driverLongitude: null,
      driverLocationAt: null,
    });

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('en_route');
    expect(result.current.dispatchState.matchedDriverId).toBe('DRIVER-NO-GPS');
    expect(result.current.dispatchState.matchedDriverLocation).toBeNull();
    expect(result.current.dispatchState.matchedDriverLocationAt).toBeNull();
  });
});

describe('useDispatch — NO_DRIVERS terminal state', () => {
  it('transitions to no_drivers when backend returns NO_DRIVERS', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'DRIVER-X', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-nd', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-nd',
      status: 'NO_DRIVERS',
      driverId: null,
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
    });

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('no_drivers');
    expect(result.current.dispatchState.matchedDriverId).toBeNull();
    expect(result.current.dispatchState.matchedDriverLocation).toBeNull();
  });
});

describe('useDispatch — registration failure', () => {
  it('transitions to error when registerDriver throws', async () => {
    vi.mocked(registerDriver).mockRejectedValue(new Error('Broker unavailable'));

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('error');
    expect(result.current.dispatchState.errorMessage).toContain('Broker unavailable');
  });
});

describe('useDispatch — submission failure', () => {
  it('transitions to error when submitRideRequest throws', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D', message: 'ok' });
    vi.mocked(submitRideRequest).mockRejectedValue(new Error('requestId must not be blank'));

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('error');
    expect(result.current.dispatchState.errorMessage).toContain('requestId must not be blank');
  });
});

describe('useDispatch — duplicate submission prevention', () => {
  it('ignores a second requestDispatch() call while in-flight', () => {
    // Registration returns a promise that never resolves (flow stays in-flight)
    vi.mocked(registerDriver).mockReturnValue(new Promise(() => {}));

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));

    act(() => { result.current.requestDispatch(); });
    // First call: flow is in-flight (registering)
    expect(result.current.dispatchState.phase).toBe('registering');

    act(() => { result.current.requestDispatch(); }); // duplicate — must be ignored

    // registerDriver must have been called exactly once
    expect(vi.mocked(registerDriver)).toHaveBeenCalledTimes(1);
  });
});

describe('useDispatch — reset', () => {
  it('returns to idle after reset()', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-r', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-r',
      status: 'NO_DRIVERS',
      driverId: null,
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
    });

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('no_drivers');

    act(() => { result.current.reset(); });

    expect(result.current.dispatchState.phase).toBe('idle');
    expect(result.current.dispatchState.requestId).toBeNull();
  });
});

describe('useDispatch — place change cancels in-flight flow', () => {
  it('resets to idle when selectedPlace changes during in-flight registration', async () => {
    // Registration never resolves
    vi.mocked(registerDriver).mockReturnValue(new Promise(() => {}));

    let place: PlaceSuggestion | null = makePlaceSuggestion('London');
    const { result, rerender } = renderHook(({ p }) => useDispatch(p), {
      initialProps: { p: place },
    });

    act(() => { result.current.requestDispatch(); });
    expect(result.current.dispatchState.phase).toBe('registering');

    // Change the selected place — hook should abort and reset
    place = makePlaceSuggestion('Paris');
    await act(async () => { rerender({ p: place }); });

    expect(result.current.dispatchState.phase).toBe('idle');
  });
});

// ── Abort / cancellation tests (Phase 7E.3) ───────────────────────────────────

describe('useDispatch — abort during polling delay terminates cleanly', () => {
  it('returns to idle without unhandled rejection when reset fires while flow is sleeping between poll attempts', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D-ABORT', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-abort', message: 'ok' });
    // getRequestStatus resolves only after timer advances — keep it pending via PENDING status
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-abort',
      status: 'PENDING',
      driverId: null,
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:01Z',
    });

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });

    // Advance past registration + submission + first poll interval, but less than second poll
    await act(async () => { await vi.advanceTimersByTimeAsync(2_500); });
    expect(result.current.dispatchState.phase).toBe('polling');

    // Reset while delay is in progress (flow is sleeping between poll attempts)
    act(() => { result.current.reset(); });

    // Confirm clean termination — no unhandled rejection, state is idle
    expect(result.current.dispatchState.phase).toBe('idle');
  });

  it('terminates cleanly when unmount fires while the polling delay is pending', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D-UNMOUNT', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-unmount', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-unmount',
      status: 'PENDING',
      driverId: null,
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:01Z',
    });

    const { result, unmount } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });

    await act(async () => { await vi.advanceTimersByTimeAsync(2_500); });
    expect(result.current.dispatchState.phase).toBe('polling');

    // Unmount the component — the cleanup effect calls abortRef.current?.abort()
    // The dangling abort listener must NOT produce an unhandled rejection.
    expect(() => act(() => { unmount(); })).not.toThrow();
  });

  it('does not propagate AbortError when place changes while delay is sleeping', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D-PLCHG', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-plchg', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-plchg',
      status: 'PENDING',
      driverId: null,
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:01Z',
    });

    let place: PlaceSuggestion | null = makePlaceSuggestion('London');
    const { result, rerender } = renderHook(({ p }) => useDispatch(p), {
      initialProps: { p: place },
    });

    act(() => { result.current.requestDispatch(); });
    await act(async () => { await vi.advanceTimersByTimeAsync(2_500); });
    expect(result.current.dispatchState.phase).toBe('polling');

    // Change place mid-polling delay — abort fires during the sleep window
    place = makePlaceSuggestion('Tokyo');
    await act(async () => { rerender({ p: place }); });

    expect(result.current.dispatchState.phase).toBe('idle');
  });

  it('abort listener is cleaned up after the timer resolves normally so a subsequent abort has no effect', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D-CLEAN', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-clean', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-clean',
      status: 'MATCHED',
      driverId: 'D-CLEAN',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: 51.5074,
      driverLongitude: -0.1278,
      driverLocationAt: '2026-01-01T00:00:58Z',
    });

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });

    // Run to completion — timer resolves normally, flow reaches EN_ROUTE
    await drainHook();
    expect(result.current.dispatchState.phase).toBe('en_route');

    // Subsequent reset (abort after timer already resolved) must not throw
    expect(() => act(() => { result.current.reset(); })).not.toThrow();
    expect(result.current.dispatchState.phase).toBe('idle');
  });

  it('unexpected rejections surface as error phase, log to console, and allow subsequent dispatch attempts', async () => {
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D-UNEXPECTED', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-unexpected', message: 'ok' });
    // Simulate an unexpected runtime failure inside the polling cycle:
    // getRequestStatus returns an object whose status property getter throws an unexpected error
    const unexpectedError = new Error('Corrupted status response payload');
    vi.mocked(getRequestStatus).mockResolvedValue({
      get status() {
        throw unexpectedError;
      },
    } as any);

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });

    // Drain hook to advance past registration, submission, and first poll attempt
    await drainHook();

    // 1. Error state must be surfaced
    expect(result.current.dispatchState.phase).toBe('error');
    expect(result.current.dispatchState.errorMessage).toBe('Corrupted status response payload');

    // 2. Diagnostic logging occurred
    expect(consoleErrorSpy).toHaveBeenCalledWith(
      'Unhandled error in dispatch flow:',
      unexpectedError
    );

    // 3. activeRef must be released so a subsequent dispatch can be initiated
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-retry',
      status: 'MATCHED',
      driverId: 'D-RETRY',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: 51.5074,
      driverLongitude: -0.1278,
      driverLocationAt: '2026-01-01T00:00:58Z',
    });

    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('en_route');
    expect(result.current.dispatchState.matchedDriverId).toBe('D-RETRY');

    consoleErrorSpy.mockRestore();
  });

  it('stale dispatch does not release active lock belonging to a newer dispatch', async () => {
    let resolveFirstRegister!: (val: any) => void;
    const firstRegisterPromise = new Promise((resolve) => {
      resolveFirstRegister = resolve;
    });

    // First dispatch hangs on registerDriver
    vi.mocked(registerDriver).mockReturnValueOnce(firstRegisterPromise as any);

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));

    // 1. Start Dispatch 1
    act(() => { result.current.requestDispatch(); });
    expect(result.current.dispatchState.phase).toBe('registering');

    // 2. Reset Dispatch 1
    act(() => { result.current.reset(); });
    expect(result.current.dispatchState.phase).toBe('idle');

    // 3. Start Dispatch 2 (second dispatch hangs on registerDriver as well)
    let resolveSecondRegister!: (val: any) => void;
    const secondRegisterPromise = new Promise((resolve) => {
      resolveSecondRegister = resolve;
    });
    vi.mocked(registerDriver).mockReturnValueOnce(secondRegisterPromise as any);

    act(() => { result.current.requestDispatch(); });
    expect(result.current.dispatchState.phase).toBe('registering');

    // 4. Dispatch 1 now finishes/resolves after being superseded
    await act(async () => {
      resolveFirstRegister({ driverId: 'D-STALE', message: 'stale' });
    });

    // 5. Stale dispatch must NOT have released the active lock for Dispatch 2!
    // A duplicate requestDispatch call while Dispatch 2 is running must be guarded
    act(() => { result.current.requestDispatch(); });
    expect(registerDriver).toHaveBeenCalledTimes(2); // Still only 2 calls, not 3

    // 6. Now let Dispatch 2 proceed and finish
    vi.mocked(submitRideRequest).mockResolvedValueOnce({ requestId: 'req-2', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValueOnce({
      requestId: 'req-2',
      status: 'MATCHED',
      driverId: 'D-NEW',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
    });

    await act(async () => {
      resolveSecondRegister({ driverId: 'D-NEW', message: 'ok' });
    });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('en_route');
    expect(result.current.dispatchState.matchedDriverId).toBe('D-NEW');
  });

  it('unexpected error is logged even if signal.aborted is true when the handler runs', async () => {
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D-ERR-ABORT', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-err-abort', message: 'ok' });

    const unexpectedErr = new Error('Corrupted status getter');
    vi.mocked(getRequestStatus).mockResolvedValue({
      get status() {
        throw unexpectedErr;
      },
    } as any);

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });

    // Advance to polling delay
    await act(async () => { await vi.advanceTimersByTimeAsync(1500); });

    // Reset while the request is in flight
    act(() => { result.current.reset(); });

    // Drain any pending microtasks so the rejection handler runs with signal.aborted === true
    await drainHook();

    // The unexpected error MUST be logged despite signal.aborted being true
    expect(consoleErrorSpy).toHaveBeenCalledWith(
      'Unhandled error in dispatch flow:',
      unexpectedErr
    );

    // Stale aborted flow must NOT overwrite state (remains idle from reset)
    expect(result.current.dispatchState.phase).toBe('idle');

    consoleErrorSpy.mockRestore();
  });
});

// ── isValidDriverCoords unit tests (Phase 7E.2) ───────────────────────────────────

describe('useDispatch — fleet synchronization (Phase 11.2)', () => {
  it('registers all available visible drivers with their exact IDs and coordinates', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'd-1', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-sync', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-sync',
      status: 'MATCHED',
      driverId: 'place-sim-1',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: 51.51,
      driverLongitude: -0.12,
    });

    const visibleDrivers = [
      { id: 'place-sim-1', lat: 51.51, lon: -0.12, status: 'available' as const },
      { id: 'place-sim-2', lat: 51.52, lon: -0.13, status: 'available' as const },
      { id: 'place-sim-3', lat: 51.53, lon: -0.14, status: 'busy' as const },
    ];

    const place = makePlaceSuggestion();
    const { result } = renderHook(() => useDispatch(place, visibleDrivers));

    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('en_route');
    expect(result.current.dispatchState.matchedDriverId).toBe('place-sim-1');

    // Only available drivers (sim-1 and sim-2) registered, not busy (sim-3)
    expect(registerDriver).toHaveBeenCalledTimes(2);
    expect(registerDriver).toHaveBeenCalledWith(
      expect.objectContaining({ driverId: expect.stringMatching(/^place-sim-1/), latitude: 51.51, longitude: -0.12 }),
      expect.any(Object),
    );
    expect(registerDriver).toHaveBeenCalledWith(
      expect.objectContaining({ driverId: expect.stringMatching(/^place-sim-2/), latitude: 51.52, longitude: -0.13 }),
      expect.any(Object),
    );
  });
});

describe('useDispatch — cancelRide (Phase 11.2)', () => {
  it('calls cancelTrip and transitions phase to cancelled', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-cancel', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-cancel',
      status: 'MATCHED',
      driverId: 'D-CANCEL',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
    });
    vi.mocked(cancelTrip).mockResolvedValue({
      requestId: 'req-cancel',
      driverId: 'D-CANCEL',
      status: 'CANCELLED',
      outcome: 'TRANSITIONED',
      message: 'Trip cancelled',
    });

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('en_route');

    let success = false;
    await act(async () => {
      success = await result.current.cancelRide();
    });

    expect(success).toBe(true);
    expect(cancelTrip).toHaveBeenCalledWith(
      expect.stringMatching(/^req-/),
      'D-CANCEL',
      expect.any(Object),
    );
    expect(result.current.dispatchState.phase).toBe('cancelled');
  });

  it('handles cancellation error gracefully', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-cancel-err', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-cancel-err',
      status: 'MATCHED',
      driverId: 'D-CANCEL-ERR',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
    });
    vi.mocked(cancelTrip).mockRejectedValue(new Error('Trip already in progress'));

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    let success = true;
    await act(async () => {
      success = await result.current.cancelRide();
    });

    expect(success).toBe(false);
    expect(result.current.dispatchState.errorMessage).toBe('Trip already in progress');
  });

  it('prevents duplicate cancellation requests while in-flight and exposes isCancelling', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-cancel-dup', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-cancel-dup',
      status: 'MATCHED',
      driverId: 'D-CANCEL-DUP',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
    });

    let resolveCancel!: (res: any) => void;
    vi.mocked(cancelTrip).mockReturnValue(
      new Promise((resolve) => {
        resolveCancel = resolve;
      }),
    );

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('en_route');
    expect(result.current.isCancelling).toBe(false);

    let promise1!: Promise<boolean>;
    act(() => {
      promise1 = result.current.cancelRide();
    });

    expect(result.current.isCancelling).toBe(true);
    expect(cancelTrip).toHaveBeenCalledTimes(1);

    let promise2Result = true;
    await act(async () => {
      promise2Result = await result.current.cancelRide();
    });

    expect(promise2Result).toBe(false);
    expect(cancelTrip).toHaveBeenCalledTimes(1);

    await act(async () => {
      resolveCancel({
        requestId: 'req-cancel-dup',
        driverId: 'D-CANCEL-DUP',
        status: 'CANCELLED',
        outcome: 'TRANSITIONED',
        message: 'Trip cancelled',
      });
      await promise1;
    });

    expect(result.current.isCancelling).toBe(false);
    expect(result.current.dispatchState.phase).toBe('cancelled');
  });
});

describe('isValidDriverCoords', () => {
  it('accepts valid WGS-84 coordinates', () => {
    expect(isValidDriverCoords(48.8566, 2.3522)).toBe(true);
    expect(isValidDriverCoords(-33.8688, 151.2093)).toBe(true);
    expect(isValidDriverCoords(90, 180)).toBe(true);
    expect(isValidDriverCoords(-90, -180)).toBe(true);
  });

  it('accepts (0, 0) as Null Island', () => {
    expect(isValidDriverCoords(0, 0)).toBe(true);
  });

  it('rejects null values', () => {
    expect(isValidDriverCoords(null, 2.3522)).toBe(false);
    expect(isValidDriverCoords(48.8566, null)).toBe(false);
    expect(isValidDriverCoords(null, null)).toBe(false);
  });

  it('rejects undefined values', () => {
    expect(isValidDriverCoords(undefined, 0)).toBe(false);
    expect(isValidDriverCoords(0, undefined)).toBe(false);
  });

  it('rejects NaN', () => {
    expect(isValidDriverCoords(NaN, 0)).toBe(false);
    expect(isValidDriverCoords(0, NaN)).toBe(false);
  });

  it('rejects Infinity', () => {
    expect(isValidDriverCoords(Infinity, 0)).toBe(false);
    expect(isValidDriverCoords(0, -Infinity)).toBe(false);
  });

  it('rejects latitude outside [-90, 90]', () => {
    expect(isValidDriverCoords(90.0001, 0)).toBe(false);
    expect(isValidDriverCoords(-90.0001, 0)).toBe(false);
  });

  it('rejects longitude outside [-180, 180]', () => {
    expect(isValidDriverCoords(0, 180.0001)).toBe(false);
    expect(isValidDriverCoords(0, -180.0001)).toBe(false);
  });
});

// ── Phase 11.3: End-to-End Trip Lifecycle ────────────────────────────────────

describe('useDispatch — Phase 11.3 Trip Lifecycle', () => {
  const setupMatchedTrip = async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D1', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-11-3', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-11-3',
      status: 'MATCHED',
      driverId: 'D1',
      receivedAt: '2026-10-18T10:00:00Z',
      updatedAt: '2026-10-18T10:01:00Z',
    });
    vi.mocked(beginEnRoute).mockResolvedValue({
      requestId: 'req-11-3',
      driverId: 'D1',
      status: 'EN_ROUTE',
      outcome: 'TRANSITIONED',
      message: 'OK',
    });

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();
    return result;
  };

  it('progresses end-to-end: matched → en_route → arrived → in_progress → completed', async () => {
    vi.mocked(startTrip).mockResolvedValue({
      requestId: 'req-11-3',
      driverId: 'D1',
      status: 'IN_PROGRESS',
      outcome: 'TRANSITIONED',
      message: 'Started',
    });
    vi.mocked(completeTrip).mockResolvedValue({
      requestId: 'req-11-3',
      driverId: 'D1',
      status: 'COMPLETED',
      outcome: 'TRANSITIONED',
      message: 'Completed',
    });

    const result = await setupMatchedTrip();
    expect(result.current.dispatchState.phase).toBe('en_route');
    expect(beginEnRoute).toHaveBeenCalledWith(expect.stringMatching(/^req-/), 'D1', expect.any(Object));

    // Vehicle reaches pickup → mark arrived
    act(() => {
      result.current.markArrived();
    });
    expect(result.current.dispatchState.phase).toBe('arrived');

    // Passenger boards → rider starts trip
    let startSuccess = false;
    await act(async () => {
      startSuccess = await result.current.startRide();
    });
    expect(startSuccess).toBe(true);
    expect(startTrip).toHaveBeenCalledWith(expect.stringMatching(/^req-/), 'D1', expect.any(Object));
    expect(result.current.dispatchState.phase).toBe('in_progress');

    // Vehicle reaches destination → complete trip
    let completeSuccess = false;
    await act(async () => {
      completeSuccess = await result.current.completeRide();
    });
    expect(completeSuccess).toBe(true);
    expect(completeTrip).toHaveBeenCalledWith(expect.stringMatching(/^req-/), 'D1', expect.any(Object));
    expect(result.current.dispatchState.phase).toBe('completed');
  });

  it('keeps phase as matched and sets error if beginEnRoute fails without reconciliation', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D1', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-fail', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-fail',
      status: 'MATCHED',
      driverId: 'D1',
      receivedAt: '2026-10-18T10:00:00Z',
      updatedAt: '2026-10-18T10:01:00Z',
    });
    vi.mocked(beginEnRoute).mockRejectedValue(new Error('Network error starting approach'));
    vi.mocked(getTripStatus).mockResolvedValue({
      requestId: 'req-fail',
      driverId: 'D1',
      status: 'ASSIGNED',
      createdAt: '2026-10-18T10:00:00Z',
      updatedAt: '2026-10-18T10:01:00Z',
    });

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('matched');
    expect(result.current.dispatchState.errorMessage).toContain('Network error starting approach');
  });

  it('reconciles to en_route if beginEnRoute fails but authoritative status is EN_ROUTE', async () => {
    vi.mocked(registerDriver).mockResolvedValue({ driverId: 'D1', message: 'ok' });
    vi.mocked(submitRideRequest).mockResolvedValue({ requestId: 'req-rec', message: 'ok' });
    vi.mocked(getRequestStatus).mockResolvedValue({
      requestId: 'req-rec',
      status: 'MATCHED',
      driverId: 'D1',
      receivedAt: '2026-10-18T10:00:00Z',
      updatedAt: '2026-10-18T10:01:00Z',
    });
    vi.mocked(beginEnRoute).mockRejectedValue(new Error('HTTP 503 broker hiccup'));
    vi.mocked(getTripStatus).mockResolvedValue({
      requestId: 'req-rec',
      driverId: 'D1',
      status: 'EN_ROUTE',
      createdAt: '2026-10-18T10:00:00Z',
      updatedAt: '2026-10-18T10:01:00Z',
    });

    const { result } = renderHook(() => useDispatch(makePlaceSuggestion()));
    act(() => { result.current.requestDispatch(); });
    await drainHook();

    expect(result.current.dispatchState.phase).toBe('en_route');
    expect(result.current.dispatchState.errorMessage).toBeNull();
  });

  it('prevents duplicate startRide calls while in flight and exposes isStarting', async () => {
    const result = await setupMatchedTrip();
    act(() => { result.current.markArrived(); });

    let resolveStart!: (val: any) => void;
    vi.mocked(startTrip).mockReturnValue(
      new Promise((resolve) => {
        resolveStart = resolve;
      }),
    );

    let p1!: Promise<boolean>;
    act(() => {
      p1 = result.current.startRide();
    });

    expect(result.current.isStarting).toBe(true);
    expect(startTrip).toHaveBeenCalledTimes(1);

    // Concurrent call must be rejected
    let p2Result = true;
    await act(async () => {
      p2Result = await result.current.startRide();
    });
    expect(p2Result).toBe(false);
    expect(startTrip).toHaveBeenCalledTimes(1);

    await act(async () => {
      resolveStart({
        requestId: 'req-11-3',
        driverId: 'D1',
        status: 'IN_PROGRESS',
        outcome: 'TRANSITIONED',
        message: 'Started',
      });
      await p1;
    });

    expect(result.current.isStarting).toBe(false);
    expect(result.current.dispatchState.phase).toBe('in_progress');
  });

  it('reconciles startRide to in_progress on 503 if backend status confirms IN_PROGRESS', async () => {
    const result = await setupMatchedTrip();
    act(() => { result.current.markArrived(); });

    vi.mocked(startTrip).mockRejectedValue(new Error('HTTP 503 Driver registry timeout'));
    vi.mocked(getTripStatus).mockResolvedValue({
      requestId: 'req-11-3',
      driverId: 'D1',
      status: 'IN_PROGRESS',
      createdAt: '2026-10-18T10:00:00Z',
      updatedAt: '2026-10-18T10:05:00Z',
    });

    let success = false;
    await act(async () => {
      success = await result.current.startRide();
    });

    expect(success).toBe(true);
    expect(result.current.dispatchState.phase).toBe('in_progress');
    expect(result.current.dispatchState.errorMessage).toBeNull();
  });

  it('prevents duplicate completeRide calls while in flight and exposes isCompleting', async () => {
    const result = await setupMatchedTrip();
    act(() => { result.current.markArrived(); });

    vi.mocked(startTrip).mockResolvedValue({
      requestId: 'req-11-3',
      driverId: 'D1',
      status: 'IN_PROGRESS',
      outcome: 'TRANSITIONED',
      message: 'Started',
    });
    await act(async () => {
      await result.current.startRide();
    });

    let resolveComplete!: (val: any) => void;
    vi.mocked(completeTrip).mockReturnValue(
      new Promise((resolve) => {
        resolveComplete = resolve;
      }),
    );

    let p1!: Promise<boolean>;
    act(() => {
      p1 = result.current.completeRide();
    });

    expect(result.current.isCompleting).toBe(true);
    expect(completeTrip).toHaveBeenCalledTimes(1);

    // Duplicate click
    let p2Result = true;
    await act(async () => {
      p2Result = await result.current.completeRide();
    });
    expect(p2Result).toBe(false);
    expect(completeTrip).toHaveBeenCalledTimes(1);

    await act(async () => {
      resolveComplete({
        requestId: 'req-11-3',
        driverId: 'D1',
        status: 'COMPLETED',
        outcome: 'TRANSITIONED',
        message: 'Completed',
      });
      await p1;
    });

    expect(result.current.isCompleting).toBe(false);
    expect(result.current.dispatchState.phase).toBe('completed');
  });

  it('strictly locks out cancellation once the trip is in_progress or completed', async () => {
    const result = await setupMatchedTrip();
    act(() => { result.current.markArrived(); });

    vi.mocked(startTrip).mockResolvedValue({
      requestId: 'req-11-3',
      driverId: 'D1',
      status: 'IN_PROGRESS',
      outcome: 'TRANSITIONED',
      message: 'Started',
    });
    await act(async () => {
      await result.current.startRide();
    });
    expect(result.current.dispatchState.phase).toBe('in_progress');

    // Attempting cancellation during in_progress
    let cancelAttempt1 = true;
    await act(async () => {
      cancelAttempt1 = await result.current.cancelRide();
    });
    expect(cancelAttempt1).toBe(false);
    expect(cancelTrip).not.toHaveBeenCalled();

    // Complete trip
    vi.mocked(completeTrip).mockResolvedValue({
      requestId: 'req-11-3',
      driverId: 'D1',
      status: 'COMPLETED',
      outcome: 'TRANSITIONED',
      message: 'Completed',
    });
    await act(async () => {
      await result.current.completeRide();
    });
    expect(result.current.dispatchState.phase).toBe('completed');

    // Attempting cancellation after completion
    let cancelAttempt2 = true;
    await act(async () => {
      cancelAttempt2 = await result.current.cancelRide();
    });
    expect(cancelAttempt2).toBe(false);
    expect(cancelTrip).not.toHaveBeenCalled();
  });

  it('allows cancellation while arrived at pickup prior to boarding', async () => {
    const result = await setupMatchedTrip();
    act(() => { result.current.markArrived(); });
    expect(result.current.dispatchState.phase).toBe('arrived');

    vi.mocked(cancelTrip).mockResolvedValue({
      requestId: 'req-11-3',
      driverId: 'D1',
      status: 'CANCELLED',
      outcome: 'TRANSITIONED',
      message: 'Cancelled',
    });

    let cancelSuccess = false;
    await act(async () => {
      cancelSuccess = await result.current.cancelRide();
    });

    expect(cancelSuccess).toBe(true);
    expect(cancelTrip).toHaveBeenCalledWith(expect.stringMatching(/^req-/), 'D1', expect.any(Object));
    expect(result.current.dispatchState.phase).toBe('cancelled');
  });

  it('prevents stale async lifecycle responses from overwriting a reset or new trip', async () => {
    const result = await setupMatchedTrip();
    act(() => { result.current.markArrived(); });

    let resolveSlowStart!: (val: any) => void;
    vi.mocked(startTrip).mockReturnValue(
      new Promise((resolve) => {
        resolveSlowStart = resolve;
      }),
    );

    act(() => {
      result.current.startRide();
    });

    // User resets back to idle
    act(() => {
      result.current.reset();
    });
    expect(result.current.dispatchState.phase).toBe('idle');

    // Slow start finally resolves
    await act(async () => {
      resolveSlowStart({
        requestId: 'req-11-3',
        driverId: 'D1',
        status: 'IN_PROGRESS',
        outcome: 'TRANSITIONED',
        message: 'Late start',
      });
    });

    // Stale resolution must NOT have advanced the reset hook to in_progress!
    expect(result.current.dispatchState.phase).toBe('idle');
  });
});
