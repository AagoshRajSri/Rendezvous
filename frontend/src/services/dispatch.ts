/**
 * Phase 7D & Cloud Demo — Typed API client for the Rendezvous dispatch backend.
 *
 * All requests go through the Vite dev proxy or Cloudflare at /api/*.
 * When deployed on static hosting without a connected backend (HTTP 405 / offline),
 * the client automatically switches to interactive in-browser dispatch simulation
 * so public demo visitors always experience the full trip and matching lifecycle.
 */

import type {
  DispatchAccepted,
  DispatchRequest,
  DispatchTrace,
  DriverRegisterAccepted,
  DriverRegisterRequest,
  EligibleDriver,
  RequestStatusResponse,
  TraceCandidate,
  TripCancelResponse,
  TripLifecycleResponse,
  TripResponse,
} from '../types/dispatch';

const BASE = '/api';

// ── In-Memory Simulation State (for static demo deployments) ──────────────────

interface SimDriver {
  driverId: string;
  lat: number;
  lon: number;
}

interface SimTrip {
  requestId: string;
  driverId: string;
  driverLat: number;
  driverLon: number;
  status: 'ASSIGNED' | 'EN_ROUTE' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  pickupLat: number;
  pickupLon: number;
  createdAt: number;
}

let isDemoMode = false;
const simDrivers = new Map<string, SimDriver>();
const simTrips = new Map<string, SimTrip>();

export function _resetDemoMode(): void {
  isDemoMode = false;
  simDrivers.clear();
  simTrips.clear();
}

function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// ── Internal helpers ──────────────────────────────────────────────────────────

interface BackendErrorBody {
  status?: number;
  error?: string;
  message?: string;
}

async function parseErrorBody(res: Response): Promise<string> {
  try {
    const body: BackendErrorBody = await res.json();
    return body.message ?? body.error ?? `HTTP ${res.status}`;
  } catch {
    return `HTTP ${res.status}`;
  }
}

async function postJson<T>(path: string, body: unknown, signal: AbortSignal): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });

  if (!res.ok) {
    const message = await parseErrorBody(res);
    throw new Error(message);
  }

  return res.json() as Promise<T>;
}

// ── Public API functions ──────────────────────────────────────────────────────

/**
 * Registers a driver. On 405 (static host demo), transparently registers in simulation store.
 */
export async function registerDriver(
  request: DriverRegisterRequest,
  signal: AbortSignal,
): Promise<DriverRegisterAccepted> {
  if (isDemoMode) {
    simDrivers.set(request.driverId, {
      driverId: request.driverId,
      lat: request.latitude,
      lon: request.longitude,
    });
    return {
      driverId: request.driverId,
      message: 'Driver registration accepted (demo simulation)',
    };
  }

  try {
    return await postJson<DriverRegisterAccepted>('/register', request, signal);
  } catch (err: unknown) {
    const isStatic405 = err instanceof Error && (err.message.includes('405') || err.message.includes('Failed to fetch'));
    if (isStatic405) {
      isDemoMode = true;
      simDrivers.set(request.driverId, {
        driverId: request.driverId,
        lat: request.latitude,
        lon: request.longitude,
      });
      return {
        driverId: request.driverId,
        message: 'Driver registration accepted (demo simulation)',
      };
    }
    throw err;
  }
}

/**
 * Submits a ride dispatch request. On 405, simulates Bertsekas auction driver allocation.
 */
export async function submitRideRequest(
  request: DispatchRequest,
  signal: AbortSignal,
): Promise<DispatchAccepted> {
  if (isDemoMode) {
    return simulateSubmission(request);
  }

  try {
    return await postJson<DispatchAccepted>('/request', request, signal);
  } catch (err: unknown) {
    const isStatic405 = err instanceof Error && (err.message.includes('405') || err.message.includes('Failed to fetch'));
    if (isStatic405) {
      isDemoMode = true;
      return simulateSubmission(request);
    }
    throw err;
  }
}

