import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { useRoute } from './useRoute';
import { routingService } from '../services/routing';
import type { PlaceSuggestion } from '../types/place';

const placeA: PlaceSuggestion = {
  id: 'place-a',
  name: 'Pickup City',
  subtitle: 'Region A',
  lat: 12.9716,
  lon: 77.5946,
  type: 'city',
};

const placeB: PlaceSuggestion = {
  id: 'place-b',
  name: 'Destination City',
  subtitle: 'Region B',
  lat: 12.9352,
  lon: 77.6245,
  type: 'city',
};

describe('useRoute hook', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('remains idle when only pickup is provided', () => {
    const { result } = renderHook(() => useRoute(placeA, null));
    expect(result.current.state.status).toBe('idle');
    expect(result.current.state.route).toBeNull();
  });

  it('triggers route calculation when both pickup and destination are provided', async () => {
    vi.spyOn(routingService, 'getRoute').mockResolvedValueOnce({
      status: 'ok',
      route: {
        distanceMeters: 5200,
        distanceKm: 5.2,
        durationSeconds: 780,
        durationMinutes: 13,
        coordinates: [
          [77.5946, 12.9716],
          [77.6245, 12.9352],
        ],
        fares: [
          {
            tierId: 'standard',
            name: 'Rendezvous Go',
            tagline: 'Standard',
            capacity: 4,
            baseFare: 50,
            distanceFare: 78,
            timeFare: 26,
            totalFare: 154,
            currency: '₹',
            etaMinutes: 3,
            icon: '🚗',
          },
        ],
        isFallback: false,
        attribution: 'OSRM',
      },
    });

    const { result } = renderHook(() => useRoute(placeA, placeB));

    await waitFor(() => {
      expect(result.current.state.status).toBe('ready');
    });

    expect(result.current.state.route).not.toBeNull();
    expect(result.current.state.route?.distanceKm).toBe(5.2);
    expect(result.current.state.route?.durationMinutes).toBe(13);
  });

  it('updates selected tier correctly', async () => {
    const { result } = renderHook(() => useRoute(placeA, null));
    expect(result.current.state.selectedTier).toBe('standard');

    act(() => {
      result.current.setSelectedTier('premier');
    });

    expect(result.current.state.selectedTier).toBe('premier');
  });

  it('handles routing error and reflects error status', async () => {
    vi.spyOn(routingService, 'getRoute').mockResolvedValueOnce({
      status: 'error',
      message: 'Failed to calculate route',
      retryable: true,
    });

    const { result } = renderHook(() => useRoute(placeA, placeB));

    await waitFor(() => {
      expect(result.current.state.status).toBe('error');
    });

    expect(result.current.state.errorMessage).toBe('Failed to calculate route');
  });
});
