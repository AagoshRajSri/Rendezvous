import type { DispatchState } from '../types/dispatch';
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
  /** Phase 11.2: Live road remaining distance in meters. */
  remainingDistanceMeters?: number;
  /** Phase 11.2: Live ETA countdown in seconds. */
  remainingDurationSeconds?: number;
  /** Phase 11.2: Whether animated vehicle has reached pickup pin. */
  isVehicleArrived?: boolean;
}

/**
 * Phase 11.2 — Real dispatch action & driver tracking panel.
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
}: DispatchPanelProps) {
  const {
    phase,
    matchedDriverId,
    matchedDriverLocation,
    matchedDriverLocationAt,
    errorMessage,
    updatedAt,
  } = dispatchState;
  const formattedFixTime = formatValidTime(matchedDriverLocationAt);
  const formattedUpdateTime = formatValidTime(updatedAt);

  return (
    <div className="dispatch-panel" role="region" aria-label="Real dispatch panel">
      {/* ── Header ──────────────────────────────────────────────────────── */}
      <div className="dispatch-panel__header">
        <span className="dispatch-panel__icon" aria-hidden="true">🚗</span>
        <span className="dispatch-panel__title">Real Dispatch</span>
      </div>

      {/* ── Body ────────────────────────────────────────────────────────── */}
      <div className="dispatch-panel__body">
        <div className="dispatch-panel__place" title={placeName}>{placeName}</div>

        {/* Disclaimer — always visible */}
        <p className="dispatch-panel__disclaimer">
          Demo infrastructure — unauthenticated. Driver IDs are not verified identities.
        </p>

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

        {phase === 'matched' && (
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

        {/* ── Matched state: Driver Approach & Telemetry (Phase 11.2) ───── */}
        {phase === 'matched' && (
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

            {/* Live ETA & remaining distance card */}
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

        {/* ── Matched driver card ───────────────────────────────────────── */}
        {phase === 'matched' && matchedDriverId && (
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
        {phase === 'matched' && (
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
