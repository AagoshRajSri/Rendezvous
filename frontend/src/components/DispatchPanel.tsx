import type { DispatchState } from '../types/dispatch';
import './DispatchPanel.css';

interface DispatchPanelProps {
  placeName: string;
  dispatchState: DispatchState;
  onRequestDispatch: () => void;
  onReset: () => void;
}

/**
 * Phase 7E.2 — Real dispatch action panel.
 *
 * Rendered when the user selects a place. Provides:
 *   - A "Request Real Dispatch" CTA that triggers the backend matching pipeline.
 *   - A status display that tracks progress through registering → submitting → polling.
 *   - A matched-driver result card (only when the backend confirms a match).
 *   - A "Driver location verified" telemetry badge when the backend supplies a
 *     fresh GPS fix for the matched driver (snapshot at status-query time, not live).
 *   - An error display with a retry/reset option.
 *
 * Security notice: displayed prominently in the disclaimer — the backend is
 * unauthenticated demonstration infrastructure. driverId is not a verified identity.
 *
 * Simulated drivers (Phase 7C) remain visible alongside this panel; they are
 * clearly separated and are never fed into the real matching pipeline.
 */
/**
 * Safely formats an ISO-8601 timestamp string as a local time (HH:MM:SS).
 * Returns null if the timestamp is missing, null, undefined, or fails to parse.
 */
function formatValidTime(isoString: string | null | undefined): string | null {
  if (!isoString) return null;
  const d = new Date(isoString);
  if (isNaN(d.getTime())) return null;
  return d.toLocaleTimeString();
}

export function DispatchPanel({
  placeName,
  dispatchState,
  onRequestDispatch,
  onReset,
}: DispatchPanelProps) {
  const { phase, matchedDriverId, matchedDriverLocation, matchedDriverLocationAt, errorMessage, updatedAt } = dispatchState;
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

        {(phase === 'no_drivers' || phase === 'error') && (
          <div className="dispatch-panel__action-group">
            <button
              id="dispatch-retry-btn"
              className="dispatch-panel__btn dispatch-panel__btn--primary"
              onClick={onRequestDispatch}
              type="button"
            >
              <span aria-hidden="true">🔄</span>
              Retry Dispatch
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
          <button
            id="dispatch-reset-btn"
            className="dispatch-panel__btn dispatch-panel__btn--secondary"
            onClick={onReset}
            type="button"
          >
            Reset
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

        {/* ── Driver location badge / unavailable notice (Phase 7E.2) ─────── */}
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

        {phase === 'matched' && !matchedDriverId && (
          <StatusRow
            dot="success"
            label="Match confirmed"
            sublabel="Driver ID not yet available (reconciling)"
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
