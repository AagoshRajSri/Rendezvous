import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import {
  calculateBearing,
  buildCumulativeRoute,
  interpolateRoutePosition,
  useVehicleAnimation,
} from './useVehicleAnimation';

describe('calculateBearing', () => {
  it('calculates approximately 0° (North) when traveling directly North', () => {
    const bearing = calculateBearing([0, 0], [0, 1]);
    expect(Math.round(bearing)).toBe(0);
  });

  it('calculates approximately 90° (East) when traveling directly East', () => {
    const bearing = calculateBearing([0, 0], [1, 0]);
    expect(Math.round(bearing)).toBe(90);
  });

  it('calculates approximately 180° (South) when traveling directly South', () => {
    const bearing = calculateBearing([0, 1], [0, 0]);
    expect(Math.round(bearing)).toBe(180);
  });

  it('calculates approximately 270° (West) when traveling directly West', () => {
    const bearing = calculateBearing([1, 0], [0, 0]);
    expect(Math.round(bearing)).toBe(270);
  });
});

describe('buildCumulativeRoute & interpolateRoutePosition', () => {
  const sampleCoords: [number, number][] = [
    [78.4867, 17.385],
    [78.49, 17.385],
    [78.49, 17.39],
  ];

  it('builds monotonically increasing cumulative distances', () => {
    const cumulative = buildCumulativeRoute(sampleCoords);
    expect(cumulative.cumulativeDistances).toHaveLength(3);
    expect(cumulative.cumulativeDistances[0]).toBe(0);
    expect(cumulative.cumulativeDistances[1]).toBeGreaterThan(0);
    expect(cumulative.cumulativeDistances[2]).toBeGreaterThan(cumulative.cumulativeDistances[1]);
    expect(cumulative.totalDistance).toBe(cumulative.cumulativeDistances[2]);
  });

  it('interpolates start point at 0 distance', () => {
    const cumulative = buildCumulativeRoute(sampleCoords);
    const { position } = interpolateRoutePosition(sampleCoords, cumulative, 0);
    expect(position[0]).toBeCloseTo(78.4867, 4);
    expect(position[1]).toBeCloseTo(17.385, 4);
  });

  it('interpolates midpoint along first segment', () => {
    const cumulative = buildCumulativeRoute(sampleCoords);
    const halfSeg1 = cumulative.cumulativeDistances[1] / 2;
    const { position } = interpolateRoutePosition(sampleCoords, cumulative, halfSeg1);
    expect(position[0]).toBeGreaterThan(78.4867);
    expect(position[0]).toBeLessThan(78.49);
    expect(position[1]).toBeCloseTo(17.385, 4);
  });

  it('interpolates final coordinate when distance meets or exceeds total', () => {
    const cumulative = buildCumulativeRoute(sampleCoords);
    const { position } = interpolateRoutePosition(sampleCoords, cumulative, cumulative.totalDistance + 100);
    expect(position[0]).toBeCloseTo(78.49, 4);
    expect(position[1]).toBeCloseTo(17.39, 4);
  });
});

describe('useVehicleAnimation hook', () => {
  const sampleCoords: [number, number][] = [
    [78.4867, 17.385],
    [78.49, 17.39],
  ];

  let rafCallbacks: FrameRequestCallback[] = [];
  let nextFrameId = 1;

  beforeEach(() => {
    rafCallbacks = [];
    nextFrameId = 1;
    vi.stubGlobal('requestAnimationFrame', vi.fn((cb: FrameRequestCallback) => {
      rafCallbacks.push(cb);
      return nextFrameId++;
    }));
    vi.stubGlobal('cancelAnimationFrame', vi.fn((_id: number) => {
      // noop in test
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function stepRaf(nowMs: number) {
    const current = [...rafCallbacks];
    rafCallbacks = [];
    current.forEach((cb) => cb(nowMs));
  }

  it('initializes with start coordinates when inactive', () => {
    const { result } = renderHook(() =>
      useVehicleAnimation({
        routeCoordinates: sampleCoords,
        isActive: false,
      }),
    );

    expect(result.current.currentPosition).toEqual(sampleCoords[0]);
    expect(result.current.progress).toBe(0);
    expect(result.current.isArrived).toBe(false);
  });

  it('advances progress and updates position across animation frames', () => {
    const onArrival = vi.fn();
    const { result } = renderHook(() =>
      useVehicleAnimation({
        routeCoordinates: sampleCoords,
        nominalDurationSeconds: 10,
        speedMultiplier: 10, // 10s / 10 = 1s total animation (1000ms)
        isActive: true,
        onArrival,
      }),
    );

    // Initial frame setup
    expect(requestAnimationFrame).toHaveBeenCalled();

    // Step 0: start time recorded at 1000ms
    act(() => {
      stepRaf(1000);
    });
    expect(result.current.progress).toBe(0);

    // Step 1: 500ms later (50% progress)
    act(() => {
      stepRaf(1500);
    });
    expect(result.current.progress).toBeCloseTo(0.5, 1);
    expect(result.current.isArrived).toBe(false);
    expect(result.current.currentPosition![0]).toBeGreaterThan(sampleCoords[0][0]);
    expect(result.current.currentPosition![0]).toBeLessThan(sampleCoords[1][0]);

    // Step 2: 1000ms later (100% progress)
    act(() => {
      stepRaf(2000);
    });
    expect(result.current.progress).toBe(1.0);
    expect(result.current.isArrived).toBe(true);
    expect(result.current.remainingDistanceMeters).toBe(0);
    expect(result.current.remainingDurationSeconds).toBe(0);
    expect(onArrival).toHaveBeenCalledTimes(1);
  });

  it('cancels and resets cleanly when isActive becomes false', () => {
    const { result, rerender } = renderHook(
      ({ active }) =>
        useVehicleAnimation({
          routeCoordinates: sampleCoords,
          isActive: active,
        }),
      { initialProps: { active: true } },
    );

    act(() => {
      stepRaf(1000);
      stepRaf(1300);
    });

    rerender({ active: false });

    expect(result.current.isArrived).toBe(false);
    expect(result.current.progress).toBe(0);
  });
});
