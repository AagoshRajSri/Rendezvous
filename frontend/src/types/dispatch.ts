/**
 * Phase 7E.2 - Dispatch domain types for the frontend API client and hook.
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
 * Phase 11.2 - Fresh eligible driver response from GET /api/drivers.
 */
export interface EligibleDriver {
  driverId: string;
  latitude: number;
  longitude: number;
  status: 'AVAILABLE' | 'CLAIMED' | 'EN_ROUTE' | 'ON_TRIP' | string;
  updatedAt: string;
}

/**
 * Phase 11.3 - Response payload detailing the outcome of a lifecycle transition attempt.
 * Matches backend TripLifecycleResponse record.
 */
export interface TripLifecycleResponse {
  requestId: string;
  driverId: string;
  status: 'ASSIGNED' | 'EN_ROUTE' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | string;
  outcome: 'TRANSITIONED' | 'ALREADY_IN_TARGET_STATE' | string;
  message: string;
}

/** Legacy alias for backwards compatibility */
export type TripCancelResponse = TripLifecycleResponse;

/**
 * Phase 11.3 - Response payload from GET /api/trips/{requestId}.
 * Matches backend TripResponse record.
 */
export interface TripResponse {
  requestId: string;
  driverId: string;
  status: 'ASSIGNED' | 'EN_ROUTE' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | string;
  createdAt: string;
  updatedAt: string;
}

/**
 * HTTP 200 response from GET /api/requests/{requestId}.
 *
 * status values:
 *   PENDING - queued, matching in progress
 *   MATCHED - driver assigned; driverId is populated
 *   NO_DRIVERS - terminal; no driver was available
 *
 * Phase 7E.2: Optional driver telemetry fields are present when the backend
 * has a fresh GPS fix for the matched driver in its registry at query time.
 * These represent the last-known verified fix as of the status response - 
 * not a continuously live position. Telemetry may be null even when status
 * is MATCHED; null telemetry does not invalidate the match.
 */
export interface RequestStatusResponse {
  requestId: string;
  /** PENDING | MATCHED | NO_DRIVERS */
  status: RequestStatus;
  /** Only present when status === 'MATCHED'. */
  driverId: string | null;
  receivedAt: string; // ISO-8601
  updatedAt: string;  // ISO-8601
  /**
   * Phase 7E.2 - WGS-84 latitude of the matched driver's last-known fresh fix.
   * Only present when the backend has a verified, non-stale GPS fix.
   * May be null even when status === 'MATCHED'.
   */
  driverLatitude?: number | null;
  /**
   * Phase 7E.2 - WGS-84 longitude of the matched driver's last-known fresh fix.
   * Only present when the backend has a verified, non-stale GPS fix.
   * May be null even when status === 'MATCHED'.
   */
  driverLongitude?: number | null;
  /**
   * Phase 7E.2 - ISO-8601 timestamp at which the driver fix was captured.
   * Only present when driverLatitude and driverLongitude are present.
   */
  driverLocationAt?: string | null;
}

export type RequestStatus = 'PENDING' | 'MATCHED' | 'NO_DRIVERS';

// ── Frontend dispatch state machine ──────────────────────────────────────────

export type DispatchPhase =
  | 'idle'
  | 'registering'   // POST /api/register in flight
  | 'submitting'    // POST /api/request in flight
  | 'polling'       // GET  /api/requests/{id} - awaiting terminal result
  | 'matched'       // match confirmed
  | 'en_route'      // Phase 11.3: driver en route to pickup (POST /api/trips/{id}/en-route confirmed)
  | 'arrived'       // Phase 11.3: driver arrived at pickup, waiting for boarding
  | 'in_progress'   // Phase 11.3: passenger boarded, ride in progress (POST /api/trips/{id}/start confirmed)
  | 'completed'     // Phase 11.3: ride completed (POST /api/trips/{id}/complete confirmed)
  | 'cancelled'     // Phase 11.2: ride cancelled prior to pickup
  | 'no_drivers'    // terminal: no driver available
  | 'error';        // terminal: network or server error

export interface DispatchState {
  phase: DispatchPhase;
  /** Retained after POST /api/request returns 202 */
  requestId: string | null;
  /** Confirmed backend driver ID (only when phase === 'matched') */
  matchedDriverId: string | null;
  /**
   * Phase 7E.2 - Verified coordinates of the matched driver's last-known fresh
   * fix, extracted and validated from the status response at match time.
   * Null when the backend does not supply a valid GPS fix for the matched driver.
   * Permits (0, 0) as a valid coordinate pair (Null Island).
   */
  matchedDriverLocation: { lat: number; lon: number } | null;
  /**
   * Phase 7E.2 - ISO-8601 timestamp at which the driver fix was captured,
   * as reported by the backend. Null when matchedDriverLocation is null.
   */
  matchedDriverLocationAt: string | null;
  /** Human-readable error message (only when phase === 'error') */
  errorMessage: string | null;
  /** ISO-8601 timestamp of the last status update */
  updatedAt: string | null;
}

// ── Phase 11.4D - Dispatch Trace API types ────────────────────────────────────

/**
 * Lifecycle and evaluation outcome of a driver candidate.
 * Mirrors the backend CandidateOutcome enum exactly.
 */
export type CandidateOutcome =
  | 'MATCHED'
  | 'OUTCOMPETED'
  | 'UNAVAILABLE'
  | 'CLAIM_LOST'
  | 'UNMATCHED';

/**
 * One spatial exploration or refinement stage recorded during dispatch search.
 * Matches backend SpatialStage record.
 *
 * Bounding-box fields (minLatitude … maxLongitude) are null when the stage
 * did not expand to an explicit bounding box.
 */
export interface SpatialStage {
  stageIndex: number;
  stageType: string;
  windowSize: number;
  minLatitude: number | null;
  minLongitude: number | null;
  maxLatitude: number | null;
  maxLongitude: number | null;
  visitedNodeCount: number;
  prunedNodeCount: number;
  candidatesDiscovered: number;
}

/**
 * A single driver candidate evaluated during the dispatch matching process.
 * Matches backend TraceCandidate record.
 *
 * Cost components (etaCost … totalCost) are null when the matcher did not
 * compute a full breakdown (e.g. the candidate was filtered before scoring).
 * rejectionReason is null for the matched winner.
 */
export interface TraceCandidate {
  driverId: string;
  latitude: number;
  longitude: number;
  distanceKm: number;
  etaCost: number | null;
  densityCost: number | null;
  opportunityCost: number | null;
  totalCost: number | null;
  rank: number;
  outcome: CandidateOutcome;
  rejectionReason: string | null;
}

/**
 * Complete, read-only dispatch execution trace for a single ride request.
 * HTTP 200 response from GET /api/requests/{requestId}/trace.
 * Matches backend DispatchTrace record.
 *
 * winnerDriverId is null when no driver was matched (matched === false).
 * startedAt is an ISO-8601 instant serialized as a string by Jackson.
 */
export interface DispatchTrace {
  requestId: string;
  algorithm: string;
  pickupLatitude: number;
  pickupLongitude: number;
  /** ISO-8601 instant - Jackson serializes java.time.Instant as a string. */
  startedAt: string;
  durationNanos: number;
  stages: SpatialStage[];
  candidates: TraceCandidate[];
  /** null when matched === false. */
  winnerDriverId: string | null;
  matched: boolean;
  totalFleetSize: number;
  decisionSummary: string;
}
