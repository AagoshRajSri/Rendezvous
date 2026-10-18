import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { SearchBar } from './SearchBar';
import type { PlaceSuggestion } from '../types/place';
import type { SearchState } from '../hooks/usePlaceSearch';

const mockPickup: PlaceSuggestion = {
  id: 'pick-1',
  name: 'Connaught Place',
  subtitle: 'New Delhi, Delhi',
  lat: 28.6315,
  lon: 77.2167,
  type: 'city',
};

const mockDest: PlaceSuggestion = {
  id: 'dest-1',
  name: 'Indira Gandhi Airport',
  subtitle: 'New Delhi, Delhi',
  lat: 28.5562,
  lon: 77.1000,
  type: 'airport',
};

const idleState: SearchState = { status: 'idle' };

describe('SearchBar component', () => {
  it('renders single input combobox when no pickup place is selected', () => {
    render(
      <SearchBar
        query=""
        state={idleState}
        onQueryChange={vi.fn()}
        onClear={vi.fn()}
        onRetry={vi.fn()}
        onSelect={vi.fn()}
        mapReady={true}
      />
    );

    const combobox = screen.getByRole('combobox');
    expect(combobox).not.toBeNull();
    expect(combobox.getAttribute('placeholder')).toContain('Search cities, countries');
  });

  it('renders dual trip planner rows when pickupPlace and onTargetChange are provided', () => {
    const handleTargetChange = vi.fn();
    const handleSwap = vi.fn();

    render(
      <SearchBar
        query=""
        state={idleState}
        onQueryChange={vi.fn()}
        onClear={vi.fn()}
        onRetry={vi.fn()}
        onSelect={vi.fn()}
        mapReady={true}
        pickupPlace={mockPickup}
        destinationPlace={mockDest}
        activeTarget="destination"
        onTargetChange={handleTargetChange}
        onSwap={handleSwap}
      />
    );

    // Pickup pill shows pickup place name
    expect(screen.getByText('Connaught Place')).not.toBeNull();

    // Destination is active combobox
    const destInput = screen.getByRole('combobox', { name: /Search destination/i });
    expect(destInput).not.toBeNull();
    expect(destInput.getAttribute('placeholder')).toContain('Where to?');

    // Swap button exists
    const swapBtn = screen.getByRole('button', { name: /Swap pickup and destination/i });
    fireEvent.click(swapBtn);
    expect(handleSwap).toHaveBeenCalledTimes(1);
  });

  it('switches active target to pickup when pickup pill is clicked in destination mode', () => {
    const handleTargetChange = vi.fn();

    render(
      <SearchBar
        query=""
        state={idleState}
        onQueryChange={vi.fn()}
        onClear={vi.fn()}
        onRetry={vi.fn()}
        onSelect={vi.fn()}
        mapReady={true}
        pickupPlace={mockPickup}
        activeTarget="destination"
        onTargetChange={handleTargetChange}
      />
    );

    const pickupPill = screen.getByRole('button', { name: /Connaught Place/i });
    fireEvent.click(pickupPill);

    expect(handleTargetChange).toHaveBeenCalledWith('pickup');
  });
});
