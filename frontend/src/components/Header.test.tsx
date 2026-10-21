import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Header } from './Header';
import type { MapReadyState } from '../hooks/useMapReady';

describe('Header component', () => {
  const readyState: MapReadyState = { status: 'ready' };

  it('renders navigation tabs and marks Ride active by default', () => {
    render(<Header mapState={readyState} activeTab="ride" />);

    const rideTab = screen.getByRole('button', { name: 'Ride' });
    const fleetTab = screen.getByRole('button', { name: 'Fleet' });
    const dispatchTab = screen.getByRole('button', { name: 'Dispatch' });

    expect(rideTab.classList.contains('header__nav-item--active')).toBe(true);
    expect(fleetTab.classList.contains('header__nav-item--active')).toBe(false);
    expect(dispatchTab.classList.contains('header__nav-item--active')).toBe(false);
  });

  it('invokes onSelectTab when Fleet or Dispatch is clicked', () => {
    const onSelectTab = vi.fn();
    render(<Header mapState={readyState} activeTab="ride" onSelectTab={onSelectTab} />);

    fireEvent.click(screen.getByRole('button', { name: 'Fleet' }));
    expect(onSelectTab).toHaveBeenCalledWith('fleet');

    fireEvent.click(screen.getByRole('button', { name: 'Dispatch' }));
    expect(onSelectTab).toHaveBeenCalledWith('dispatch');
  });

  it('highlights Fleet tab when activeTab is fleet', () => {
    render(<Header mapState={readyState} activeTab="fleet" />);

    const fleetTab = screen.getByRole('button', { name: 'Fleet' });
    expect(fleetTab.classList.contains('header__nav-item--active')).toBe(true);
    expect(fleetTab.getAttribute('aria-current')).toBe('page');
  });
});
