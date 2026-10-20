import type { MapReadyState } from '../hooks/useMapReady';
import './Header.css';

interface HeaderProps {
  mapState: MapReadyState;
}

const STATUS_LABELS: Record<MapReadyState['status'], string> = {
  loading: 'Loading map…',
  ready: 'Globe ready',
  error: 'Map error',
};

/**
 * Uber-inspired executive navigation bar with minimalist geometric Rendezvous emblem,
 * Apple-grade frosted glass, and clean mobility navigation tabs.
 */
export function Header({ mapState }: HeaderProps) {
  return (
    <header className="header" role="banner">
      <div className="header__inner">
        {/* ── Brand: Minimalist Geometric Logo + Typography ─────────────── */}
        <div className="header__brand-group">
          <div className="header__logo" aria-hidden="true">
            <svg
              className="header__logo-svg"
              viewBox="0 0 32 32"
              fill="none"
              xmlns="http://www.w3.org/2000/svg"
            >
              {/* Sleek converging pathways forming an abstract, geometric 'R' */}
              <rect x="4" y="5" width="4" height="22" rx="2" fill="#ffffff" />
              <path
                d="M8 7 H19 C23.5 7 26.5 10 26.5 14 C26.5 18 23.5 21 19 21 H8"
                stroke="#ffffff"
                strokeWidth="3.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
              <path
                d="M17 19 L26.5 27"
                stroke="#10b981"
                strokeWidth="3.5"
                strokeLinecap="round"
              />
              <circle cx="26.5" cy="27" r="2.2" fill="#34d399" />
            </svg>
          </div>

          <div className="header__wordmark">
            <span className="header__title">Rendezvous</span>
          </div>

          {/* ── Uber-style navigation items ──────────────────────────────── */}
          <nav className="header__nav" aria-label="Main Navigation">
            <button type="button" className="header__nav-item header__nav-item--active">
              Ride
            </button>
            <button type="button" className="header__nav-item">
              Fleet
            </button>
            <button type="button" className="header__nav-item">
              Dispatch
            </button>
          </nav>
        </div>

        <div className="header__spacer" />

        {/* ── Right: Apple/Uber-style minimal status pill ───────────────── */}
        <div className="header__right">
          <div
            className={`header__status header__status--${mapState.status}`}
            role="status"
            aria-live="polite"
            aria-label={`Map status: ${STATUS_LABELS[mapState.status]}`}
          >
            <span className="header__status-dot" aria-hidden="true" />
            <span className="header__status-text">{STATUS_LABELS[mapState.status]}</span>
          </div>
        </div>
      </div>
    </header>
  );
}
