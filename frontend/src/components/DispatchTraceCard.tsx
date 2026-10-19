/**
 * Phase 11.4E — Dispatch Trace Visualizer Component.
 *
 * Renders an expandable accordion displaying:
 * - High-level execution summary (algorithm, timing, match outcome, fleet stats, decision).
 * - Candidate evaluation table/list with outcome badges, cost decomposition, and rejection reasons.
 * - Spatial exploration stages metrics with graceful fallback for empty stage lists.
 * - Loading, neutral 404 (not yet generated / expired / evicted), and error retry states.
 * - Guaranteed safe display of nullable numbers (never renders NaN).
 */

import { useState } from 'react';
import type { DispatchTraceState } from '../hooks/useDispatchTrace';
import type { CandidateOutcome, SpatialStage, TraceCandidate } from '../types/dispatch';
import './DispatchTraceCard.css';

export interface DispatchTraceCardProps {
  state: DispatchTraceState;
  onRefresh: () => void;
  defaultExpanded?: boolean;
  /** Phase 11.4F: Optional controlled expanded state. */
  isExpanded?: boolean;
  /** Phase 11.4F: Callback invoked when accordion toggle is clicked. */
  onToggleExpand?: (expanded: boolean) => void;
}

/** Formats nanoseconds into a human-readable duration (µs or ms). */
export function formatTraceDuration(durationNanos: number): string {
  if (durationNanos == null || isNaN(durationNanos) || durationNanos < 0) {
    return '—';
  }
  if (durationNanos < 1_000) {
    return `${durationNanos} ns`;
  }
  if (durationNanos < 1_000_000) {
    return `${(durationNanos / 1_000).toFixed(1)} µs`;
  }
  return `${(durationNanos / 1_000_000).toFixed(2)} ms`;
}

/** Formats a numeric cost safely. Never returns NaN. */
export function formatTraceCost(cost: number | null | undefined): string {
  if (cost === null || cost === undefined || isNaN(cost)) {
    return '—';
  }
  return cost.toFixed(2);
}

