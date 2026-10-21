import type { SimulatedDriver } from '../types/driver';
import './SimulatedFleetBadge.css';

interface SimulatedFleetBadgeProps {
  drivers: SimulatedDriver[];
  placeName?: string;
  isHighlighted?: boolean;
}

/**
 * Compact, accessible overlay badge and legend displaying simulated fleet metrics.
 *
 * Explicitly identifies the driver data as simulated to prevent any implication
 * of real-world dispatch supply.
 */
export function SimulatedFleetBadge({
  drivers,
  placeName,
  isHighlighted = false,
}: SimulatedFleetBadgeProps) {
  if (drivers.length === 0) {
    return null;
  }

  const totalCount = drivers.length;
  const availableCount = drivers.filter((d) => d.status === 'available').length;
  const busyCount = totalCount - availableCount;

  return (
    <aside
      className={`simulated-fleet-badge ${isHighlighted ? 'simulated-fleet-badge--highlighted' : ''}`}
      role="status"
      aria-label={`Simulated fleet: ${availableCount} available of ${totalCount} total simulated drivers`}
      id="simulated-fleet-badge"
    >
      <div className="simulated-fleet-badge__header">
        <div className="simulated-fleet-badge__title-group">
          <span className="simulated-fleet-badge__live-dot" aria-hidden="true" />
          <span className="simulated-fleet-badge__title">Simulated Fleet</span>
        </div>
        <span className="simulated-fleet-badge__tag" aria-hidden="true">SIM</span>
      </div>

      {placeName && (
        <div className="simulated-fleet-badge__location" title={placeName}>
          {placeName}
        </div>
      )}

      <div className="simulated-fleet-badge__counts">
        <span className="simulated-fleet-badge__count-main">
          {availableCount} <span className="simulated-fleet-badge__count-label">available</span>
        </span>
        <span className="simulated-fleet-badge__count-sep">/</span>
        <span className="simulated-fleet-badge__count-total">
          {totalCount} <span className="simulated-fleet-badge__count-label">total</span>
        </span>
      </div>

      <div className="simulated-fleet-badge__legend" aria-hidden="true">
        <div className="simulated-fleet-badge__legend-item">
          <span className="simulated-fleet-badge__legend-dot simulated-fleet-badge__legend-dot--available" />
          <span>Available ({availableCount})</span>
        </div>
        <div className="simulated-fleet-badge__legend-item">
          <span className="simulated-fleet-badge__legend-dot simulated-fleet-badge__legend-dot--busy" />
          <span>Busy ({busyCount})</span>
        </div>
      </div>

      <p className="simulated-fleet-badge__notice">
        Synthetic data for display - not real dispatch supply
      </p>
    </aside>
  );
}
