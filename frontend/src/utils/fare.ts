import type { RideTierFare } from '../types/routing';

interface FareTierConfig {
  tierId: 'standard' | 'premier' | 'xl';
  name: string;
  tagline: string;
  capacity: number;
  baseFare: number;
  ratePerKm: number;
  ratePerMinute: number;
  minimumFare: number;
  etaOffsetMinutes: number;
  icon: string;
}

const FARE_TIERS: FareTierConfig[] = [
  {
    tierId: 'standard',
    name: 'Rendezvous Go',
    tagline: 'Affordable, reliable everyday rides',
    capacity: 4,
    baseFare: 50,
    ratePerKm: 15,
    ratePerMinute: 2,
    minimumFare: 70,
    etaOffsetMinutes: 3,
    icon: '🚗',
  },
  {
    tierId: 'premier',
    name: 'Rendezvous Premier',
    tagline: 'Top-rated drivers, premium vehicles',
    capacity: 4,
    baseFare: 80,
    ratePerKm: 22,
    ratePerMinute: 3,
    minimumFare: 120,
    etaOffsetMinutes: 5,
    icon: '✨',
  },
  {
    tierId: 'xl',
    name: 'Rendezvous XL',
    tagline: 'Spacious 6-seater rides for groups',
    capacity: 6,
    baseFare: 120,
    ratePerKm: 28,
    ratePerMinute: 4,
    minimumFare: 180,
    etaOffsetMinutes: 7,
    icon: '🚙',
  },
];

/**
 * Calculates fare estimates for all vehicle tiers given distance in km and duration in minutes.
 * Guarantees integer currency values and enforces minimum fare thresholds.
 */
export function calculateFares(
  distanceKm: number,
  durationMinutes: number,
  currency = '₹',
): RideTierFare[] {
  const safeDistance = Math.max(0, Number.isFinite(distanceKm) ? distanceKm : 0);
  const safeDuration = Math.max(0, Number.isFinite(durationMinutes) ? durationMinutes : 0);

  return FARE_TIERS.map((tier) => {
    const distanceFare = Math.round(safeDistance * tier.ratePerKm);
    const timeFare = Math.round(safeDuration * tier.ratePerMinute);
    const unroundedTotal = tier.baseFare + distanceFare + timeFare;
    const totalFare = Math.max(tier.minimumFare, Math.round(unroundedTotal));

    return {
      tierId: tier.tierId,
      name: tier.name,
      tagline: tier.tagline,
      capacity: tier.capacity,
      baseFare: tier.baseFare,
      distanceFare,
      timeFare,
      totalFare,
      currency,
      etaMinutes: Math.max(1, tier.etaOffsetMinutes),
      icon: tier.icon,
    };
  });
}

/**
 * Formats distance into a human-readable string (meters or km).
 */
export function formatDistance(meters: number): string {
  if (!Number.isFinite(meters) || meters <= 0) return '0 m';
  if (meters < 1000) {
    return `${Math.round(meters)} m`;
  }
  const km = meters / 1000;
  return `${km < 10 ? km.toFixed(1) : Math.round(km)} km`;
}

/**
 * Formats duration in seconds into a human-readable string (mins or hours + mins).
 */
export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return '0 min';
  const totalMinutes = Math.round(seconds / 60);
  if (totalMinutes < 60) {
    return `${Math.max(1, totalMinutes)} min`;
  }
  const hours = Math.floor(totalMinutes / 60);
  const remainingMinutes = totalMinutes % 60;
  if (remainingMinutes === 0) {
    return `${hours} hr`;
  }
  return `${hours} hr ${remainingMinutes} min`;
}
