/**
 * Phase 7D — Unit tests for the useDispatch hook.
 *
 * Uses vi.mock to replace the dispatch service so no real HTTP requests are made.
 * Tests cover phase transitions, duplicate-submission prevention, abort on place
 * change, no_drivers terminal state, error handling, and reset behaviour.
 */

import { act, renderHook } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useDispatch } from './useDispatch';
import type { PlaceSuggestion } from '../types/place';

// ── Mock the dispatch service ─────────────────────────────────────────────────

vi.mock('../services/dispatch', () => ({
  registerDriver: vi.fn(),
  submitRideRequest: vi.fn(),
  getRequestStatus: vi.fn(),
}));

import { registerDriver, submitRideRequest, getRequestStatus } from '../services/dispatch';

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
  it('transitions through registering → submitting → polling → matched', async () => {
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

    expect(result.current.dispatchState.phase).toBe('matched');
    expect(result.current.dispatchState.matchedDriverId).toBe('DRIVER-42');
    expect(result.current.dispatchState.matchedDriverLocation).toBeNull();
    expect(result.current.dispatchState.errorMessage).toBeNull();
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
