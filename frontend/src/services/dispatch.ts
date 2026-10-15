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
  DriverRegisterAccepted,
  DriverRegisterRequest,
  RequestStatusResponse,
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
