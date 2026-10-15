import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SimulatedFleetBadge } from './SimulatedFleetBadge';
import type { SimulatedDriver } from '../types/driver';

describe('SimulatedFleetBadge', () => {
  const sampleDrivers: SimulatedDriver[] = [
    { id: 'd-1', lon: 0, lat: 0, status: 'available' },
    { id: 'd-2', lon: 0, lat: 0, status: 'available' },
    { id: 'd-3', lon: 0, lat: 0, status: 'available' },
    { id: 'd-4', lon: 0, lat: 0, status: 'busy' },
  ];

  it('renders nothing when drivers array is empty', () => {
    const { container } = render(<SimulatedFleetBadge drivers={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders the badge with correct counts for available and total drivers', () => {
    render(<SimulatedFleetBadge drivers={sampleDrivers} placeName="London, UK" />);

    // Live status role
    const badge = screen.getByRole('status');
    expect(badge).not.toBeNull();
    expect(badge.getAttribute('aria-label')).toBe(
      'Simulated fleet: 3 available of 4 total simulated drivers',
    );

    // Text elements
    expect(screen.getByText('Simulated Fleet')).not.toBeNull();
    expect(screen.getByText('SIM')).not.toBeNull();
    expect(screen.getByText('London, UK')).not.toBeNull();
    expect(screen.getByText('3')).not.toBeNull();
    expect(screen.getByText('4')).not.toBeNull();
  });

  it('displays the legend distinguishing available and busy drivers', () => {
    render(<SimulatedFleetBadge drivers={sampleDrivers} />);

    expect(screen.getByText('Available (3)')).not.toBeNull();
    expect(screen.getByText('Busy (1)')).not.toBeNull();
  });

  it('explicitly displays the simulation disclaimer notice', () => {
    render(<SimulatedFleetBadge drivers={sampleDrivers} />);

    expect(
      screen.getByText('Synthetic data for display — not real dispatch supply'),
    ).not.toBeNull();
  });
});
