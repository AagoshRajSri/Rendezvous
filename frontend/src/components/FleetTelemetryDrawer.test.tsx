import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { FleetTelemetryDrawer } from './FleetTelemetryDrawer';
import type { SimulatedDriver } from '../types/driver';

const sampleDrivers: SimulatedDriver[] = [
  { id: 'DRV-101', lat: 51.5074, lon: -0.1276, status: 'available' },
  { id: 'DRV-102', lat: 51.5080, lon: -0.1280, status: 'busy' },
  { id: 'DRV-103', lat: 51.5090, lon: -0.1290, status: 'available' },
];

describe('FleetTelemetryDrawer', () => {
  it('does not render when isOpen is false', () => {
    const { container } = render(
      <FleetTelemetryDrawer
        isOpen={false}
        onClose={vi.fn()}
        drivers={sampleDrivers}
      />
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders operational metrics and driver list when isOpen is true', () => {
    render(
      <FleetTelemetryDrawer
        isOpen={true}
        onClose={vi.fn()}
        drivers={sampleDrivers}
        placeName="London, UK"
      />
    );

    expect(screen.getByText('FLEET TELEMETRY')).toBeDefined();
    expect(screen.getByText('London, UK')).toBeDefined();
    expect(screen.getByText('DRV-101')).toBeDefined();
    expect(screen.getByText('DRV-102')).toBeDefined();
    expect(screen.getByText('DRV-103')).toBeDefined();
    // 2 available out of 3 total -> 67%
    expect(screen.getByText('67%')).toBeDefined();
  });

  it('filters drivers by status and search query', () => {
    render(
      <FleetTelemetryDrawer
        isOpen={true}
        onClose={vi.fn()}
        drivers={sampleDrivers}
      />
    );

    // Filter by busy
    fireEvent.click(screen.getByRole('tab', { name: /Busy/ }));
    expect(screen.queryByText('DRV-101')).toBeNull();
    expect(screen.getByText('DRV-102')).toBeDefined();

    // Filter by available
    fireEvent.click(screen.getByRole('tab', { name: /Available/ }));
    expect(screen.getByText('DRV-101')).toBeDefined();
    expect(screen.queryByText('DRV-102')).toBeNull();

    // Search input
    const searchInput = screen.getByPlaceholderText(/Search by Driver ID/);
    fireEvent.change(searchInput, { target: { value: '103' } });
    expect(screen.getByText('DRV-103')).toBeDefined();
    expect(screen.queryByText('DRV-101')).toBeNull();
  });

  it('calls onFocusCluster when cluster action button is clicked', () => {
    const onFocusCluster = vi.fn();
    render(
      <FleetTelemetryDrawer
        isOpen={true}
        onClose={vi.fn()}
        drivers={sampleDrivers}
        onFocusCluster={onFocusCluster}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: /Focus Entire Fleet Cluster/ }));
    expect(onFocusCluster).toHaveBeenCalledTimes(1);
  });

  it('calls onFocusDriver when targeting a specific driver', () => {
    const onFocusDriver = vi.fn();
    render(
      <FleetTelemetryDrawer
        isOpen={true}
        onClose={vi.fn()}
        drivers={sampleDrivers}
        onFocusDriver={onFocusDriver}
      />
    );

    const targetBtn = screen.getByRole('button', { name: 'Target camera on driver DRV-101' });
    fireEvent.click(targetBtn);
    expect(onFocusDriver).toHaveBeenCalledWith(sampleDrivers[0]);
  });

  it('calls onClose when close button is clicked', () => {
    const onClose = vi.fn();
    render(
      <FleetTelemetryDrawer
        isOpen={true}
        onClose={onClose}
        drivers={sampleDrivers}
      />
    );

    fireEvent.click(screen.getByTitle('Return to Ride View'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
