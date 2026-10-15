import { useEffect, useState } from 'react';
import type { PlaceSuggestion } from '../types/place';
import type { SimulatedDriver } from '../types/driver';
import { generateDrivers } from '../utils/driverUtils';

/**
 * Hook to manage the simulated driver fleet for the currently selected place.
 *
 * Guarantees:
 * - Returns an empty array when selectedPlace is null.
 * - Deterministic: Selecting the same place reproduces the exact same driver set.
 * - Stale-free: Changing places immediately replaces previous drivers.
 * - Cleanup: Cleans up driver visualization state upon place clear or unmount.
 */
export function useSimulatedDrivers(selectedPlace: PlaceSuggestion | null): SimulatedDriver[] {
  const [drivers, setDrivers] = useState<SimulatedDriver[]>([]);

  useEffect(() => {
    if (!selectedPlace) {
      setDrivers([]);
      return;
    }

    const nextDrivers = generateDrivers(selectedPlace);
    setDrivers(nextDrivers);

    return () => {
      setDrivers([]);
    };
  }, [selectedPlace]);

  return drivers;
}
