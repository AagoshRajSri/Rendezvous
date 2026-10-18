import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { RoutePreviewCard } from './RoutePreviewCard';
import type { PlaceSuggestion } from '../types/place';
import type { RouteSummary } from '../types/routing';

const dummyPickup: PlaceSuggestion = {
  id: 'p-1',
  name: 'Central Station',
  subtitle: 'Metro, Downtown',
  lat: 40.7128,
  lon: -74.006,
  type: 'station',
};

const dummyDestination: PlaceSuggestion = {
  id: 'p-2',
  name: 'Airport Terminal 1',
  subtitle: 'Queens, NY',
  lat: 40.6413,
  lon: -73.7781,
  type: 'airport',
};

const dummyRoute: RouteSummary = {
  distanceMeters: 24500,
  distanceKm: 24.5,
  durationSeconds: 1800,
  durationMinutes: 30,
  coordinates: [
    [-74.006, 40.7128],
    [-73.7781, 40.6413],
  ],
  fares: [
    {
      tierId: 'standard',
      name: 'Rendezvous Go',
      tagline: 'Standard ride',
      capacity: 4,
      baseFare: 50,
      distanceFare: 368,
      timeFare: 60,
      totalFare: 478,
      currency: '₹',
      etaMinutes: 3,
      icon: '🚗',
    },
    {
      tierId: 'premier',
      name: 'Rendezvous Premier',
      tagline: 'Premium ride',
      capacity: 4,
      baseFare: 80,
      distanceFare: 539,
      timeFare: 90,
      totalFare: 709,
      currency: '₹',
      etaMinutes: 5,
      icon: '✨',
    },
    {
      tierId: 'xl',
      name: 'Rendezvous XL',
      tagline: 'XL ride',
      capacity: 6,
      baseFare: 120,
      distanceFare: 686,
      timeFare: 120,
      totalFare: 926,
      currency: '₹',
      etaMinutes: 7,
      icon: '🚙',
    },
  ],
  isFallback: false,
  attribution: 'Routes © OpenStreetMap contributors, OSRM',
};

describe('RoutePreviewCard component', () => {
  it('renders pickup, destination, distance, duration, and fare tiers', () => {
    const handleSelectTier = vi.fn();
    const handleRequestDispatch = vi.fn();
    const handleClear = vi.fn();

    render(
      <RoutePreviewCard
        pickup={dummyPickup}
        destination={dummyDestination}
        route={dummyRoute}
        selectedTier="standard"
        onSelectTier={handleSelectTier}
        onRequestDispatch={handleRequestDispatch}
        onClearDestination={handleClear}
      />,
    );

    expect(screen.getByText('Central Station')).not.toBeNull();
    expect(screen.getByText('Airport Terminal 1')).not.toBeNull();
    expect(screen.getByText('25 km')).not.toBeNull(); // 24.5 km formatted
    expect(screen.getByText('30 min')).not.toBeNull();

    expect(screen.getByText('Rendezvous Go')).not.toBeNull();
    expect(screen.getByText('Rendezvous Premier')).not.toBeNull();
    expect(screen.getByText('Rendezvous XL')).not.toBeNull();
    expect(screen.getAllByText('₹478').length).toBeGreaterThanOrEqual(1);
  });

  it('allows switching tiers by clicking on tier items', () => {
    const handleSelectTier = vi.fn();
    const handleRequestDispatch = vi.fn();
    const handleClear = vi.fn();

    render(
      <RoutePreviewCard
        pickup={dummyPickup}
        destination={dummyDestination}
        route={dummyRoute}
        selectedTier="standard"
        onSelectTier={handleSelectTier}
        onRequestDispatch={handleRequestDispatch}
        onClearDestination={handleClear}
      />,
    );

    const premierBtn = screen.getByRole('radio', { name: /Rendezvous Premier/i });
    fireEvent.click(premierBtn);

    expect(handleSelectTier).toHaveBeenCalledWith('premier');
  });

  it('triggers onRequestDispatch when confirm CTA is clicked', () => {
    const handleSelectTier = vi.fn();
    const handleRequestDispatch = vi.fn();
    const handleClear = vi.fn();

    render(
      <RoutePreviewCard
        pickup={dummyPickup}
        destination={dummyDestination}
        route={dummyRoute}
        selectedTier="standard"
        onSelectTier={handleSelectTier}
        onRequestDispatch={handleRequestDispatch}
        onClearDestination={handleClear}
      />,
    );

    const confirmBtn = screen.getByRole('button', { name: /Request Rendezvous Go/i });
    fireEvent.click(confirmBtn);

    expect(handleRequestDispatch).toHaveBeenCalledTimes(1);
  });

  it('triggers onClearDestination when close button is clicked', () => {
    const handleSelectTier = vi.fn();
    const handleRequestDispatch = vi.fn();
    const handleClear = vi.fn();

    render(
      <RoutePreviewCard
        pickup={dummyPickup}
        destination={dummyDestination}
        route={dummyRoute}
        selectedTier="standard"
        onSelectTier={handleSelectTier}
        onRequestDispatch={handleRequestDispatch}
        onClearDestination={handleClear}
      />,
    );

    const closeBtn = screen.getByRole('button', { name: /Close route preview/i });
    fireEvent.click(closeBtn);

    expect(handleClear).toHaveBeenCalledTimes(1);
  });
});
