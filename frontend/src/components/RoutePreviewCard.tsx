import type { PlaceSuggestion } from '../types/place';
import type { RouteSummary } from '../types/routing';
import { formatDistance, formatDuration } from '../utils/fare';
import './RoutePreviewCard.css';

interface RoutePreviewCardProps {
  pickup: PlaceSuggestion;
  destination: PlaceSuggestion;
  route: RouteSummary;
  selectedTier: 'standard' | 'premier' | 'xl';
  onSelectTier: (tier: 'standard' | 'premier' | 'xl') => void;
  onRequestDispatch: () => void;
  onClearDestination: () => void;
  isLoading?: boolean;
}

export function RoutePreviewCard({
  pickup,
  destination,
  route,
  selectedTier,
  onSelectTier,
  onRequestDispatch,
  onClearDestination,
  isLoading = false,
}: RoutePreviewCardProps) {
  const chosenFare = route.fares.find((f) => f.tierId === selectedTier) ?? route.fares[0];

  return (
    <div
      className="route-preview-card"
      role="region"
      aria-label="Route preview and fare estimate"
      id="route-preview-card"
    >
      {/* ── Route Header ─────────────────────────────────────────────────── */}
      <div className="route-preview-card__header">
        <div className="route-preview-card__title-row">
          <span className="route-preview-card__badge-icon" aria-hidden="true">🗺</span>
          <span className="route-preview-card__title">Trip Route Preview</span>
          {route.isFallback && (
            <span
              className="route-preview-card__fallback-tag"
              title="Calculated via geodesic interpolation because road network routing is offline"
            >
              Estimated
            </span>
          )}
        </div>
        <button
          type="button"
          className="route-preview-card__close-btn"
          onClick={onClearDestination}
          aria-label="Close route preview"
        >
          ×
        </button>
      </div>

      {/* ── Waypoint Details ──────────────────────────────────────────────── */}
      <div className="route-preview-card__waypoints">
        <div className="route-preview-card__waypoint route-preview-card__waypoint--pickup">
          <span className="route-preview-card__dot route-preview-card__dot--pickup" aria-hidden="true" />
          <div className="route-preview-card__waypoint-info">
            <span className="route-preview-card__waypoint-label">PICKUP</span>
            <span className="route-preview-card__waypoint-name" title={pickup.name}>
              {pickup.name}
            </span>
          </div>
        </div>

        <div className="route-preview-card__waypoint-connector" aria-hidden="true" />

        <div className="route-preview-card__waypoint route-preview-card__waypoint--dest">
          <span className="route-preview-card__dot route-preview-card__dot--dest" aria-hidden="true" />
          <div className="route-preview-card__waypoint-info">
            <span className="route-preview-card__waypoint-label">DESTINATION</span>
            <span className="route-preview-card__waypoint-name" title={destination.name}>
              {destination.name}
            </span>
          </div>
        </div>
      </div>

      {/* ── Metrics Strip ─────────────────────────────────────────────────── */}
      <div className="route-preview-card__metrics" id="route-metrics-strip">
        <div className="route-preview-card__metric">
          <span className="route-preview-card__metric-label">Driving Distance</span>
          <span className="route-preview-card__metric-value" id="route-metric-distance">
            {formatDistance(route.distanceMeters)}
          </span>
        </div>

        <div className="route-preview-card__metric-divider" aria-hidden="true" />

        <div className="route-preview-card__metric">
          <span className="route-preview-card__metric-label">Est. Duration</span>
          <span className="route-preview-card__metric-value" id="route-metric-duration">
            {formatDuration(route.durationSeconds)}
          </span>
        </div>

        <div className="route-preview-card__metric-divider" aria-hidden="true" />

        <div className="route-preview-card__metric">
          <span className="route-preview-card__metric-label">Fare Range</span>
          <span className="route-preview-card__metric-value">
            {route.fares[0].currency}{route.fares[0].totalFare} – {route.fares[route.fares.length - 1].currency}{route.fares[route.fares.length - 1].totalFare}
          </span>
        </div>
      </div>

      {/* ── Vehicle Tier Selector ─────────────────────────────────────────── */}
      <div className="route-preview-card__tiers" role="radiogroup" aria-label="Select ride category">
        {route.fares.map((tier) => {
          const isSelected = tier.tierId === selectedTier;
          return (
            <button
              key={tier.tierId}
              type="button"
              role="radio"
              aria-checked={isSelected}
              className={`route-preview-card__tier-item${isSelected ? ' route-preview-card__tier-item--selected' : ''}`}
              onClick={() => onSelectTier(tier.tierId)}
              id={`tier-select-${tier.tierId}`}
            >
              <div className="route-preview-card__tier-icon" aria-hidden="true">
                {tier.icon}
              </div>
              <div className="route-preview-card__tier-details">
                <div className="route-preview-card__tier-row">
                  <span className="route-preview-card__tier-name">{tier.name}</span>
                  <span className="route-preview-card__tier-capacity" title={`Capacity: ${tier.capacity} passengers`}>
                    👤 {tier.capacity}
                  </span>
                </div>
                <div className="route-preview-card__tier-sub">
                  <span>{tier.etaMinutes} min away</span>
                  <span className="route-preview-card__tier-tagline">{tier.tagline}</span>
                </div>
              </div>
              <div className="route-preview-card__tier-fare">
                <span className="route-preview-card__tier-price">
                  {tier.currency}{tier.totalFare}
                </span>
              </div>
            </button>
          );
        })}
      </div>

      {/* ── CTA Action ────────────────────────────────────────────────────── */}
      <div className="route-preview-card__actions">
        <button
          type="button"
          id="confirm-ride-request-btn"
          className="route-preview-card__btn route-preview-card__btn--confirm"
          onClick={onRequestDispatch}
          disabled={isLoading}
        >
          {isLoading ? (
            'Preparing Dispatch…'
          ) : (
            <>
              <span>Request {chosenFare.name}</span>
              <span className="route-preview-card__btn-fare">
                {chosenFare.currency}{chosenFare.totalFare}
              </span>
            </>
          )}
        </button>
      </div>

      {/* ── Attribution Footer ────────────────────────────────────────────── */}
      <div className="route-preview-card__footer">
        <span className="route-preview-card__attribution">{route.attribution}</span>
      </div>
    </div>
  );
}