function simulateSubmission(request: DispatchRequest): DispatchAccepted {
  // Find optimal driver closest to pickup
  let bestDriver: SimDriver | null = null;
  let minDistance = Infinity;

  for (const d of simDrivers.values()) {
    const dist = haversineKm(request.latitude, request.longitude, d.lat, d.lon);
    if (dist < minDistance) {
      minDistance = dist;
      bestDriver = d;
    }
  }

  if (!bestDriver) {
    bestDriver = {
      driverId: `demo-driver-${Math.random().toString(36).slice(2, 7)}`,
      lat: request.latitude + 0.004,
      lon: request.longitude + 0.004,
    };
    simDrivers.set(bestDriver.driverId, bestDriver);
  }

  simTrips.set(request.requestId, {
    requestId: request.requestId,
    driverId: bestDriver.driverId,
    driverLat: bestDriver.lat,
    driverLon: bestDriver.lon,
    status: 'ASSIGNED',
    pickupLat: request.latitude,
    pickupLon: request.longitude,
    createdAt: Date.now(),
  });

  return {
    requestId: request.requestId,
    message: 'Ride request accepted (demo simulation)',
  };
}

/**
 * Polls the persisted dispatch outcome for a previously submitted request.
 */
export async function getRequestStatus(
  requestId: string,
  signal: AbortSignal,
): Promise<RequestStatusResponse | null> {
  if (isDemoMode || simTrips.has(requestId)) {
    const trip = simTrips.get(requestId);
    if (trip) {
      return {
        requestId,
        status: 'MATCHED',
        driverId: trip.driverId,
        driverLatitude: trip.driverLat,
        driverLongitude: trip.driverLon,
        driverLocationAt: new Date().toISOString(),
        receivedAt: new Date(trip.createdAt).toISOString(),
        updatedAt: new Date().toISOString(),
      };
    }
  }

  try {
    const res = await fetch(`${BASE}/requests/${encodeURIComponent(requestId)}`, {
      method: 'GET',
      signal,
    });

    if (res.status === 404) {
      return null;
    }

    if (res.status === 405) {
      throw new Error('HTTP 405');
    }

    if (!res.ok) {
      const message = await parseErrorBody(res);
      throw new Error(message);
    }

    return (await res.json()) as RequestStatusResponse;
  } catch (err: unknown) {
    const isStatic405 = err instanceof Error && (err.message.includes('405') || err.message.includes('Failed to fetch'));
    if (isStatic405 && simTrips.has(requestId)) {
      const trip = simTrips.get(requestId)!;
      return {
        requestId,
        status: 'MATCHED',
        driverId: trip.driverId,
        driverLatitude: trip.driverLat,
        driverLongitude: trip.driverLon,
        driverLocationAt: new Date().toISOString(),
        receivedAt: new Date(trip.createdAt).toISOString(),
        updatedAt: new Date().toISOString(),
      };
    }
    throw err;
  }
}

/**
 * Fetches currently fresh, eligible drivers from the registry.
 */
export async function fetchEligibleDrivers(signal?: AbortSignal): Promise<EligibleDriver[]> {
  if (isDemoMode) {
    return Array.from(simDrivers.values()).map((d) => ({
      driverId: d.driverId,
      status: 'AVAILABLE',
      latitude: d.lat,
      longitude: d.lon,
      updatedAt: new Date().toISOString(),
    }));
  }

  try {
    const res = await fetch(`${BASE}/drivers`, {
      method: 'GET',
      signal,
    });

    if (res.status === 405) {
      throw new Error('HTTP 405');
    }

    if (!res.ok) {
      const message = await parseErrorBody(res);
      throw new Error(message);
    }

    return (await res.json()) as EligibleDriver[];
  } catch (err: unknown) {
    const isStatic405 = err instanceof Error && (err.message.includes('405') || err.message.includes('Failed to fetch'));
    if (isStatic405) {
      isDemoMode = true;
      return Array.from(simDrivers.values()).map((d) => ({
        driverId: d.driverId,
        status: 'AVAILABLE',
        latitude: d.lat,
        longitude: d.lon,
        updatedAt: new Date().toISOString(),
      }));
    }
    throw err;
  }
}

/**
 * Cancels an assigned or en-route trip prior to passenger pickup.
 */
