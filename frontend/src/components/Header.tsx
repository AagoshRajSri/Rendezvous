import type { MapReadyState } from '../hooks/useMapReady';
import './Header.css';

interface HeaderProps {
  mapState: MapReadyState;
}

const STATUS_LABELS: Record<MapReadyState['status'], string> = {
  loading: 'Loading globe…',
  ready: 'Globe ready',
  error: 'Map error',
};

/**
 * Fixed top header bar with the Rendezvous brand and a live map-state chip.
 *
 * The chip reflects the three states from useMapReady: loading (animated dot),
 * ready (green), and error (red). No business logic lives here.
 */
export function Header({ mapState }: HeaderProps) {
  return (
    <header className="header" role="banner">
      {/* Logo mark — a simple route-pin SVG */}
      <div className="header__logo" aria-hidden="true">
        <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
          <path d="M12 2C8.134 2 5 5.134 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.866-3.134-7-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z" />
        </svg>
      </div>

      {/* Brand text */}
      <div className="header__brand">
        <span className="header__title">Rendezvous</span>
        <span className="header__subtitle">Ride Dispatch</span>
      </div>

      <div className="header__spacer" />

      {/* Live map-state status chip */}
      <div
        className={`header__status header__status--${mapState.status}`}
        role="status"
        aria-live="polite"
        aria-label={`Map status: ${STATUS_LABELS[mapState.status]}`}
      >
        <span className="header__status-dot" aria-hidden="true" />
        {STATUS_LABELS[mapState.status]}
      </div>
    </header>
  );
}
