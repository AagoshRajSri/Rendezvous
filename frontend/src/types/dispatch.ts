/**
 * Phase 7D — Dispatch domain types for the frontend API client and hook.
 *
 * These types mirror the backend JSON contracts for:
 *   POST /api/request           → DispatchRequest / DispatchAccepted
 *   POST /api/register          → DriverRegisterRequest / DriverRegisterAccepted
 *   GET  /api/requests/{id}     → RequestStatusResponse
 */

// ── Request payloads ─────────────────────────────────────────────────────────

/** Payload for POST /api/register */
export interface DriverRegisterRequest {
  driverId: string;
  latitude: number;
  longitude: number;
  action: 'REGISTER';
  capturedAt?: number; // epoch-ms, optional
}

/** Payload for POST /api/request */
export interface DispatchRequest {
  requestId: string;
  latitude: number;
  longitude: number;
}

// ── Response payloads ────────────────────────────────────────────────────────

/** HTTP 202 response from POST /api/register */
export interface DriverRegisterAccepted {
  driverId: string;
  message: string;
}

/** HTTP 202 response from POST /api/request */
export interface DispatchAccepted {
  requestId: string;
  message: string;
}

/**
 * HTTP 200 response from GET /api/requests/{requestId}.
 *
 * status values:
 *   PENDING    – queued, matching in progress
 *   MATCHED    – driver assigned; driverId is populated
 *   NO_DRIVERS – terminal; no driver was available
 */
export interface RequestStatusResponse {
  requestId: string;
  /** PENDING | MATCHED | NO_DRIVERS */
  status: RequestStatus;
  /** Only present when status === 'MATCHED'. */
  driverId: string | null;
  receivedAt: string; // ISO-8601
  updatedAt: string;  // ISO-8601
}

export type RequestStatus = 'PENDING' | 'MATCHED' | 'NO_DRIVERS';

// ── Frontend dispatch state machine ──────────────────────────────────────────

export type DispatchPhase =
  | 'idle'
  | 'registering'   // POST /api/register in flight
  | 'submitting'    // POST /api/request in flight
  | 'polling'       // GET  /api/requests/{id} — awaiting terminal result
  | 'matched'       // terminal: driver found
  | 'no_drivers'    // terminal: no driver available
  | 'error';        // terminal: network or server error

export interface DispatchState {
  phase: DispatchPhase;
  /** Retained after POST /api/request returns 202 */
  requestId: string | null;
  /** Confirmed backend driver ID (only when phase === 'matched') */
  matchedDriverId: string | null;
  /**
   * Verified coordinates of the matched driver, if supplied by the backend.
   * Null when the backend does not expose driver coordinates.
   */
  matchedDriverLocation: { lat: number; lon: number } | null;
  /** Human-readable error message (only when phase === 'error') */
  errorMessage: string | null;
  /** ISO-8601 timestamp of the last status update */
  updatedAt: string | null;
}
