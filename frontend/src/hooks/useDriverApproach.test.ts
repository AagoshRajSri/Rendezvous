import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useDriverApproach } from './useDriverApproach';
import { routingService } from '../services/routing';
import type { PlaceSuggestion } from '../types/place';

vi.mock('../services/routing', () => ({
  routingService: {
    getRoute: vi.fn(),
  },
}));

describe('useDriverApproach hook', () => {
  const pickup: PlaceSuggestion = {
    id: 'place-pickup',
    name: 'Charminar',
    subtitle: 'Hyderabad',
    type: 'landmark',
    lat: 17.3616,
    lon: 78.4747,
  };

  const driverLoc = { lat: 17.385, lon: 78.4867 };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.resetAllMocks();
  });

  it('remains idle when isMatched is false', () => {
    const { result } = renderHook(() =>
      useDriverApproach(driverLoc, pickup, false),
    );

    expect(result.current.status).toBe('idle');
    expect(result.current.route).toBeNull();
    expect(routingService.getRoute).not.toHaveBeenCalled();
  });

  it('fetches approach route when matched with valid coordinates', async () => {
    const mockRoute = {
      distanceMeters: 3200,
      distanceKm: 3.2,
      durationSeconds: 480,
      durationMinutes: 8,
      coordinates: [
        [78.4867, 17.385],
        [78.4747, 17.3616],
      ] as [number, number][],
      fares: [],
      isFallback: false,
      attribution: 'OSRM',
    };

    vi.mocked(routingService.getRoute).mockResolvedValue({
      status: 'ok',
      route: mockRoute,
    });

    const { result } = renderHook(() =>
      useDriverApproach(driverLoc, pickup, true),
    );

    await waitFor(() => {
      expect(result.current.status).toBe('ready');
    });

    expect(result.current.route).toEqual(mockRoute);
    expect(routingService.getRoute).toHaveBeenCalledWith(
      { lon: 78.4867, lat: 17.385 },
      { lon: 78.4747, lat: 17.3616 },
      expect.any(AbortSignal),
    );
  });

  it('handles routing error gracefully', async () => {
    vi.mocked(routingService.getRoute).mockResolvedValue({
      status: 'error',
      message: 'Failed to calculate driver approach route',
      retryable: false,
    });

    const { result } = renderHook(() =>
      useDriverApproach(driverLoc, pickup, true),
    );

    await waitFor(() => {
      expect(result.current.status).toBe('error');
    });

    expect(result.current.route).toBeNull();
    expect(result.current.errorMessage).toBe('Failed to calculate driver approach route');
  });

  it('resets to idle when isMatched turns false', async () => {
    vi.mocked(routingService.getRoute).mockResolvedValue({
      status: 'ok',
      route: {
        distanceMeters: 1000,
        distanceKm: 1,
        durationSeconds: 120,
        durationMinutes: 2,
        coordinates: [[78.4867, 17.385], [78.4747, 17.3616]],
        fares: [],
        isFallback: false,
        attribution: 'OSRM',
      },
    });

    const { result, rerender } = renderHook(
      ({ matched }) => useDriverApproach(driverLoc, pickup, matched),
      { initialProps: { matched: true } },
    );

    await waitFor(() => {
      expect(result.current.status).toBe('ready');
    });

    rerender({ matched: false });

    expect(result.current.status).toBe('idle');
    expect(result.current.route).toBeNull();
  });
});
