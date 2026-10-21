/**
 * Phase 7C - Simulated Driver domain types.
 *
 * Represents an ephemeral, locally generated simulated vehicle for map visualization.
 * No backend entity or dispatch state is associated with these simulated drivers.
 */

export type DriverStatus = 'available' | 'busy';

export interface SimulatedDriver {
  /** Stable identifier for this simulated driver (e.g. `${placeId}-sim-${index}`) */
  id: string;
  /** WGS-84 longitude [-180, 180] */
  lon: number;
  /** WGS-84 latitude [-90, 90] */
  lat: number;
  /** Simulation status */
  status: DriverStatus;
}
