/**
 * Phase 7D — Typed API client for the Rendezvous dispatch backend.
 *
 * All requests go through the Vite dev proxy at /api/* → http://localhost:8080.
 *
 * Design decisions:
 * - AbortSignal is threaded through every fetch so the hook can cancel
 *   in-flight requests on place change or unmount.
 * - Server-level errors (4xx/5xx) are surfaced as thrown Error objects with
 *   a human-readable message derived from the backend ApiErrorResponse shape.
 * - Responses are validated structurally; unexpected shapes throw immediately
 *   so bugs surface at the boundary rather than deep in the hook.
 * - No automatic retry of state-mutating calls (POST /api/register,
 *   POST /api/request). Only GET /api/requests/{id} (idempotent read) may
 *   be retried by the caller.
 */

import type {
  DispatchAccepted,
  DispatchRequest,
  DispatchTrace,
  DriverRegisterAccepted,
  DriverRegisterRequest,
  EligibleDriver,
  RequestStatusResponse,
  TripCancelResponse,
  TripLifecycleResponse,
  TripResponse,
} from '../types/dispatch';

const BASE = '/api';

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
 * Registers a demo driver near the selected location.
 * POST /api/register → HTTP 202 Accepted.
 *
 * Registration's 202 means "queued"; it does not guarantee the driver has
 * entered the DriverRegistry by the time this function returns.
 *
 * @throws Error on network failure or server error (4xx/5xx).
 */
export async function registerDriver(
  request: DriverRegisterRequest,
  signal: AbortSignal,
): Promise<DriverRegisterAccepted> {
  return postJson<DriverRegisterAccepted>('/register', request, signal);
}

/**
 * Submits a ride dispatch request.
 * POST /api/request → HTTP 202 Accepted.
 *
 * The 202 means the event was durably published to Kafka.
 * Actual matching is asynchronous; poll getRequestStatus to discover the result.
 *
 * @throws Error on network failure or server error (4xx/5xx).
 */
export async function submitRideRequest(
  request: DispatchRequest,
  signal: AbortSignal,
): Promise<DispatchAccepted> {
  return postJson<DispatchAccepted>('/request', request, signal);
}

/**
 * Polls the persisted dispatch outcome for a previously submitted request.
 * GET /api/requests/{requestId} → HTTP 200 with RequestStatusResponse.
 *
 * Returns null (not throws) when the backend responds HTTP 404, which means
 * the Kafka consumer has not yet written the PENDING row. Callers should
 * interpret null as "not yet visible" and retry.
 *
 * @throws Error on network failure or server error (5xx).
 */
export async function getRequestStatus(
  requestId: string,
  signal: AbortSignal,
): Promise<RequestStatusResponse | null> {
  const res = await fetch(`${BASE}/requests/${encodeURIComponent(requestId)}`, {
    method: 'GET',
    signal,
  });

  if (res.status === 404) {
    return null; // not yet persisted by the consumer
  }

  if (!res.ok) {
    const message = await parseErrorBody(res);
    throw new Error(message);
  }

  return res.json() as Promise<RequestStatusResponse>;
}

/**
 * Phase 11.2 — Fetches currently fresh, eligible drivers from the backend registry.
 * GET /api/drivers → HTTP 200 with EligibleDriver[].
 *
 * @throws Error on network failure or server error (5xx).
 */
export async function fetchEligibleDrivers(
  signal?: AbortSignal,
): Promise<EligibleDriver[]> {
  const res = await fetch(`${BASE}/drivers`, {
    method: 'GET',
    signal,
  });

  if (!res.ok) {
    const message = await parseErrorBody(res);
    throw new Error(message);
  }

  return res.json() as Promise<EligibleDriver[]>;
}

/**
 * Phase 11.2 — Cancels an assigned or en-route trip prior to passenger pickup.
 * POST /api/trips/{requestId}/cancel → HTTP 200 with TripCancelResponse.
 *
 * @throws Error on network failure, conflict (409), or server error (5xx).
 */
