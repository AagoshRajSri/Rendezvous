/**
 * Unit tests for usePlaceSearch hook.
 * Tests: debounce, stale response guard, caching, clearQuery, retry.
 *
 * Notes:
 * - Each test injects its own GeocodingService instance to bypass the module-
 *   level singleton and avoid cross-test cache pollution.
 * - The hook's cache is keyed by query string. We use distinct queries across
 *   tests so that cache hits from earlier tests do not interfere.
 * - Fake timers control the 300ms debounce without real waits.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { usePlaceSearch } from './usePlaceSearch';
import type { GeocodingResult, GeocodingService, PlaceSuggestion } from '../types/place';

// ── Helpers ───────────────────────────────────────────────────────────────────

const PARIS: PlaceSuggestion = {
  id: 'photon-R-71525-0',
  name: 'Paris',
  subtitle: 'Île-de-France, France',
  type: 'city',
  lon: 2.348,
  lat: 48.853,
  bbox: [2.224, 48.815, 2.469, 48.902],
};

function makeService(
  impl: (q: string, s: AbortSignal) => Promise<GeocodingResult>,
): GeocodingService {
  return { search: vi.fn(impl) };
}

const okResult: GeocodingResult = { status: 'ok', suggestions: [PARIS] };
const emptyResult: GeocodingResult = { status: 'empty' };
const networkError: GeocodingResult = {
  status: 'error',
  message: 'Network error',
  retryable: true,
};

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('usePlaceSearch', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('starts in idle state', () => {
    const service = makeService(vi.fn());
    const { result } = renderHook(() => usePlaceSearch({ service }));
    expect(result.current.state.status).toBe('idle');
  });

  it('stays idle for queries shorter than 2 characters', async () => {
    const search = vi.fn().mockResolvedValue(okResult);
    const service = makeService(search);
    const { result } = renderHook(() => usePlaceSearch({ service }));

    act(() => { result.current.setQuery('a'); });
    await act(async () => { vi.advanceTimersByTime(500); });

    expect(search).not.toHaveBeenCalled();
    expect(result.current.state.status).toBe('idle');
  });

  it('debounces: only dispatches one request after 300ms of silence', async () => {
    const search = vi.fn().mockResolvedValue(okResult);
    const service = makeService(search);
    const { result } = renderHook(() => usePlaceSearch({ service }));

    // Use unique query prefix to avoid cache hits from other tests
    act(() => { result.current.setQuery('Qx'); });
    act(() => { result.current.setQuery('Qxa'); });
    act(() => { result.current.setQuery('Qxar'); });
    act(() => { result.current.setQuery('QxariS'); });

    await act(async () => { vi.advanceTimersByTime(300); });

    expect(search).toHaveBeenCalledTimes(1);
    expect(search.mock.calls[0][0]).toBe('QxariS');
  });

  it('transitions through loading to ok', async () => {
    // Use a promise we can control manually
    let resolveFetch!: (r: GeocodingResult) => void;
    const pending = new Promise<GeocodingResult>((res) => { resolveFetch = res; });
    const service = makeService(() => pending);
    const { result } = renderHook(() => usePlaceSearch({ service }));

    // Trigger debounce with unique query
    act(() => { result.current.setQuery('Zz'); });
    // Advance past debounce - fetch is now in-flight
    await act(async () => { vi.advanceTimersByTime(300); });

    // Fetch is pending; state should be loading
    expect(result.current.state.status).toBe('loading');

    // Now resolve the fetch
    await act(async () => { resolveFetch(okResult); });

    expect(result.current.state.status).toBe('ok');
    if (result.current.state.status !== 'ok') return;
    expect(result.current.state.suggestions[0].name).toBe('Paris');
  });

  it('discards a stale response when a newer request completes first', async () => {
    // Both requests resolve after the test triggers them; we control order of
    // resolution to simulate the newer completing before the stale one.
    let resolveFirst!: (r: GeocodingResult) => void;
    const first = new Promise<GeocodingResult>((res) => { resolveFirst = res; });
    let resolveSecond!: (r: GeocodingResult) => void;
    const second = new Promise<GeocodingResult>((res) => { resolveSecond = res; });

    let callCount = 0;
    const service = makeService(() => {
      callCount++;
      return callCount === 1 ? first : second;
    });

    const { result } = renderHook(() => usePlaceSearch({ service }));

    // Use unique prefixes to avoid cache
    act(() => { result.current.setQuery('Zy'); });
    await act(async () => { vi.advanceTimersByTime(300); });

    act(() => { result.current.setQuery('ZyZ'); });
    await act(async () => { vi.advanceTimersByTime(300); });

    // Resolve second (newer) first, then stale first
    const staleResult: GeocodingResult = {
      status: 'ok',
      suggestions: [{ ...PARIS, name: 'STALE' }],
    };
    await act(async () => { resolveSecond(okResult); });
    await act(async () => { resolveFirst(staleResult); });

    // Should reflect the newer (second) result
    expect(result.current.state.status).toBe('ok');
    if (result.current.state.status !== 'ok') return;
    expect(result.current.state.suggestions[0].name).toBe('Paris');
  });

  it('clearQuery resets to idle and clears query string', async () => {
    const service = makeService(vi.fn().mockResolvedValue(okResult));
    const { result } = renderHook(() => usePlaceSearch({ service }));

    act(() => { result.current.setQuery('Rr'); });
    await act(async () => { vi.advanceTimersByTime(300); });

    act(() => { result.current.clearQuery(); });

    expect(result.current.query).toBe('');
    expect(result.current.state.status).toBe('idle');
  });

  it('returns empty state on empty result', async () => {
    const service = makeService(vi.fn().mockResolvedValue(emptyResult));
    const { result } = renderHook(() => usePlaceSearch({ service }));

    act(() => { result.current.setQuery('xyzzy99'); });
    await act(async () => { vi.advanceTimersByTime(300); });

    expect(result.current.state.status).toBe('empty');
  });

  it('returns error state on network failure', async () => {
    // Use unique query so cache doesn't interfere
    const service = makeService(vi.fn().mockResolvedValue(networkError));
    const { result } = renderHook(() => usePlaceSearch({ service }));

    act(() => { result.current.setQuery('Err1'); });
    await act(async () => { vi.advanceTimersByTime(300); });

    expect(result.current.state.status).toBe('error');
    if (result.current.state.status !== 'error') return;
    expect(result.current.state.retryable).toBe(true);
  });

  it('retry re-dispatches a new request', async () => {
    const search = vi.fn()
      .mockResolvedValueOnce(networkError)
      .mockResolvedValueOnce(okResult);
    const service = makeService(search);
    const { result } = renderHook(() => usePlaceSearch({ service }));

    act(() => { result.current.setQuery('Err2'); });
    await act(async () => { vi.advanceTimersByTime(300); });
    expect(result.current.state.status).toBe('error');

    await act(async () => { result.current.retry(); });

    expect(result.current.state.status).toBe('ok');
    expect(search).toHaveBeenCalledTimes(2);
  });

  it('cache hit aborts in-flight request and cannot be overwritten by a late network response', async () => {
    // 1. Prime cache with "London"
    const londonSuggestion: PlaceSuggestion = { ...PARIS, name: 'London' };
    const londonResult: GeocodingResult = { status: 'ok', suggestions: [londonSuggestion] };
    const primeSearch = vi.fn().mockResolvedValue(londonResult);
    const primeService = makeService(primeSearch);
    const { result: primeHook } = renderHook(() => usePlaceSearch({ service: primeService }));

    act(() => { primeHook.current.setQuery('London'); });
    await act(async () => { vi.advanceTimersByTime(300); });
    expect(primeHook.current.state.status).toBe('ok');

    // 2. Now start a slow query "SlowCity"
    let resolveSlow!: (r: GeocodingResult) => void;
    const slowPromise = new Promise<GeocodingResult>((res) => { resolveSlow = res; });
    const service = makeService(() => slowPromise);
    const { result } = renderHook(() => usePlaceSearch({ service }));

    act(() => { result.current.setQuery('SlowCity'); });
    await act(async () => { vi.advanceTimersByTime(300); });
    expect(result.current.state.status).toBe('loading');

    // 3. User switches to cached "london" (case-insensitive)
    act(() => { result.current.setQuery('london'); });
    // Cache hit should be synchronous
    expect(result.current.state.status).toBe('ok');
    if (result.current.state.status !== 'ok') return;
    expect(result.current.state.suggestions[0].name).toBe('London');

    // 4. Now the slow network request for "SlowCity" finally resolves
    const slowResult: GeocodingResult = {
      status: 'ok',
      suggestions: [{ ...PARIS, name: 'SlowCity' }],
    };
    await act(async () => { resolveSlow(slowResult); });

    // 5. Stale response MUST NOT overwrite the cache hit
    expect(result.current.state.status).toBe('ok');
    if (result.current.state.status !== 'ok') return;
    expect(result.current.state.suggestions[0].name).toBe('London');
  });

  it('clearQuery aborts in-flight request and cannot be overwritten by a late response', async () => {
    let resolvePending!: (r: GeocodingResult) => void;
    const pendingPromise = new Promise<GeocodingResult>((res) => { resolvePending = res; });
    const service = makeService(() => pendingPromise);
    const { result } = renderHook(() => usePlaceSearch({ service }));

    act(() => { result.current.setQuery('PendingClear'); });
    await act(async () => { vi.advanceTimersByTime(300); });
    expect(result.current.state.status).toBe('loading');

    // Clear query
    act(() => { result.current.clearQuery(); });
    expect(result.current.query).toBe('');
    expect(result.current.state.status).toBe('idle');

    // Resolve late aborted response (e.g. empty or ok)
    await act(async () => { resolvePending(emptyResult); });

    // Must remain idle, NOT empty
    expect(result.current.state.status).toBe('idle');
  });

  it('reducing query below 2 chars aborts in-flight request and remains idle', async () => {
    let resolvePending!: (r: GeocodingResult) => void;
    const pendingPromise = new Promise<GeocodingResult>((res) => { resolvePending = res; });
    const service = makeService(() => pendingPromise);
    const { result } = renderHook(() => usePlaceSearch({ service }));

    act(() => { result.current.setQuery('PendingShort'); });
    await act(async () => { vi.advanceTimersByTime(300); });
    expect(result.current.state.status).toBe('loading');

    // Backspace to 1 character
    act(() => { result.current.setQuery('P'); });
    expect(result.current.state.status).toBe('idle');

    // Resolve late aborted response
    await act(async () => { resolvePending(okResult); });

    // Must remain idle
    expect(result.current.state.status).toBe('idle');
  });
});
