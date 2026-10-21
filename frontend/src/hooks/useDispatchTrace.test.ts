/**
 * Phase 11.4D - Unit tests for the useDispatchTrace hook.
 *
 * Mocks getDispatchTrace from services/dispatch so no real HTTP requests
 * are made. Follows the same mock/renderHook/waitFor pattern as
 * useDriverApproach.test.ts.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { useDispatchTrace } from './useDispatchTrace';
import * as dispatchService from '../services/dispatch';
import type { DispatchTrace } from '../types/dispatch';

vi.mock('../services/dispatch', () => ({
  getDispatchTrace: vi.fn(),
  // The module has other exports; only getDispatchTrace is needed here.
  registerDriver: vi.fn(),
  submitRideRequest: vi.fn(),
  getRequestStatus: vi.fn(),
  fetchEligibleDrivers: vi.fn(),
  cancelTrip: vi.fn(),
  beginEnRoute: vi.fn(),
  startTrip: vi.fn(),
  completeTrip: vi.fn(),
  getTripStatus: vi.fn(),
}));

const mockedGetDispatchTrace = vi.mocked(dispatchService.getDispatchTrace);

// ── Fixtures ──────────────────────────────────────────────────────────────────

function sampleTrace(requestId = 'REQ-100'): DispatchTrace {
  return {
    requestId,
    algorithm: 'AuctionMatcher',
    pickupLatitude: 12.9716,
    pickupLongitude: 77.5946,
    startedAt: '2026-10-19T02:00:00Z',
    durationNanos: 2_500_000,
    stages: [],
    candidates: [],
    winnerDriverId: 'DRV-1',
    matched: true,
    totalFleetSize: 150,
    decisionSummary: 'Matched with driver DRV-1 (total cost: 1.70)',
  };
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('useDispatchTrace hook', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.resetAllMocks();
  });

  // ── No-request cases ──────────────────────────────────────────────────────

  it('remains idle and sends no request when requestId is null', () => {
    const { result } = renderHook(() => useDispatchTrace(null));

    expect(result.current.state.status).toBe('idle');
    expect(result.current.state.trace).toBeNull();
    expect(result.current.state.errorMessage).toBeNull();
    expect(mockedGetDispatchTrace).not.toHaveBeenCalled();
  });

  it('remains idle and sends no request when requestId is undefined', () => {
    const { result } = renderHook(() => useDispatchTrace(undefined));

    expect(result.current.state.status).toBe('idle');
    expect(mockedGetDispatchTrace).not.toHaveBeenCalled();
  });

  it('remains idle and sends no request when requestId is blank whitespace', () => {
    const { result } = renderHook(() => useDispatchTrace('   '));

    expect(result.current.state.status).toBe('idle');
    expect(mockedGetDispatchTrace).not.toHaveBeenCalled();
  });

  it('remains idle and sends no request when requestId is an empty string', () => {
    const { result } = renderHook(() => useDispatchTrace(''));

    expect(result.current.state.status).toBe('idle');
    expect(mockedGetDispatchTrace).not.toHaveBeenCalled();
  });

  // ── Successful retrieval ──────────────────────────────────────────────────

  it('transitions to ready with typed trace on successful fetch', async () => {
    const trace = sampleTrace('REQ-200');
    mockedGetDispatchTrace.mockResolvedValue(trace);

    const { result } = renderHook(() => useDispatchTrace('REQ-200'));

    await waitFor(() => {
      expect(result.current.state.status).toBe('ready');
    });

    expect(result.current.state.trace).toEqual(trace);
    expect(result.current.state.errorMessage).toBeNull();
    expect(mockedGetDispatchTrace).toHaveBeenCalledOnce();
    expect(mockedGetDispatchTrace).toHaveBeenCalledWith('REQ-200', expect.any(AbortSignal));
  });

  // ── HTTP 404 handling ─────────────────────────────────────────────────────

  it('transitions to not_found (distinct from error) when backend returns 404', async () => {
    mockedGetDispatchTrace.mockResolvedValue(null);

    const { result } = renderHook(() => useDispatchTrace('REQ-404'));

    await waitFor(() => {
      expect(result.current.state.status).toBe('not_found');
    });

    expect(result.current.state.trace).toBeNull();
    expect(result.current.state.errorMessage).toBeNull();
  });

  // ── Error handling ────────────────────────────────────────────────────────

  it('transitions to error with message on network or server failure', async () => {
    mockedGetDispatchTrace.mockRejectedValue(new Error('Internal Server Error'));

    const { result } = renderHook(() => useDispatchTrace('REQ-ERR'));

    await waitFor(() => {
      expect(result.current.state.status).toBe('error');
    });

    expect(result.current.state.trace).toBeNull();
    expect(result.current.state.errorMessage).toBe('Internal Server Error');
  });

  // ── Stale response prevention / ID change ─────────────────────────────────

  it('suppresses stale response when requestId changes before first fetch resolves', async () => {
    const traceA = sampleTrace('REQ-A');
    const traceB = sampleTrace('REQ-B');

    let resolveA!: (v: DispatchTrace) => void;
    const pendingA = new Promise<DispatchTrace>((res) => {
      resolveA = res;
    });

    mockedGetDispatchTrace
      .mockImplementationOnce(() => pendingA)
      .mockResolvedValueOnce(traceB);

    const { result, rerender } = renderHook(
      ({ id }) => useDispatchTrace(id),
      { initialProps: { id: 'REQ-A' } },
    );

    // REQ-A fetch is in-flight; switch to REQ-B before it resolves
    rerender({ id: 'REQ-B' });

    await waitFor(() => {
      expect(result.current.state.status).toBe('ready');
    });

    // Late-resolve REQ-A - must not overwrite REQ-B result
    resolveA(traceA);

    expect(result.current.state.trace?.requestId).toBe('REQ-B');
    expect(mockedGetDispatchTrace).toHaveBeenCalledTimes(2);
  });

  it('resets trace to null while loading when switching from request A to request B', async () => {
    const traceA = sampleTrace('REQ-A');
    let resolveB!: (v: DispatchTrace) => void;
    const pendingB = new Promise<DispatchTrace>((res) => {
      resolveB = res;
    });

    mockedGetDispatchTrace
      .mockResolvedValueOnce(traceA)
      .mockImplementationOnce(() => pendingB);

    const { result, rerender } = renderHook(
      ({ id }) => useDispatchTrace(id),
      { initialProps: { id: 'REQ-A' } },
    );

    // Wait for REQ-A to resolve successfully
    await waitFor(() => {
      expect(result.current.state.status).toBe('ready');
    });
    expect(result.current.state.trace).toEqual(traceA);

    // Switch to REQ-B while REQ-B fetch is in-flight
    rerender({ id: 'REQ-B' });

    // Verify state transitions to loading and trace is immediately reset to null
    expect(result.current.state.status).toBe('loading');
    expect(result.current.state.trace).toBeNull();
    expect(result.current.state.errorMessage).toBeNull();

    // Resolve REQ-B and verify state transitions to ready with trace B
    const traceB = sampleTrace('REQ-B');
    resolveB(traceB);

    await waitFor(() => {
      expect(result.current.state.status).toBe('ready');
    });
    expect(result.current.state.trace).toEqual(traceB);
  });

  // ── Unmount cleanup ───────────────────────────────────────────────────────

  it('does not update state after unmount (no thrown error)', async () => {
    let resolve!: (v: DispatchTrace) => void;
    const pending = new Promise<DispatchTrace>((res) => {
      resolve = res;
    });
    mockedGetDispatchTrace.mockImplementation(() => pending);

    const { unmount } = renderHook(() => useDispatchTrace('REQ-UNMOUNT'));

    unmount();

    // Resolving after unmount must not throw or produce a state-update warning
    resolve(sampleTrace('REQ-UNMOUNT'));

    // Allow any microtasks to flush before the test completes
    await Promise.resolve();
  });

  // ── Manual refresh ────────────────────────────────────────────────────────

  it('re-fetches when refresh() is called', async () => {
    const trace = sampleTrace('REQ-REFRESH');
    mockedGetDispatchTrace.mockResolvedValue(trace);

    const { result } = renderHook(() => useDispatchTrace('REQ-REFRESH'));

    await waitFor(() => {
      expect(result.current.state.status).toBe('ready');
    });
    expect(mockedGetDispatchTrace).toHaveBeenCalledTimes(1);

    act(() => {
      result.current.refresh();
    });

    // Wait for both the second call and the resolved state together
    await waitFor(() => {
      expect(mockedGetDispatchTrace).toHaveBeenCalledTimes(2);
      expect(result.current.state.status).toBe('ready');
    });
  });

  it('clears error state after a successful manual refresh', async () => {
    const trace = sampleTrace('REQ-RECOVER');

    mockedGetDispatchTrace
      .mockRejectedValueOnce(new Error('Service Unavailable'))
      .mockResolvedValueOnce(trace);

    const { result } = renderHook(() => useDispatchTrace('REQ-RECOVER'));

    await waitFor(() => {
      expect(result.current.state.status).toBe('error');
    });

    act(() => {
      result.current.refresh();
    });

    await waitFor(() => {
      expect(result.current.state.status).toBe('ready');
    });

    expect(result.current.state.errorMessage).toBeNull();
    expect(result.current.state.trace?.requestId).toBe('REQ-RECOVER');
  });

  it('refresh is a no-op when requestId is blank', () => {
    const { result } = renderHook(() => useDispatchTrace(''));

    act(() => {
      result.current.refresh();
    });

    expect(mockedGetDispatchTrace).not.toHaveBeenCalled();
  });

  // ── Transition to idle when requestId cleared ─────────────────────────────

  it('resets to idle when a valid requestId is replaced by null', async () => {
    mockedGetDispatchTrace.mockResolvedValue(sampleTrace('REQ-CLR'));

    const { result, rerender } = renderHook(
      ({ id }: { id: string | null }) => useDispatchTrace(id),
      { initialProps: { id: 'REQ-CLR' as string | null } },
    );

    await waitFor(() => {
      expect(result.current.state.status).toBe('ready');
    });

    rerender({ id: null });

    expect(result.current.state.status).toBe('idle');
    expect(result.current.state.trace).toBeNull();
  });
});
