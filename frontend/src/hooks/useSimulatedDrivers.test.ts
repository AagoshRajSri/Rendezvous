import { describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useSimulatedDrivers } from './useSimulatedDrivers';
import type { PlaceSuggestion } from '../types/place';

const PLACE_A: PlaceSuggestion = {
  id: 'place-london',
  name: 'London',
  subtitle: 'Greater London, UK',
  type: 'city',
  lon: -0.1276,
  lat: 51.5074,
  bbox: [-0.3514, 51.3849, 0.1482, 51.6723],
};

const PLACE_B: PlaceSuggestion = {
  id: 'place-paris',
  name: 'Paris',
  subtitle: 'Île-de-France, France',
  type: 'city',
  lon: 2.3488,
  lat: 48.8534,
  bbox: [2.224, 48.815, 2.469, 48.902],
};

describe('useSimulatedDrivers', () => {
  it('returns an empty array when selectedPlace is null', () => {
    const { result } = renderHook(() => useSimulatedDrivers(null));
    expect(result.current).toEqual([]);
  });

  it('generates 12-18 drivers when a place is selected', () => {
    const { result } = renderHook(() => useSimulatedDrivers(PLACE_A));
    expect(result.current.length).toBeGreaterThanOrEqual(12);
    expect(result.current.length).toBeLessThanOrEqual(18);
  });

  it('is deterministic: re-selecting the same place reproduces identical drivers', () => {
    const { result, rerender } = renderHook(
      ({ place }: { place: PlaceSuggestion | null }) => useSimulatedDrivers(place),
      { initialProps: { place: PLACE_A as PlaceSuggestion | null } },
    );

    const firstDrivers = result.current;

    // Change to null
    rerender({ place: null });
    expect(result.current).toEqual([]);

    // Select PLACE_A again
    rerender({ place: PLACE_A });
    expect(result.current).toEqual(firstDrivers);
  });

  it('replaces drivers when switching between different places', () => {
    const { result, rerender } = renderHook(
      ({ place }: { place: PlaceSuggestion | null }) => useSimulatedDrivers(place),
      { initialProps: { place: PLACE_A as PlaceSuggestion | null } },
    );

    const driversA = result.current;

    rerender({ place: PLACE_B });
    const driversB = result.current;

    expect(driversB).not.toEqual(driversA);
    expect(driversB.length).toBeGreaterThanOrEqual(12);
    expect(driversB[0].id).toContain('place-paris');
  });

  it('clears drivers when selectedPlace is set to null', () => {
    const { result, rerender } = renderHook(
      ({ place }: { place: PlaceSuggestion | null }) => useSimulatedDrivers(place),
      { initialProps: { place: PLACE_A as PlaceSuggestion | null } },
    );

    expect(result.current.length).toBeGreaterThan(0);

    rerender({ place: null });
    expect(result.current).toEqual([]);
  });

  it('cleans up state upon unmount without warnings or errors', () => {
    const { result, unmount } = renderHook(() => useSimulatedDrivers(PLACE_A));
    expect(result.current.length).toBeGreaterThan(0);

    act(() => {
      unmount();
    });
  });
});