export function DispatchTraceCard({
  state,
  onRefresh,
  defaultExpanded = false,
  isExpanded: controlledExpanded,
  onToggleExpand,
}: DispatchTraceCardProps) {
  const [uncontrolledExpanded, setUncontrolledExpanded] = useState(defaultExpanded);
  const isExpanded = controlledExpanded !== undefined ? controlledExpanded : uncontrolledExpanded;

  const handleToggle = () => {
    const next = !isExpanded;
    if (controlledExpanded === undefined) {
      setUncontrolledExpanded(next);
    }
    onToggleExpand?.(next);
  };

  if (state.status === 'idle') {
    return null;
  }

  const { status, trace, errorMessage } = state;

  return (
    <div className={`dispatch-trace-card ${isExpanded ? 'dispatch-trace-card--expanded' : ''}`}>
      <button
        type="button"
        className="dispatch-trace-card__header-btn"
        onClick={handleToggle}
        aria-expanded={isExpanded}
        aria-controls="dispatch-trace-panel-body"
        id="dispatch-trace-accordion-toggle"
      >
        <div className="dispatch-trace-card__header-left">
          <span className="dispatch-trace-card__icon" aria-hidden="true">⚡</span>
          <span className="dispatch-trace-card__title">Dispatch Trace</span>
          {status === 'loading' && (
            <span className="dispatch-trace-status-pill dispatch-trace-status-pill--loading">
              Loading…
            </span>
          )}
          {status === 'not_found' && (
            <span className="dispatch-trace-status-pill dispatch-trace-status-pill--neutral">
              Pending
            </span>
          )}
          {status === 'error' && (
            <span className="dispatch-trace-status-pill dispatch-trace-status-pill--error">
              Error
            </span>
          )}
          {status === 'ready' && trace && (
            <span className="dispatch-trace-status-pill dispatch-trace-status-pill--ready">
              {trace.algorithm} ({formatTraceDuration(trace.durationNanos)})
            </span>
          )}
        </div>
        <span
          className={`dispatch-trace-card__chevron ${isExpanded ? 'dispatch-trace-card__chevron--open' : ''}`}
          aria-hidden="true"
        >
          ▼
        </span>
      </button>

      {isExpanded && (
        <div
          id="dispatch-trace-panel-body"
          className="dispatch-trace-card__body"
          role="region"
          aria-labelledby="dispatch-trace-accordion-toggle"
        >
          {/* ── Loading State ──────────────────────────────────────────────── */}
          {status === 'loading' && (
            <div className="dispatch-trace-card__state-box" role="status">
              <div className="dispatch-trace-card__spinner" aria-hidden="true" />
              <span>Retrieving dispatch execution trace…</span>
            </div>
          )}

          {/* ── Neutral 404 Not Found State ───────────────────────────────── */}
          {status === 'not_found' && (
            <div className="dispatch-trace-card__state-box dispatch-trace-card__state-box--neutral">
              <div className="dispatch-trace-card__state-title">Trace Not Available</div>
              <p className="dispatch-trace-card__state-desc">
                Dispatch trace is currently unavailable. It may not yet be generated, or has expired
                or been evicted from memory.
              </p>
              <button
                type="button"
                className="dispatch-trace-card__action-btn"
                onClick={onRefresh}
                id="dispatch-trace-retry-notfound-btn"
              >
                Check Again
              </button>
            </div>
          )}

          {/* ── Error State ───────────────────────────────────────────────── */}
          {status === 'error' && (
            <div className="dispatch-trace-card__state-box dispatch-trace-card__state-box--error" role="alert">
              <div className="dispatch-trace-card__state-title">Failed to Load Trace</div>
              <p className="dispatch-trace-card__state-desc">
                {errorMessage || 'An unexpected error occurred while fetching the dispatch trace.'}
              </p>
              <button
                type="button"
                className="dispatch-trace-card__action-btn"
                onClick={onRefresh}
                id="dispatch-trace-retry-error-btn"
              >
                Retry
              </button>
            </div>
          )}

          {/* ── Ready State ───────────────────────────────────────────────── */}
          {status === 'ready' && trace && (
            <div className="dispatch-trace-card__content">
              {/* Trace Toolbar */}
              <div className="dispatch-trace-card__toolbar">
                <span className="dispatch-trace-card__request-tag" title={trace.requestId}>
                  ID: {trace.requestId}
                </span>
                <button
                  type="button"
                  className="dispatch-trace-card__refresh-btn"
                  onClick={onRefresh}
                  title="Re-fetch dispatch trace"
                  id="dispatch-trace-refresh-btn"
                >
                  ↻ Refresh
                </button>
              </div>

              {/* Summary Section */}
              <div className="dispatch-trace-card__section">
                <div className="dispatch-trace-card__section-title">Execution Summary</div>
                <div className="dispatch-trace-card__metrics-grid">
                  <div className="dispatch-trace-card__metric">
                    <span className="dispatch-trace-card__metric-label">Algorithm</span>
                    <strong className="dispatch-trace-card__metric-val">{trace.algorithm}</strong>
                  </div>
                  <div className="dispatch-trace-card__metric">
                    <span className="dispatch-trace-card__metric-label">Duration</span>
                    <strong className="dispatch-trace-card__metric-val">
                      {formatTraceDuration(trace.durationNanos)}
                    </strong>
                  </div>
                  <div className="dispatch-trace-card__metric">
                    <span className="dispatch-trace-card__metric-label">Outcome</span>
                    <span
                      className={`dispatch-trace-outcome-tag ${
                        trace.matched ? 'dispatch-trace-outcome-tag--matched' : 'dispatch-trace-outcome-tag--unmatched'
                      }`}
                    >
                      {trace.matched ? 'Matched' : 'Unmatched'}
                    </span>
                  </div>
                  <div className="dispatch-trace-card__metric">
                    <span className="dispatch-trace-card__metric-label">Winner</span>
                    <strong className="dispatch-trace-card__metric-val">
                      {trace.winnerDriverId ?? 'None'}
                    </strong>
                  </div>
                  <div className="dispatch-trace-card__metric">
                    <span className="dispatch-trace-card__metric-label">Fleet Evaluated</span>
                    <strong className="dispatch-trace-card__metric-val">
                      {trace.candidates.length} / {trace.totalFleetSize}
                    </strong>
                  </div>
                </div>

                <div className="dispatch-trace-card__decision-box">
                  <span className="dispatch-trace-card__decision-label">Decision:</span>
                  <span className="dispatch-trace-card__decision-text">{trace.decisionSummary}</span>
                </div>
              </div>

              {/* Candidate Evaluation Table */}
              <div className="dispatch-trace-card__section">
                <div className="dispatch-trace-card__section-title">
                  Candidate Evaluation ({trace.candidates.length})
                </div>
                {trace.candidates.length === 0 ? (
                  <p className="dispatch-trace-card__empty-text">No candidate drivers were evaluated.</p>
                ) : (
                  <div className="dispatch-trace-card__table-wrapper">
                    <table className="dispatch-trace-card__table" aria-label="Candidate Drivers Evaluation">
                      <thead>
                        <tr>
                          <th>#</th>
                          <th>Driver</th>
                          <th>Distance</th>
                          <th>Total Cost</th>
                          <th title="ETA Cost / Density Cost / Opportunity Cost">Breakdown (E/D/O)</th>
                          <th>Outcome</th>
                          <th>Rejection Reason</th>
                        </tr>
                      </thead>
                      <tbody>
                        {trace.candidates.map((c: TraceCandidate) => (
                          <tr key={`${c.driverId}-${c.rank}`}>
                            <td className="dispatch-trace-card__cell-rank">{c.rank}</td>
                            <td className="dispatch-trace-card__cell-driver">
                              <code>{c.driverId}</code>
                            </td>
                            <td>{c.distanceKm.toFixed(2)} km</td>
                            <td className="dispatch-trace-card__cell-cost">{formatTraceCost(c.totalCost)}</td>
                            <td className="dispatch-trace-card__cell-breakdown">
                              {formatTraceCost(c.etaCost)} / {formatTraceCost(c.densityCost)} /{' '}
                              {formatTraceCost(c.opportunityCost)}
                            </td>
                            <td>
                              <CandidateOutcomeBadge outcome={c.outcome} />
                            </td>
                            <td className="dispatch-trace-card__cell-reason">{c.rejectionReason ?? '—'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>

              {/* Spatial Stages Explorer */}
              <div className="dispatch-trace-card__section">
                <div className="dispatch-trace-card__section-title">
                  Spatial Stages ({trace.stages.length})
                </div>
                {trace.stages.length === 0 ? (
                  <p className="dispatch-trace-card__empty-text dispatch-trace-card__empty-stages">
                    No spatial exploration stages recorded for this algorithm.
                  </p>
                ) : (
                  <div className="dispatch-trace-card__stages-list">
                    {trace.stages.map((stage: SpatialStage) => (
                      <div key={stage.stageIndex} className="dispatch-trace-card__stage-item">
                        <div className="dispatch-trace-card__stage-header">
                          <span className="dispatch-trace-card__stage-name">
                            Stage {stage.stageIndex + 1}: {stage.stageType}
                          </span>
                          <span className="dispatch-trace-card__stage-window">
                            Window: {stage.windowSize}
                          </span>
                        </div>
                        <div className="dispatch-trace-card__stage-stats">
                          <span>Visited: {stage.visitedNodeCount}</span>
                          <span>Pruned: {stage.prunedNodeCount}</span>
                          <span>Discovered: {stage.candidatesDiscovered}</span>
                        </div>
                        {stage.minLatitude != null && (
                          <div className="dispatch-trace-card__stage-bbox">
                            Bounds: [{stage.minLatitude.toFixed(2)}, {stage.minLongitude?.toFixed(2)}] → [
                            {stage.maxLatitude?.toFixed(2)}, {stage.maxLongitude?.toFixed(2)}]
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function CandidateOutcomeBadge({ outcome }: { outcome: CandidateOutcome }) {
  const normalized = outcome.toLowerCase();
  return (
    <span className={`dispatch-trace-outcome-badge dispatch-trace-outcome-badge--${normalized}`}>
      {outcome}
    </span>
  );
}
