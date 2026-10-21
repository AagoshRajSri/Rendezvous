import type { DispatchState } from '../types/dispatch';
import type { PlaceSuggestion } from '../types/place';
import type { RouteSummary } from '../types/routing';
import { formatDistance as formatDistanceUtil, formatDuration as formatDurationUtil } from '../utils/fare';
import { useDispatchTrace, type DispatchTraceState } from '../hooks/useDispatchTrace';
import { DispatchTraceCard } from './DispatchTraceCard';
import './DispatchPanel.css';

interface DispatchPanelProps {
  placeName: string;
  dispatchState: DispatchState;
  onRequestDispatch: () => void;
  onCancelRide?: () => void;
  /** Phase 11.2: Whether cancellation request is in-flight. */
  isCancelling?: boolean;
  onReset: () => void;
  /** Phase 11.2: Approach route status. */
  approachStatus?: 'idle' | 'loading' | 'ready' | 'error';
  /** Phase 11.2 & 11.3: Live road remaining distance in meters. */
  remainingDistanceMeters?: number;
  /** Phase 11.2 & 11.3: Live ETA countdown in seconds. */
  remainingDurationSeconds?: number;
  /** Phase 11.2: Whether animated vehicle has reached pickup pin. */
  isVehicleArrived?: boolean;

  // ── Phase 11.3 props ──────────────────────────────────────────────────────
  /** Rider action: confirm boarding and advance to in_progress. */
  onStartTrip?: () => void;
  /** Whether start trip request is in-flight. */
  isStarting?: boolean;
  /** Whether start trip is allowed (false if missing destination or road route). */
  canStartTrip?: boolean;
  /** Message explaining why start trip is blocked when destination is missing. */
  missingDestinationMessage?: string | null;
  /** Complete trip action upon reaching destination. */
  onCompleteTrip?: () => void;
  /** Whether complete trip request is in-flight. */
  isCompleting?: boolean;
  /** Selected destination place. */
  destinationPlace?: PlaceSuggestion | null;
  /** Calculated road route to destination. */
  route?: RouteSummary | null;
  /** Chosen vehicle fare tier. */
  selectedTier?: 'standard' | 'premier' | 'xl';
  /** Phase 11.4F: Optional lifted dispatch trace state from App. */
  traceState?: DispatchTraceState;
  /** Phase 11.4F: Optional lifted refresh callback. */
  onRefreshTrace?: () => void;
  /** Phase 11.4F: Whether the trace accordion is currently expanded. */
  isTraceExpanded?: boolean;
  /** Phase 11.4F: Callback when trace accordion is expanded or collapsed. */
  onToggleTraceExpand?: (expanded: boolean) => void;
  /** Phase 11.4E: Whether dispatch trace accordion defaults to expanded (e.g. for testing). */
  defaultTraceExpanded?: boolean;
}

/**
 * Phase 11.3 - Real dispatch action & driver tracking panel with full trip lifecycle.
 */
function formatValidTime(isoString: string | null | undefined): string | null {
  if (!isoString) return null;
  const d = new Date(isoString);
  if (isNaN(d.getTime())) return null;
  return d.toLocaleTimeString();
}

function formatDistance(meters?: number): string {
  if (meters === undefined || meters === null) return '--';
  if (meters >= 1000) {
    return `${(meters / 1000).toFixed(1)} km`;
  }
  return `${meters} m`;
}

function formatEta(seconds?: number): string {
  if (seconds === undefined || seconds === null) return '--';
  if (seconds <= 0) return 'Arriving now';
  if (seconds < 60) return `${seconds}s`;
  const mins = Math.ceil(seconds / 60);
  return `~${mins} min${mins > 1 ? 's' : ''}`;
}