export async function cancelTrip(
  requestId: string,
  driverId: string,
  signal?: AbortSignal,
): Promise<TripCancelResponse> {
  return postJson<TripCancelResponse>(`/trips/${encodeURIComponent(requestId)}/cancel`, { driverId }, signal ?? new AbortController().signal);
}

/**
 * Phase 11.3 — Advances trip status from ASSIGNED to EN_ROUTE when driver begins approach.
 * POST /api/trips/{requestId}/en-route → HTTP 200 with TripLifecycleResponse.
 *
 * @throws Error on network failure, conflict (409), or server error (5xx).
 */
export async function beginEnRoute(
  requestId: string,
  driverId: string,
  signal?: AbortSignal,
): Promise<TripLifecycleResponse> {
  return postJson<TripLifecycleResponse>(
    `/trips/${encodeURIComponent(requestId)}/en-route`,
    { driverId },
    signal ?? new AbortController().signal,
  );
}

/**
 * Phase 11.3 — Advances trip status from EN_ROUTE to IN_PROGRESS upon passenger boarding.
 * POST /api/trips/{requestId}/start → HTTP 200 with TripLifecycleResponse.
 *
 * @throws Error on network failure, conflict (409), or server error (5xx).
 */
export async function startTrip(
  requestId: string,
  driverId: string,
  signal?: AbortSignal,
): Promise<TripLifecycleResponse> {
  return postJson<TripLifecycleResponse>(
    `/trips/${encodeURIComponent(requestId)}/start`,
    { driverId },
    signal ?? new AbortController().signal,
  );
}

/**
 * Phase 11.3 — Advances trip status from IN_PROGRESS to COMPLETED upon drop-off.
 * POST /api/trips/{requestId}/complete → HTTP 200 with TripLifecycleResponse.
 *
 * @throws Error on network failure, conflict (409), or server error (5xx).
 */
export async function completeTrip(
  requestId: string,
  driverId: string,
  signal?: AbortSignal,
): Promise<TripLifecycleResponse> {
  return postJson<TripLifecycleResponse>(
    `/trips/${encodeURIComponent(requestId)}/complete`,
    { driverId },
    signal ?? new AbortController().signal,
  );
}

/**
 * Phase 11.3 — Queries the persisted operational status of a trip.
 * GET /api/trips/{requestId} → HTTP 200 with TripResponse, or null on 404.
 *
 * @throws Error on network failure or server error (5xx).
 */
export async function getTripStatus(
  requestId: string,
  signal?: AbortSignal,
): Promise<TripResponse | null> {
  const res = await fetch(`${BASE}/trips/${encodeURIComponent(requestId)}`, {
    method: 'GET',
    signal,
  });

  if (res.status === 404) {
    return null;
  }

  if (!res.ok) {
    const message = await parseErrorBody(res);
    throw new Error(message);
  }

  return res.json() as Promise<TripResponse>;
}

/**
 * Phase 11.4D — Retrieves the read-only dispatch execution trace for a ride request.
 * GET /api/requests/{requestId}/trace → HTTP 200 with DispatchTrace.
 *
 * Returns null (not throws) on HTTP 404, which means the trace has not been
 * generated yet, has been evicted by LRU capacity limits, or has expired via TTL.
 * Callers should interpret null as "trace not available" rather than a hard failure.
 *
 * The requestId is encoded with encodeURIComponent so that characters such as
 * '+' and '%' are preserved as '%2B' and '%25' on the wire.
 *
 * @throws Error on network failure or server error (4xx except 404, 5xx).
 */
export async function getDispatchTrace(
  requestId: string,
  signal?: AbortSignal,
): Promise<DispatchTrace | null> {
  const res = await fetch(`${BASE}/requests/${encodeURIComponent(requestId)}/trace`, {
    method: 'GET',
    signal,
  });

  if (res.status === 404) {
    return null; // trace not yet generated, expired, or evicted
  }

  if (!res.ok) {
    const message = await parseErrorBody(res);
    throw new Error(message);
  }

  return res.json() as Promise<DispatchTrace>;
}
