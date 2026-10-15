import type { DispatchState } from '../types/dispatch';
import './DispatchPanel.css';

interface DispatchPanelProps {
  placeName: string;
  dispatchState: DispatchState;
  onRequestDispatch: () => void;
  onReset: () => void;
}

/**
 * Phase 7D — Real dispatch action panel.
 *
 * Rendered when the user selects a place. Provides:
 *   - A "Request Real Dispatch" CTA that triggers the backend matching pipeline.
 *   - A status display that tracks progress through registering → submitting → polling.
 *   - A matched-driver result card (only when the backend confirms a match).
 *   - An error display with a retry/reset option.
 *
 * Security notice: displayed prominently in the disclaimer — the backend is
 * unauthenticated demonstration infrastructure. driverId is not a verified identity.
 *
 * Simulated drivers (Phase 7C) remain visible alongside this panel; they are
 * clearly separated and are never fed into the real matching pipeline.
 */
export function DispatchPanel({
  placeName,
  dispatchState,
  onRequestDispatch,
  onReset,
}: DispatchPanelProps) {
  const { phase, matchedDriverId, errorMessage, updatedAt } = dispatchState;

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
              {updatedAt && (
                <div className="dispatch-panel__match-time">
                  {new Date(updatedAt).toLocaleTimeString()}
                </div>
              )}
            </div>
          </div>
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