export function DispatchPanel({
  placeName,
  dispatchState,
  onRequestDispatch,
  onCancelRide,
  isCancelling = false,
  onReset,
  approachStatus = 'idle',
  remainingDistanceMeters,
  remainingDurationSeconds,
  isVehicleArrived = false,
  onStartTrip,
  isStarting = false,
  canStartTrip = true,
  missingDestinationMessage = null,
  onCompleteTrip,
  isCompleting = false,
  destinationPlace = null,
  route = null,
  selectedTier = 'standard',
  traceState: propTraceState,
  onRefreshTrace,
  isTraceExpanded,
  onToggleTraceExpand,
  defaultTraceExpanded = false,
}: DispatchPanelProps) {
  const {
    phase,
    requestId,
    matchedDriverId,
    matchedDriverLocation,
    matchedDriverLocationAt,
    errorMessage,
    updatedAt,
  } = dispatchState;
  const formattedFixTime = formatValidTime(matchedDriverLocationAt);
  const formattedUpdateTime = formatValidTime(updatedAt);

  // Phase 11.4F - Single-fetch dispatch trace lifted to App, with fallback for backward compatibility
  const internalTrace = useDispatchTrace(propTraceState ? null : requestId);
  const traceState = propTraceState ?? internalTrace.state;
  const refreshTrace = onRefreshTrace ?? internalTrace.refresh;

  // Selected fare tier details for summary card
  const chosenFare = route?.fares?.find((f) => f.tierId === selectedTier) ?? route?.fares?.[0];

  return (
    <div className="dispatch-panel" role="region" aria-label="Real dispatch panel">
      {/* ── Header ──────────────────────────────────────────────────────── */}
      <div className="dispatch-panel__header">
        <span className="dispatch-panel__icon" aria-hidden="true">
          {phase === 'completed'
            ? '🏁'
            : phase === 'in_progress'
              ? '🚗'
              : phase === 'arrived'
                ? '📍'
                : '🚗'}
        </span>
        <span className="dispatch-panel__title">
          {phase === 'completed'
            ? 'Trip Summary'
            : phase === 'in_progress'
              ? 'On Trip'
              : phase === 'arrived'
                ? 'Driver Waiting'
                : 'Real Dispatch'}
        </span>
      </div>

      {/* ── Body ────────────────────────────────────────────────────────── */}
      <div className="dispatch-panel__body">
        <div className="dispatch-panel__place" title={placeName}>
          {phase === 'in_progress' && destinationPlace
            ? `To: ${destinationPlace.name}`
            : placeName}
        </div>

        {/* Disclaimer - always visible unless completed */}
        {phase !== 'completed' && (
          <p className="dispatch-panel__disclaimer">
            Demo infrastructure - unauthenticated. Driver IDs are not verified identities.
          </p>
        )}

        {/* ── CTA / action row ──────────────────────────────────────────── */}
        {phase === 'idle' && (
          <button
            id="dispatch-request-btn"
            className="dispatch-panel__btn dispatch-panel__btn--primary"
            onClick={onRequestDispatch}
            type="button"
          >
            <span aria-hidden="true">⚡</span>
            Request Real Dispatch
          </button>
        )}

        {(phase === 'no_drivers' || phase === 'error' || phase === 'cancelled') && (
          <div className="dispatch-panel__action-group">
            <button
              id="dispatch-retry-btn"
              className="dispatch-panel__btn dispatch-panel__btn--primary"
              onClick={onRequestDispatch}
              type="button"
            >
              <span aria-hidden="true">🔄</span>
              {phase === 'cancelled' ? 'Book Another Ride' : 'Retry Dispatch'}
            </button>
            <button
              id="dispatch-reset-btn"
              className="dispatch-panel__btn dispatch-panel__btn--secondary"
              onClick={onReset}
              type="button"
            >
              Reset
            </button>
          </div>
        )}

        {(phase === 'matched' || phase === 'en_route') && (
          <div className="dispatch-panel__action-group">
            {onCancelRide && (
              <button
                id="dispatch-cancel-btn"
                className="dispatch-panel__btn dispatch-panel__btn--danger"
                onClick={onCancelRide}
                type="button"
                disabled={isCancelling}
              >
                <span aria-hidden="true">{isCancelling ? '⏳' : '✕'}</span>
                {isCancelling ? 'Cancelling…' : 'Cancel Ride'}
              </button>
            )}
            <button
              id="dispatch-reset-btn"
              className="dispatch-panel__btn dispatch-panel__btn--secondary"
              onClick={onReset}
              type="button"
              disabled={isCancelling}
            >
              Reset
            </button>
          </div>
        )}

        {/* ── Phase 11.3: Arrived at pickup action group ─────────────────── */}
        {phase === 'arrived' && (
          <div className="dispatch-panel__action-group">
            <button
              id="dispatch-start-btn"
              className="dispatch-panel__btn dispatch-panel__btn--primary"
              onClick={onStartTrip}
              type="button"
              disabled={isStarting || !canStartTrip}
            >
              <span aria-hidden="true">{isStarting ? '⏳' : '🚀'}</span>
              {isStarting ? 'Starting Trip…' : 'Start Trip'}
            </button>
            {onCancelRide && (
              <button
                id="dispatch-cancel-btn"
                className="dispatch-panel__btn dispatch-panel__btn--danger"
                onClick={onCancelRide}
                type="button"
                disabled={isCancelling || isStarting}
              >
                <span aria-hidden="true">{isCancelling ? '⏳' : '✕'}</span>
                {isCancelling ? 'Cancelling…' : 'Cancel'}
              </button>
            )}
          </div>
        )}

        {/* ── Phase 11.3: In-Progress actions ────────────────────────────── */}
        {phase === 'in_progress' && (
          <button
            id="dispatch-complete-btn"
            className="dispatch-panel__btn dispatch-panel__btn--success"
            onClick={onCompleteTrip}
            type="button"
            disabled={isCompleting}
          >
            <span aria-hidden="true">{isCompleting ? '⏳' : '🏁'}</span>
            {isCompleting ? 'Completing Trip…' : 'Complete Trip'}
          </button>
        )}

        {/* ── Phase 11.3: Completed actions ──────────────────────────────── */}
        {phase === 'completed' && (
          <button
            id="dispatch-book-another-btn"
            className="dispatch-panel__btn dispatch-panel__btn--primary"
            onClick={onReset}
            type="button"
          >
            <span aria-hidden="true">✨</span>
            Book Another Ride
          </button>
        )}

        {/* ── Status display ────────────────────────────────────────────── */}
        {phase === 'registering' && (
          <StatusRow dot="active" label="Registering demo driver…" sublabel="Registration queued in Kafka" />
        )}

        {phase === 'submitting' && (
          <StatusRow dot="active" label="Submitting ride request…" sublabel="Request queued for matching" />
        )}

        {phase === 'polling' && (
          <StatusRow dot="active" label="Waiting for backend match…" sublabel="Polling dispatch engine" />
        )}

        {/* ── Matched & En-Route state: Driver Approach (Phase 11.2 & 11.3) ── */}
        {(phase === 'matched' || phase === 'en_route') && (
          <>
            {isVehicleArrived ? (
              <StatusRow
                dot="success"
                label="Driver arrived at pickup"
                sublabel="Vehicle waiting at pickup location"
              />
            ) : approachStatus === 'loading' ? (
              <StatusRow
                dot="active"
                label="Calculating route to pickup…"
                sublabel="Routing assigned driver"
              />
            ) : approachStatus === 'error' ? (
              <StatusRow
                dot="warning"
                label="Driver heading to pickup"
                sublabel="Estimated geodesic path"
              />
            ) : (
              <StatusRow
                dot="active"
                label="Driver heading to pickup"
                sublabel="Following road geometry to pickup"
              />
            )}

            {/* Live ETA & remaining distance card to pickup */}
            {remainingDistanceMeters !== undefined && remainingDurationSeconds !== undefined && (
              <div className="dispatch-panel__eta-card" id="dispatch-eta-card" role="status">
                <div className="dispatch-panel__eta-item">
                  <span className="dispatch-panel__eta-label">Pickup ETA</span>
                  <span className="dispatch-panel__eta-value" id="dispatch-eta-value">
                    {isVehicleArrived ? 'Arrived' : formatEta(remainingDurationSeconds)}
                  </span>
                </div>
                <div className="dispatch-panel__eta-divider" />
                <div className="dispatch-panel__eta-item">
                  <span className="dispatch-panel__eta-label">Distance</span>
                  <span className="dispatch-panel__eta-value" id="dispatch-distance-value">
                    {formatDistance(remainingDistanceMeters)}
                  </span>
                </div>
              </div>
            )}
          </>
        )}

        {/* ── Phase 11.3: Arrived Waiting State ──────────────────────────── */}
        {phase === 'arrived' && (
          <>
            <StatusRow
              dot="success"
              label="Driver arrived at pickup"
              sublabel="Vehicle waiting at pickup location. Ready for boarding."
            />

            {!canStartTrip && (
              <div className="dispatch-panel__guidance-box" role="alert">
                <span className="dispatch-panel__guidance-title">Destination Required</span>
                <span className="dispatch-panel__guidance-msg">
                  {missingDestinationMessage || 'Please select a destination using the search bar before starting the ride.'}
                </span>
              </div>
            )}
          </>
        )}

        {/* ── Phase 11.3: In-Progress Journey Display ────────────────────── */}
        {phase === 'in_progress' && (
          <>
            <StatusRow
              dot="active"
              label="On road to destination"
              sublabel={destinationPlace ? `Traveling to ${destinationPlace.name}` : 'Traveling along road route'}
            />

            {/* Live ETA & remaining distance card to destination */}
            {remainingDistanceMeters !== undefined && remainingDurationSeconds !== undefined && (
              <div
                className="dispatch-panel__eta-card dispatch-panel__eta-card--destination"
                id="dispatch-eta-card"
                role="status"
              >
                <div className="dispatch-panel__eta-item">
                  <span className="dispatch-panel__eta-label">Destination ETA</span>
                  <span className="dispatch-panel__eta-value" id="dispatch-eta-value">
                    {formatEta(remainingDurationSeconds)}
                  </span>
                </div>
                <div className="dispatch-panel__eta-divider" />
                <div className="dispatch-panel__eta-item">
                  <span className="dispatch-panel__eta-label">Remaining</span>
                  <span className="dispatch-panel__eta-value" id="dispatch-distance-value">
                    {formatDistance(remainingDistanceMeters)}
                  </span>
                </div>
              </div>
            )}
          </>
        )}

        {/* ── Phase 11.3: Completed Trip Summary Card ────────────────────── */}
        {phase === 'completed' && (
          <div
            className="dispatch-panel__summary-card"
            id="dispatch-summary-card"
            role="region"
            aria-label="Trip completion summary"
          >
            <div className="dispatch-panel__summary-header">
              <span className="dispatch-panel__summary-title">
                <span aria-hidden="true">🎉</span> Ride Completed
              </span>
              <span className="dispatch-panel__summary-badge">Persisted</span>
            </div>

            <div className="dispatch-panel__summary-grid">
              <div className="dispatch-panel__summary-row">
                <span className="dispatch-panel__summary-label">Driver ID</span>
                <span className="dispatch-panel__summary-value">{matchedDriverId ?? '--'}</span>
              </div>
              <div className="dispatch-panel__summary-row">
                <span className="dispatch-panel__summary-label">Pickup</span>
                <span className="dispatch-panel__summary-value" title={placeName}>{placeName}</span>
              </div>
              {destinationPlace && (
                <div className="dispatch-panel__summary-row">
                  <span className="dispatch-panel__summary-label">Drop-off</span>
                  <span className="dispatch-panel__summary-value" title={destinationPlace.name}>
                    {destinationPlace.name}
                  </span>
                </div>
              )}
              {route && (
                <div className="dispatch-panel__summary-row">
                  <span className="dispatch-panel__summary-label">Journey</span>
                  <span className="dispatch-panel__summary-value">
                    {formatDistanceUtil(route.distanceMeters)} · {formatDurationUtil(route.durationSeconds)}
                  </span>
                </div>
              )}
              <div className="dispatch-panel__summary-fare-row">
                <div>
                  <span className="dispatch-panel__summary-fare-label">
                    {chosenFare?.name ?? 'Standard Ride'} {chosenFare?.icon ?? '🚗'}
                  </span>
                  <span className="dispatch-panel__summary-fare-note">Quoted Fare (Fixed Quote)</span>
                </div>
                <span className="dispatch-panel__summary-fare-value" id="dispatch-summary-fare">
                  {chosenFare ? `${chosenFare.currency}${chosenFare.totalFare}` : '--'}
                </span>
              </div>
            </div>
          </div>
        )}

        {/* ── Matched driver card (shown during approach and arrived) ─────── */}
        {(phase === 'matched' || phase === 'en_route' || phase === 'arrived') && matchedDriverId && (
          <div className="dispatch-panel__match-card" role="status" aria-live="polite">
            <div className="dispatch-panel__match-avatar" aria-hidden="true">🚖</div>
            <div className="dispatch-panel__match-info">
              <div className="dispatch-panel__match-label">Match confirmed</div>
              <div
                className="dispatch-panel__match-driver-id"
                title={matchedDriverId}
                id="dispatch-matched-driver-id"
              >
                {matchedDriverId}
              </div>
              {formattedUpdateTime && (
                <div className="dispatch-panel__match-time">
                  {formattedUpdateTime}
                </div>
              )}
            </div>
          </div>
        )}

        {/* ── Driver location badge / unavailable notice ────────────────── */}
        {(phase === 'matched' || phase === 'en_route' || phase === 'arrived') && (
          matchedDriverLocation ? (
            <div
              className="dispatch-panel__location-badge"
              role="status"
              id="dispatch-driver-location-badge"
            >
              <span className="dispatch-panel__location-badge-icon" aria-hidden="true">📍</span>
              <div className="dispatch-panel__location-badge-text">
                <strong>Driver location verified</strong>
                {formattedFixTime && (
                  <span>Fix at {formattedFixTime}</span>
                )}
              </div>
            </div>
          ) : (
            <div
              className="dispatch-panel__location-badge dispatch-panel__location-badge--unavailable"
              role="status"
              id="dispatch-driver-location-unavailable"
            >
              <span className="dispatch-panel__location-badge-icon" aria-hidden="true">📍</span>
              <div className="dispatch-panel__location-badge-text">
                <span>Driver location unavailable (stale or unverified fix at match time)</span>
              </div>
            </div>
          )
        )}

        {phase === 'cancelled' && (
          <StatusRow
            dot="warning"
            label="Ride Cancelled"
            sublabel="The trip was cancelled prior to passenger pickup."
          />
        )}

        {phase === 'no_drivers' && (
          <StatusRow
            dot="warning"
            label="No drivers available"
            sublabel="Registration was queued, but no available driver was ready near this location when the matching engine ran."
          />
        )}

        {phase === 'error' && (
          <>
            <StatusRow dot="error" label="Dispatch error" />
            {errorMessage && (
              <p className="dispatch-panel__error-msg" role="alert">{errorMessage}</p>
            )}
          </>
        )}

        {/* ── Phase 11.4E & 11.4F: Dispatch Execution Trace Accordion ────────── */}
        {requestId && (
          <DispatchTraceCard
            state={traceState}
            onRefresh={refreshTrace}
            defaultExpanded={defaultTraceExpanded}
            isExpanded={isTraceExpanded}
            onToggleExpand={onToggleTraceExpand}
          />
        )}
      </div>
    </div>
  );
}

// ── Internal helpers ──────────────────────────────────────────────────────────

type DotVariant = 'active' | 'success' | 'warning' | 'error';

function StatusRow({
  dot,
  label,
  sublabel,
}: {
  dot: DotVariant;
  label: string;
  sublabel?: string;
}) {
  return (
    <div className="dispatch-panel__status">
      <div
        className={`dispatch-panel__status-dot dispatch-panel__status-dot--${dot}`}
        aria-hidden="true"
      />
      <div className="dispatch-panel__status-text">
        <strong>{label}</strong>
        {sublabel && <span>{sublabel}</span>}
      </div>
    </div>
  );
}
