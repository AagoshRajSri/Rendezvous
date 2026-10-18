import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  OsrmRoutingService,
  calculateHaversineDistance,
  createFallbackRoute,
  interpolateGreatCirclePoints,
} from './routing';

describe('OsrmRoutingService and routing utilities', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('calculates Haversine distance correctly between two points', () => {
    // New York to London ~5570 km
    const ny = { lat: 40.7128, lon: -74.006 };
    const london = { lat: 51.5074, lon: -0.1278 };
    const distMeters = calculateHaversineDistance(ny, london);
    const distKm = distMeters / 1000;

    expect(distKm).toBeGreaterThan(5500);
    expect(distKm).toBeLessThan(5600);
  });

  it('interpolates great-circle intermediate points between coordinates', () => {
    const p1 = { lat: 28.6139, lon: 77.209 }; // Delhi
    const p2 = { lat: 19.076, lon: 72.8777 }; // Mumbai
    const points = interpolateGreatCirclePoints(p1, p2, 10);

    expect(points.length).toBe(11);
    expect(points[0][0]).toBeCloseTo(77.209, 2);
    expect(points[0][1]).toBeCloseTo(28.6139, 2);
    expect(points[10][0]).toBeCloseTo(72.8777, 2);
    expect(points[10][1]).toBeCloseTo(19.076, 2);
  });

  it('creates a sensible fallback route with distance, duration, and fares', () => {
    const p1 = { lat: 12.9716, lon: 77.5946 };
    const p2 = { lat: 12.9352, lon: 77.6245 };
    const fallback = createFallbackRoute(p1, p2);

    expect(fallback.isFallback).toBe(true);
    expect(fallback.distanceMeters).toBeGreaterThan(0);
    expect(fallback.durationSeconds).toBeGreaterThan(0);
    expect(fallback.coordinates.length).toBeGreaterThan(5);
    expect(fallback.fares).toHaveLength(3);
  });

  it('parses valid OSRM response into RouteSummary and computes fares', async () => {
    const service = new OsrmRoutingService();

    const mockResponse = {
      code: 'Ok',
      routes: [
        {
          distance: 12450.5,
          duration: 1540.2,
          geometry: {
            coordinates: [
              [77.5946, 12.9716],
              [77.6000, 12.9600],
              [77.6245, 12.9352],
            ],
            type: 'LineString',
          },
        },
      ],
    };

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      json: async () => mockResponse,
    } as Response);

    const result = await service.getRoute(
      { lat: 12.9716, lon: 77.5946 },
      { lat: 12.9352, lon: 77.6245 },
    );

    expect(result.status).toBe('ok');
    if (result.status === 'ok') {
      expect(result.route.distanceMeters).toBe(12451);
      expect(result.route.distanceKm).toBe(12.45);
      expect(result.route.durationMinutes).toBe(26);
      expect(result.route.isFallback).toBe(false);
      expect(result.route.coordinates).toHaveLength(3);
      expect(result.route.fares).toHaveLength(3);
      // Rendezvous Go base 50 + 12.45*15(187) + 26*2(52) = 289
      expect(result.route.fares[0].totalFare).toBeGreaterThan(200);
    }
  });

  it('falls back gracefully to geodesic route when fetch throws network error', async () => {
    const service = new OsrmRoutingService();

    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new TypeError('Failed to fetch'));

    const result = await service.getRoute(
      { lat: 48.8566, lon: 2.3522 },
      { lat: 48.8584, lon: 2.2945 },
    );

    expect(result.status).toBe('ok');
    if (result.status === 'ok') {
      expect(result.route.isFallback).toBe(true);
      expect(result.route.distanceMeters).toBeGreaterThan(0);
      expect(result.route.fares).toHaveLength(3);
    }
  });

  it('aborts cleanly when AbortSignal is triggered', async () => {
    const service = new OsrmRoutingService();

    const controller = new AbortController();
    controller.abort();

    const result = await service.getRoute(
      { lat: 48.8566, lon: 2.3522 },
      { lat: 48.8584, lon: 2.2945 },
      controller.signal,
    );

    expect(result.status).toBe('error');
    if (result.status === 'error') {
      expect(result.message).toContain('aborted');
    }
  });

  it('validates coordinates and rejects invalid non-finite numbers', async () => {
    const service = new OsrmRoutingService();
    const result = await service.getRoute(
      { lat: NaN, lon: 2.3522 },
      { lat: 48.8584, lon: 2.2945 },
    );

    expect(result.status).toBe('error');
    if (result.status === 'error') {
      expect(result.message).toContain('Invalid origin or destination');
    }
  });
});
