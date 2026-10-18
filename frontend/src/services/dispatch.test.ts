/**
 * Phase 7D — Unit tests for the dispatch API client (services/dispatch.ts).
 *
 * All tests use vi.stubGlobal('fetch', ...) to mock the global fetch.
 * No real HTTP requests are made.
 *
 * Infrastructure-dependent tests (PostgreSQL, Kafka) are not included here
 * and belong in backend integration test classes.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cancelTrip, fetchEligibleDrivers, getRequestStatus, registerDriver, submitRideRequest } from './dispatch';
import type { DriverRegisterRequest, DispatchRequest } from '../types/dispatch';

// ── Helpers ───────────────────────────────────────────────────────────────────

function mockFetch(status: number, body: unknown): void {
  const response = new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response));
}

function abortSignal(): AbortSignal {
  return new AbortController().signal;
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

// ── registerDriver ────────────────────────────────────────────────────────────

describe('registerDriver', () => {
  const request: DriverRegisterRequest = {
    driverId: 'DRIVER-1',
    latitude: 51.5,
    longitude: -0.1,
    action: 'REGISTER',
    capturedAt: 1_700_000_000_000,
  };

  it('posts to /api/register and returns accepted response on 202', async () => {
    mockFetch(202, { driverId: 'DRIVER-1', message: 'Driver registration accepted and queued for processing' });

    const result = await registerDriver(request, abortSignal());

    expect(result.driverId).toBe('DRIVER-1');
    expect(result.message).toContain('accepted');

    const calls = vi.mocked(fetch).mock.calls;
    expect(calls).toHaveLength(1);
    const [url, init] = calls[0] as [string, RequestInit];
    expect(url).toBe('/api/register');
    expect(init.method).toBe('POST');
    const sent = JSON.parse(init.body as string);
    expect(sent.driverId).toBe('DRIVER-1');
    expect(sent.action).toBe('REGISTER');
  });

  it('throws with backend error message on 400', async () => {
    mockFetch(400, { status: 400, error: 'Bad Request', message: 'driverId must not be null or blank' });
    await expect(registerDriver(request, abortSignal())).rejects.toThrow('driverId must not be null or blank');
  });

  it('throws on 503 broker unavailable', async () => {
    mockFetch(503, { status: 503, error: 'Service Unavailable', message: 'Event could not be published' });
    await expect(registerDriver(request, abortSignal())).rejects.toThrow('Event could not be published');
  });
});

// ── submitRideRequest ─────────────────────────────────────────────────────────

describe('submitRideRequest', () => {
  const request: DispatchRequest = {
    requestId: 'req-abc123',
    latitude: 51.5,
    longitude: -0.1,
  };

  it('posts to /api/request and returns accepted response on 202', async () => {
    mockFetch(202, { requestId: 'req-abc123', message: 'Ride request accepted and queued for matching' });

    const result = await submitRideRequest(request, abortSignal());

    expect(result.requestId).toBe('req-abc123');
    expect(result.message).toContain('accepted');

    const [url, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/request');
    expect(JSON.parse(init.body as string).requestId).toBe('req-abc123');
  });

  it('throws with backend message on 400', async () => {
    mockFetch(400, { status: 400, error: 'Bad Request', message: 'requestId must not be null or blank' });
    await expect(submitRideRequest(request, abortSignal())).rejects.toThrow('requestId must not be null or blank');
  });
});

// ── getRequestStatus ──────────────────────────────────────────────────────────

describe('getRequestStatus', () => {
  it('returns PENDING response on 200', async () => {
    mockFetch(200, {
      requestId: 'req-1',
      status: 'PENDING',
      driverId: null,
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    });

    const result = await getRequestStatus('req-1', abortSignal());
    expect(result).not.toBeNull();
    expect(result!.status).toBe('PENDING');
    expect(result!.driverId).toBeNull();
  });

  it('returns MATCHED response with driverId on 200', async () => {
    mockFetch(200, {
      requestId: 'req-2',
      status: 'MATCHED',
      driverId: 'DRIVER-42',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
    });

    const result = await getRequestStatus('req-2', abortSignal());
    expect(result).not.toBeNull();
    expect(result!.status).toBe('MATCHED');
    expect(result!.driverId).toBe('DRIVER-42');
  });

  it('returns NO_DRIVERS response on 200', async () => {
    mockFetch(200, {
      requestId: 'req-3',
      status: 'NO_DRIVERS',
      driverId: null,
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
    });

    const result = await getRequestStatus('req-3', abortSignal());
    expect(result!.status).toBe('NO_DRIVERS');
  });

  it('returns null (not throws) when backend responds 404 (not yet persisted)', async () => {
    mockFetch(404, { status: 404, error: 'Not Found', message: 'No ride request found for requestId: req-x' });

    const result = await getRequestStatus('req-x', abortSignal());
    expect(result).toBeNull();
  });

  it('throws on 500 server error', async () => {
    mockFetch(500, { status: 500, error: 'Internal Server Error', message: 'An internal error occurred' });
    await expect(getRequestStatus('req-err', abortSignal())).rejects.toThrow();
  });

  it('URL-encodes the requestId in the path', async () => {
    mockFetch(200, {
      requestId: 'req/with/slashes',
      status: 'PENDING',
      driverId: null,
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    });

    await getRequestStatus('req/with/slashes', abortSignal());

    const [url] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toContain(encodeURIComponent('req/with/slashes'));
  });
});

// ── fetchEligibleDrivers ──────────────────────────────────────────────────────

describe('fetchEligibleDrivers', () => {
  it('calls GET /api/drivers and returns eligible driver array', async () => {
    const mockDrivers = [
      {
        driverId: 'D1',
        latitude: 17.385,
        longitude: 78.4867,
        status: 'AVAILABLE',
        updatedAt: '2026-10-18T10:00:00Z',
      },
    ];
    mockFetch(200, mockDrivers);

    const result = await fetchEligibleDrivers(abortSignal());
    expect(result).toHaveLength(1);
    expect(result[0].driverId).toBe('D1');
    expect(result[0].status).toBe('AVAILABLE');

    const [url, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/drivers');
    expect(init.method).toBe('GET');
  });

  it('throws when server returns error', async () => {
    mockFetch(500, { message: 'Internal registry error' });
    await expect(fetchEligibleDrivers(abortSignal())).rejects.toThrow('Internal registry error');
  });
});

// ── cancelTrip ────────────────────────────────────────────────────────────────

describe('cancelTrip', () => {
  it('posts to /api/trips/{requestId}/cancel with driverId and returns response', async () => {
    mockFetch(200, {
      requestId: 'req-1',
      driverId: 'D1',
      status: 'CANCELLED',
      outcome: 'TRANSITIONED',
      message: 'Trip successfully transitioned to CANCELLED',
    });

    const result = await cancelTrip('req-1', 'D1', abortSignal());
    expect(result.status).toBe('CANCELLED');
    expect(result.outcome).toBe('TRANSITIONED');

    const [url, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/trips/req-1/cancel');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ driverId: 'D1' });
  });

  it('throws on conflict (409) when trip cannot be cancelled', async () => {
    mockFetch(409, { message: 'Cannot cancel trip in IN_PROGRESS state' });
    await expect(cancelTrip('req-1', 'D1', abortSignal())).rejects.toThrow('Cannot cancel trip in IN_PROGRESS state');
  });
});