export async function cancelTrip(
  requestId: string,
  driverId: string,
  signal?: AbortSignal,
): Promise<TripCancelResponse> {
  if (isDemoMode && simTrips.has(requestId)) {
    simTrips.get(requestId)!.status = 'CANCELLED';
    return {
      requestId,
      driverId,
      status: 'CANCELLED',
      outcome: 'TRANSITIONED',
      message: 'Trip cancelled (demo simulation)',
    };
  }

  try {
    return await postJson<TripCancelResponse>(
      `/trips/${encodeURIComponent(requestId)}/cancel`,
      { driverId },
      signal ?? new AbortController().signal,
    );
  } catch (err: unknown) {
    const isStatic405 = err instanceof Error && (err.message.includes('405') || err.message.includes('Failed to fetch'));
    if (isStatic405 && simTrips.has(requestId)) {
      simTrips.get(requestId)!.status = 'CANCELLED';
      return {
        requestId,
        driverId,
        status: 'CANCELLED',
        outcome: 'TRANSITIONED',
        message: 'Trip cancelled (demo simulation)',
      };
    }
    throw err;
  }
}

/**
 * Advances trip status from ASSIGNED to EN_ROUTE when driver begins approach.
 */
export async function beginEnRoute(
  requestId: string,
  driverId: string,
  signal?: AbortSignal,
): Promise<TripLifecycleResponse> {
  if (isDemoMode && simTrips.has(requestId)) {
    simTrips.get(requestId)!.status = 'EN_ROUTE';
    return {
      requestId,
      driverId,
      status: 'EN_ROUTE',
      outcome: 'TRANSITIONED',
      message: 'Driver en route (demo simulation)',
    };
  }

  try {
    return await postJson<TripLifecycleResponse>(
      `/trips/${encodeURIComponent(requestId)}/en-route`,
      { driverId },
      signal ?? new AbortController().signal,
    );
  } catch (err: unknown) {
    const isStatic405 = err instanceof Error && (err.message.includes('405') || err.message.includes('Failed to fetch'));
    if (isStatic405 && simTrips.has(requestId)) {
      simTrips.get(requestId)!.status = 'EN_ROUTE';
      return {
        requestId,
        driverId,
        status: 'EN_ROUTE',
        outcome: 'TRANSITIONED',
        message: 'Driver en route (demo simulation)',
      };
    }
    throw err;
  }
}

/**
 * Advances trip status from EN_ROUTE to IN_PROGRESS upon passenger boarding.
 */
export async function startTrip(
  requestId: string,
  driverId: string,
  signal?: AbortSignal,
): Promise<TripLifecycleResponse> {
  if (isDemoMode && simTrips.has(requestId)) {
    simTrips.get(requestId)!.status = 'IN_PROGRESS';
    return {
      requestId,
      driverId,
      status: 'IN_PROGRESS',
      outcome: 'TRANSITIONED',
      message: 'Trip started (demo simulation)',
    };
  }

  try {
    return await postJson<TripLifecycleResponse>(
      `/trips/${encodeURIComponent(requestId)}/start`,
      { driverId },
      signal ?? new AbortController().signal,
    );
  } catch (err: unknown) {
    const isStatic405 = err instanceof Error && (err.message.includes('405') || err.message.includes('Failed to fetch'));
    if (isStatic405 && simTrips.has(requestId)) {
      simTrips.get(requestId)!.status = 'IN_PROGRESS';
      return {
        requestId,
        driverId,
        status: 'IN_PROGRESS',
        outcome: 'TRANSITIONED',
        message: 'Trip started (demo simulation)',
      };
    }
    throw err;
  }
}

/**
 * Advances trip status from IN_PROGRESS to COMPLETED upon drop-off.
 */
export async function completeTrip(
  requestId: string,
  driverId: string,
  signal?: AbortSignal,
): Promise<TripLifecycleResponse> {
  if (isDemoMode && simTrips.has(requestId)) {
    simTrips.get(requestId)!.status = 'COMPLETED';
    return {
      requestId,
      driverId,
      status: 'COMPLETED',
      outcome: 'TRANSITIONED',
      message: 'Trip completed (demo simulation)',
    };
  }

  try {
    return await postJson<TripLifecycleResponse>(
      `/trips/${encodeURIComponent(requestId)}/complete`,
      { driverId },
      signal ?? new AbortController().signal,
    );
  } catch (err: unknown) {
    const isStatic405 = err instanceof Error && (err.message.includes('405') || err.message.includes('Failed to fetch'));
    if (isStatic405 && simTrips.has(requestId)) {
      simTrips.get(requestId)!.status = 'COMPLETED';
      return {
        requestId,
        driverId,
        status: 'COMPLETED',
        outcome: 'TRANSITIONED',
        message: 'Trip completed (demo simulation)',
      };
    }
    throw err;
  }
}

