import { useState } from 'react';
import type { DispatchTrace, DispatchPhase } from '../types/dispatch';
import type { RouteSummary } from '../types/routing';
import './AlgorithmHUD.css';

export interface AlgorithmHUDProps {
  trace: DispatchTrace | null;
  route: RouteSummary | null;
  approachRoute: RouteSummary | null;
  isAlgorithmMode: boolean;
  onToggleAlgorithmMode: () => void;
  onReplayPathSearch?: () => void;
  matchedDriverId?: string | null;
  phase: DispatchPhase;
  dockPosition?: 'left' | 'right';
  hasDestination?: boolean;
  defaultMinimized?: boolean;
}

export function AlgorithmHUD({
  trace,
  route,
  approachRoute,
  isAlgorithmMode,
  onToggleAlgorithmMode,
  onReplayPathSearch,
  matchedDriverId,
  phase,
  dockPosition = 'right',
  hasDestination = false,
  defaultMinimized,
}: AlgorithmHUDProps) {
  const [isMinimized, setIsMinimized] = useState(() => {
    if (defaultMinimized !== undefined) return defaultMinimized;
    if (typeof window !== 'undefined' && window.innerWidth <= 768) return true;
    return false;
  });

  // Active route being visualized: destination route or driver approach route
  const activeRoute = (phase === 'matched' || phase === 'en_route') ? approachRoute : route;

  // Don't render if neither a trace nor a route exists
  const hasContent = Boolean(trace || activeRoute);
  if (!hasContent) {
    return null;
  }

  const winnerCandidate = trace?.candidates.find(
    (c) => c.outcome === 'MATCHED' || c.driverId === trace.winnerDriverId || c.driverId === matchedDriverId,
  );
  const outcompetedCandidates = trace?.candidates
    .filter((c) => c.driverId !== winnerCandidate?.driverId)
    .sort((a, b) => a.rank - b.rank)
    .slice(0, 3) ?? [];

  const totalVisitedNodes = trace?.stages.reduce((acc, s) => acc + s.visitedNodeCount, 0) ?? 0;
  const totalPrunedNodes = trace?.stages.reduce((acc, s) => acc + s.prunedNodeCount, 0) ?? 0;
  const searchLatencyMs = trace ? (trace.durationNanos / 1_000_000).toFixed(2) : null;

  return (
    <>
      {!isMinimized && (
        <div
          className="algorithm-hud__backdrop"
          onClick={() => setIsMinimized(true)}
          aria-hidden="true"
        />
      )}
      <aside
        className={`algorithm-hud algorithm-hud--dock-${dockPosition} ${hasDestination ? 'algorithm-hud--dual-search' : 'algorithm-hud--single-search'} ${isMinimized ? 'algorithm-hud--minimized' : 'algorithm-hud--expanded'} ${isAlgorithmMode ? 'algorithm-hud--active' : 'algorithm-hud--inactive'}`}
        aria-label="Algorithm Visualizer Heads-Up Display"
      >
      {/* ── HUD Header Bar ────────────────────────────────────────────── */}
      <div className="algorithm-hud__header">
        <div className="algorithm-hud__title-group">
          <span className="algorithm-hud__pulse-dot" aria-hidden="true" />
          <div className="algorithm-hud__title-wrap">
            <span className="algorithm-hud__badge">ALGORITHM VISUALIZER</span>
            <span className="algorithm-hud__subtitle">
              {trace ? 'Quadtree NN + Contraction Hierarchies' : 'Shortest Path Graph Solver'}
            </span>
          </div>
        </div>

        <div className="algorithm-hud__controls">
          <button
            type="button"
            className={`algorithm-hud__toggle-btn ${isAlgorithmMode ? 'algorithm-hud__toggle-btn--on' : ''}`}
            onClick={onToggleAlgorithmMode}
            title={isAlgorithmMode ? 'Disable visual algorithm overlays' : 'Enable visual algorithm overlays'}
          >
            <span className="algorithm-hud__toggle-indicator" />
            {isAlgorithmMode ? 'MODE: ON' : 'MODE: OFF'}
          </button>

          <button
            type="button"
            className="algorithm-hud__icon-btn"
            onClick={() => setIsMinimized((prev) => !prev)}
            aria-label={isMinimized ? 'Expand HUD' : 'Minimize HUD'}
            title={isMinimized ? 'Expand' : 'Minimize'}
          >
            {isMinimized ? '⤢' : ' - '}
          </button>
        </div>
      </div>

      {/* ── HUD Expanded Body ─────────────────────────────────────────── */}
      {!isMinimized && (
        <div className="algorithm-hud__body">
          {/* ── Section 1: Spatial QuadTree Search ─────────────────────── */}
          <div className="algorithm-hud__section">
            <div className="algorithm-hud__section-header">
              <span className="algorithm-hud__section-icon" aria-hidden="true">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#8b5cf6" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="3" y="3" width="18" height="18" rx="2"/>
                  <line x1="12" y1="3" x2="12" y2="21"/>
                  <line x1="3" y1="12" x2="21" y2="12"/>
                </svg>
              </span>
              <span className="algorithm-hud__section-title">Spatial QuadTree Partition (O(log N))</span>
              {searchLatencyMs && (
                <span className="algorithm-hud__section-tag">{searchLatencyMs} ms</span>
              )}
            </div>

            <div className="algorithm-hud__grid">
              <div className="algorithm-hud__metric">
                <span className="algorithm-hud__metric-label">Stages</span>
                <span className="algorithm-hud__metric-val">{trace ? trace.stages.length : 1}</span>
              </div>
              <div className="algorithm-hud__metric">
                <span className="algorithm-hud__metric-label">Explored</span>
                <span className="algorithm-hud__metric-val">{totalVisitedNodes} nodes</span>
              </div>
              <div className="algorithm-hud__metric">
                <span className="algorithm-hud__metric-label">Pruned</span>
                <span className="algorithm-hud__metric-val">{totalPrunedNodes} nodes</span>
              </div>
              <div className="algorithm-hud__metric">
                <span className="algorithm-hud__metric-label">Candidates</span>
                <span className="algorithm-hud__metric-val">{trace ? trace.candidates.length : ' - '}</span>
              </div>
            </div>

            {trace && trace.stages.length > 0 && (
              <div className="algorithm-hud__stage-pills">
                {trace.stages.map((st) => (
                  <span key={st.stageIndex} className="algorithm-hud__stage-pill">
                    Stage {st.stageIndex + 1}: {st.windowSize} km window ({st.candidatesDiscovered} found)
                  </span>
                ))}
              </div>
            )}
          </div>

          {/* ── Section 2: Shortest-Path Graph Solver ───────────────────── */}
          {activeRoute && (
            <div className="algorithm-hud__section">
              <div className="algorithm-hud__section-header">
                <span className="algorithm-hud__section-icon" aria-hidden="true">
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#a855f7" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="6" cy="19" r="3"/>
                    <path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15"/>
                    <circle cx="18" cy="5" r="3"/>
                  </svg>
                </span>
                <span className="algorithm-hud__section-title">
                  Shortest Path (OSRM / Contraction Hierarchies)
                </span>
                {onReplayPathSearch && (
                  <button
                    type="button"
                    className="algorithm-hud__action-chip"
                    onClick={onReplayPathSearch}
                    title="Re-run laser wavefront shortest path discovery animation"
                  >
                    Replay Pulse ↻
                  </button>
                )}
              </div>

              <div className="algorithm-hud__grid">
                <div className="algorithm-hud__metric">
                  <span className="algorithm-hud__metric-label">Path Length</span>
                  <span className="algorithm-hud__metric-val algorithm-hud__metric-val--highlight">
                    ~{activeRoute.distanceKm} km
                  </span>
                </div>
                <div className="algorithm-hud__metric">
                  <span className="algorithm-hud__metric-label">Travel ETA</span>
                  <span className="algorithm-hud__metric-val algorithm-hud__metric-val--highlight">
                    ~{activeRoute.durationMinutes} min
                  </span>
                </div>
                <div className="algorithm-hud__metric">
                  <span className="algorithm-hud__metric-label">Road Nodes</span>
                  <span className="algorithm-hud__metric-val">
                    {activeRoute.coordinates.length} waypoints
                  </span>
                </div>
                <div className="algorithm-hud__metric">
                  <span className="algorithm-hud__metric-label">Geometry</span>
                  <span className="algorithm-hud__metric-val">
                    {activeRoute.isFallback ? 'Geodesic' : 'Road Graph'}
                  </span>
                </div>
              </div>

              <div className="algorithm-hud__status-banner">
                <span className="algorithm-hud__status-laser" />
                <span>Wavefront shortest-path solved along OpenStreetMap road graph</span>
              </div>
            </div>
          )}

          {/* ── Section 3: Multiobjective Cost Function ────────────────── */}
          {trace && (
            <div className="algorithm-hud__section">
              <div className="algorithm-hud__section-header">
                <span className="algorithm-hud__section-icon" aria-hidden="true">
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#10b981" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <line x1="18" y1="20" x2="18" y2="10"/>
                    <line x1="12" y1="20" x2="12" y2="4"/>
                    <line x1="6" y1="20" x2="6" y2="14"/>
                  </svg>
                </span>
                <span className="algorithm-hud__section-title">Multiobjective Cost Evaluation</span>
              </div>

              <div className="algorithm-hud__formula">
                <code>Cost = 0.50·ETA + 0.30·Density + 0.20·Opportunity</code>
              </div>

              {winnerCandidate && (
                <div className="algorithm-hud__candidate algorithm-hud__candidate--winner">
                  <div className="algorithm-hud__candidate-header">
                    <span className="algorithm-hud__candidate-badge">OPTIMAL WINNER</span>
                    <span className="algorithm-hud__candidate-id">ID: {winnerCandidate.driverId}</span>
                  </div>
                  <div className="algorithm-hud__candidate-scores">
                    <span>Dist: {winnerCandidate.distanceKm.toFixed(2)} km</span>
                    <span>
                      Score:{' '}
                      <strong>
                        {winnerCandidate.totalCost != null ? winnerCandidate.totalCost.toFixed(3) : 'MIN'}
                      </strong>
                    </span>
                  </div>
                </div>
              )}

              {outcompetedCandidates.length > 0 && (
                <div className="algorithm-hud__candidate-list">
                  <span className="algorithm-hud__candidate-list-title">Outcompeted Candidates:</span>
                  {outcompetedCandidates.map((cand) => (
                    <div key={cand.driverId} className="algorithm-hud__candidate-row">
                      <span className="algorithm-hud__candidate-row-id">ID: {cand.driverId}</span>
                      <span className="algorithm-hud__candidate-row-dist">
                        {cand.distanceKm.toFixed(1)} km
                      </span>
                      <span className="algorithm-hud__candidate-row-score">
                        Cost: {cand.totalCost != null ? cand.totalCost.toFixed(2) : ' - '}
                      </span>
                      <span className="algorithm-hud__candidate-row-tag">PRUNED</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </aside>
    </>
  );
}