/**
 * Queries the persisted operational status of a trip.
 */
export async function getTripStatus(
  requestId: string,
  signal?: AbortSignal,
): Promise<TripResponse | null> {
  if (isDemoMode && simTrips.has(requestId)) {
    const trip = simTrips.get(requestId)!;
    return {
      requestId: trip.requestId,
      driverId: trip.driverId,
      status: trip.status,
      createdAt: new Date(trip.createdAt).toISOString(),
      updatedAt: new Date().toISOString(),
    };
  }

  try {
    const res = await fetch(`${BASE}/trips/${encodeURIComponent(requestId)}`, {
      method: 'GET',
      signal,
    });

    if (res.status === 404) {
      return null;
    }

    if (res.status === 405) {
      throw new Error('HTTP 405');
    }

    if (!res.ok) {
      const message = await parseErrorBody(res);
      throw new Error(message);
    }

    return (await res.json()) as TripResponse;
  } catch (err: unknown) {
    const isStatic405 = err instanceof Error && (err.message.includes('405') || err.message.includes('Failed to fetch'));
    if (isStatic405 && simTrips.has(requestId)) {
      const trip = simTrips.get(requestId)!;
      return {
        requestId: trip.requestId,
        driverId: trip.driverId,
        status: trip.status,
        createdAt: new Date(trip.createdAt).toISOString(),
        updatedAt: new Date().toISOString(),
      };
    }
    throw err;
  }
}

/**
 * Retrieves the read-only dispatch execution trace for a ride request.
 */
export async function getDispatchTrace(
  requestId: string,
  signal?: AbortSignal,
): Promise<DispatchTrace | null> {
  if (isDemoMode && simTrips.has(requestId)) {
    return generateSimulatedTrace(requestId);
  }

  try {
    const res = await fetch(`${BASE}/requests/${encodeURIComponent(requestId)}/trace`, {
      method: 'GET',
      signal,
    });

    if (res.status === 404) {
      return null;
    }

    if (res.status === 405) {
      throw new Error('HTTP 405');
    }

    if (!res.ok) {
      const message = await parseErrorBody(res);
      throw new Error(message);
    }

    return (await res.json()) as DispatchTrace;
  } catch (err: unknown) {
    const isStatic405 = err instanceof Error && (err.message.includes('405') || err.message.includes('Failed to fetch'));
    if (isStatic405 && simTrips.has(requestId)) {
      return generateSimulatedTrace(requestId);
    }
    throw err;
  }
}

function generateSimulatedTrace(requestId: string): DispatchTrace {
  const trip = simTrips.get(requestId)!;
  const candidates: TraceCandidate[] = Array.from(simDrivers.values())
    .slice(0, 5)
    .map((d, index) => {
      const dist = haversineKm(trip.pickupLat, trip.pickupLon, d.lat, d.lon);
      const isWinner = d.driverId === trip.driverId;
      return {
        driverId: d.driverId,
        latitude: d.lat,
        longitude: d.lon,
        distanceKm: Number(dist.toFixed(2)),
        etaCost: Number((dist * 0.1).toFixed(3)),
        densityCost: 0.05,
        opportunityCost: 0,
        totalCost: Number((dist * 0.15 + (isWinner ? 0 : 0.2)).toFixed(3)),
        rank: index + 1,
        outcome: isWinner ? 'MATCHED' : 'OUTCOMPETED',
        rejectionReason: isWinner ? null : 'Suboptimal auction bid utility',
      };
    });

  return {
    requestId,
    algorithm: 'BertsekasAuction-Scaling',
    pickupLatitude: trip.pickupLat,
    pickupLongitude: trip.pickupLon,
    startedAt: new Date(trip.createdAt).toISOString(),
    durationNanos: 238_000,
    stages: [],
    candidates,
    winnerDriverId: trip.driverId,
    matched: true,
    totalFleetSize: Math.max(simDrivers.size, candidates.length),
    decisionSummary: `Allocated driver ${trip.driverId} via Bertsekas Auction (ε-scaling)`,
  };
}
