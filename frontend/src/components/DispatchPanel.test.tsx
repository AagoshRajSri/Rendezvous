import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DispatchPanel } from './DispatchPanel';
import type { DispatchState, DispatchTrace } from '../types/dispatch';
import { useDispatchTrace } from '../hooks/useDispatchTrace';

vi.mock('../hooks/useDispatchTrace', () => ({
  useDispatchTrace: vi.fn(),
}));

const mockUseDispatchTrace = vi.mocked(useDispatchTrace);

const IDLE_STATE: DispatchState = {
  phase: 'idle',
  requestId: null,
  matchedDriverId: null,
  matchedDriverLocation: null,
  matchedDriverLocationAt: null,
  errorMessage: null,
  updatedAt: null,
};

describe('DispatchPanel', () => {
  beforeEach(() => {
    mockUseDispatchTrace.mockReturnValue({
      state: { status: 'idle', trace: null, errorMessage: null },
      refresh: vi.fn(),
    });
  });
  it('renders place name, disclaimer, and Request Real Dispatch button in idle phase', () => {
    const onRequestDispatch = vi.fn();
    const onReset = vi.fn();

    render(
      <DispatchPanel
        placeName="London"
        dispatchState={IDLE_STATE}
        onRequestDispatch={onRequestDispatch}
        onReset={onReset}
      />
    );

    expect(screen.getByText('London')).not.toBeNull();
    expect(screen.getByText(/Demo infrastructure — unauthenticated/i)).not.toBeNull();
    const btn = screen.getByRole('button', { name: /Request Real Dispatch/i });
    expect(btn).not.toBeNull();

    fireEvent.click(btn);
    expect(onRequestDispatch).toHaveBeenCalledTimes(1);
    expect(onReset).not.toHaveBeenCalled();
  });

  it('renders registering status when in registering phase', () => {
    const state: DispatchState = { ...IDLE_STATE, phase: 'registering' };

    render(
      <DispatchPanel
        placeName="Paris"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={vi.fn()}
      />
    );

    expect(screen.getByText(/Registering demo driver…/i)).not.toBeNull();
    expect(screen.getByText(/Registration queued in Kafka/i)).not.toBeNull();
  });

  it('renders submitting status when in submitting phase', () => {
    const state: DispatchState = { ...IDLE_STATE, phase: 'submitting', requestId: 'REQ-123' };

    render(
      <DispatchPanel
        placeName="Paris"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={vi.fn()}
      />
    );

    expect(screen.getByText(/Submitting ride request…/i)).not.toBeNull();
    expect(screen.getByText(/Request queued for matching/i)).not.toBeNull();
  });

  it('renders polling status when in polling phase', () => {
    const state: DispatchState = { ...IDLE_STATE, phase: 'polling', requestId: 'REQ-123' };

    render(
      <DispatchPanel
        placeName="Tokyo"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={vi.fn()}
      />
    );

    expect(screen.getByText(/Waiting for backend match…/i)).not.toBeNull();
  });

  it('renders confirmed match card and Reset button when matched', () => {
    const onReset = vi.fn();
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'matched',
      requestId: 'REQ-123',
      matchedDriverId: 'DRIVER-99',
      updatedAt: '2026-10-15T12:00:00Z',
    };

    render(
      <DispatchPanel
        placeName="Tokyo"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={onReset}
      />
    );

    expect(screen.getByText(/Match confirmed/i)).not.toBeNull();
    expect(screen.getByText('DRIVER-99')).not.toBeNull();
    const resetBtn = screen.getByRole('button', { name: /Reset/i });
    expect(resetBtn).not.toBeNull();

    fireEvent.click(resetBtn);
    expect(onReset).toHaveBeenCalledTimes(1);
  });

  it('renders no_drivers terminal state with clear explanation, Retry, and Reset buttons', () => {
    const onRequestDispatch = vi.fn();
    const onReset = vi.fn();
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'no_drivers',
      requestId: 'REQ-456',
    };

    render(
      <DispatchPanel
        placeName="Berlin"
        dispatchState={state}
        onRequestDispatch={onRequestDispatch}
        onReset={onReset}
      />
    );

    expect(screen.getByText('No drivers available')).not.toBeNull();
    expect(
      screen.getByText(/Registration was queued, but no available driver was ready near this location/i)
    ).not.toBeNull();

    const retryBtn = screen.getByRole('button', { name: /Retry Dispatch/i });
    const resetBtn = screen.getByRole('button', { name: /Reset/i });
    expect(retryBtn).not.toBeNull();
    expect(resetBtn).not.toBeNull();

    fireEvent.click(retryBtn);
    expect(onRequestDispatch).toHaveBeenCalledTimes(1);

    fireEvent.click(resetBtn);
    expect(onReset).toHaveBeenCalledTimes(1);
  });

  it('renders error state with error message, Retry, and Reset buttons', () => {
    const onRequestDispatch = vi.fn();
    const onReset = vi.fn();
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'error',
      errorMessage: 'Kafka broker unreachable',
    };

    render(
      <DispatchPanel
        placeName="Rome"
        dispatchState={state}
        onRequestDispatch={onRequestDispatch}
        onReset={onReset}
      />
    );

    expect(screen.getByText('Dispatch error')).not.toBeNull();
    expect(screen.getByRole('alert').textContent).toContain('Kafka broker unreachable');

    const retryBtn = screen.getByRole('button', { name: /Retry Dispatch/i });
    const resetBtn = screen.getByRole('button', { name: /Reset/i });
    expect(retryBtn).not.toBeNull();
    expect(resetBtn).not.toBeNull();

    fireEvent.click(retryBtn);
    expect(onRequestDispatch).toHaveBeenCalledTimes(1);

    fireEvent.click(resetBtn);
    expect(onReset).toHaveBeenCalledTimes(1);
  });

  it('renders the driver location verified badge with fix time when matchedDriverLocation and valid timestamp are present', () => {
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'matched',
      matchedDriverId: 'DRIVER-GPS',
      matchedDriverLocation: { lat: 48.8566, lon: 2.3522 },
      matchedDriverLocationAt: '2026-01-01T00:00:55Z',
      updatedAt: '2026-01-01T00:01:00Z',
    };

    render(
      <DispatchPanel
        placeName="Paris"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={vi.fn()}
      />
    );

    const badge = document.getElementById('dispatch-driver-location-badge');
    expect(badge).not.toBeNull();
    expect(screen.getByText('Driver location verified')).not.toBeNull();
    const badgeText = badge?.textContent ?? '';
    expect(badgeText).toContain('Fix at');
  });

  it('renders the verified badge without fix time when timestamp is null or missing', () => {
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'matched',
      matchedDriverId: 'DRIVER-GPS-NO-TIME',
      matchedDriverLocation: { lat: 48.8566, lon: 2.3522 },
      matchedDriverLocationAt: null,
      updatedAt: '2026-01-01T00:01:00Z',
    };

    render(
      <DispatchPanel
        placeName="Paris"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={vi.fn()}
      />
    );

    const badge = document.getElementById('dispatch-driver-location-badge');
    expect(badge).not.toBeNull();
    expect(screen.getByText('Driver location verified')).not.toBeNull();
    const badgeText = badge?.textContent ?? '';
    expect(badgeText).not.toContain('Fix at');
    expect(badgeText).not.toContain('Invalid Date');
  });

  it('renders the verified badge without "Invalid Date" when timestamp is malformed', () => {
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'matched',
      matchedDriverId: 'DRIVER-GPS-BAD-TIME',
      matchedDriverLocation: { lat: 48.8566, lon: 2.3522 },
      matchedDriverLocationAt: 'not-a-valid-date-string',
      updatedAt: '2026-01-01T00:01:00Z',
    };

    render(
      <DispatchPanel
        placeName="Paris"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={vi.fn()}
      />
    );

    const badge = document.getElementById('dispatch-driver-location-badge');
    expect(badge).not.toBeNull();
    expect(screen.getByText('Driver location verified')).not.toBeNull();
    const badgeText = badge?.textContent ?? '';
    expect(badgeText).not.toContain('Fix at');
    expect(badgeText).not.toContain('Invalid Date');
  });

  it('renders the unavailable-location notice when matchedDriverLocation is null while preserving matched driver ID', () => {
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'matched',
      matchedDriverId: 'DRIVER-NO-GPS',
      matchedDriverLocation: null,
      matchedDriverLocationAt: null,
      updatedAt: '2026-01-01T00:01:00Z',
    };

    render(
      <DispatchPanel
        placeName="Paris"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={vi.fn()}
      />
    );

    expect(document.getElementById('dispatch-driver-location-badge')).toBeNull();
    expect(document.getElementById('dispatch-driver-location-unavailable')).not.toBeNull();
    expect(
      screen.getByText('Driver location unavailable (stale or unverified fix at match time)')
    ).not.toBeNull();
    expect(screen.getByText('DRIVER-NO-GPS')).not.toBeNull();
  });

  it('renders live ETA and remaining distance when provided in matched phase', () => {
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'matched',
      matchedDriverId: 'DRIVER-LIVE',
      matchedDriverLocation: { lat: 17.385, lon: 78.4867 },
      updatedAt: '2026-10-18T10:00:00Z',
    };

    render(
      <DispatchPanel
        placeName="Hyderabad"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={vi.fn()}
        remainingDistanceMeters={1500}
        remainingDurationSeconds={120}
        approachStatus="ready"
      />
    );

    expect(screen.getByText('1.5 km')).not.toBeNull();
    expect(screen.getByText('~2 mins')).not.toBeNull();
    expect(screen.getByText('Driver heading to pickup')).not.toBeNull();
  });

  it('renders Cancel Ride button and invokes onCancelRide', () => {
    const onCancelRide = vi.fn();
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'matched',
      matchedDriverId: 'DRIVER-TO-CANCEL',
      updatedAt: '2026-10-18T10:00:00Z',
    };

    render(
      <DispatchPanel
        placeName="Hyderabad"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onCancelRide={onCancelRide}
        onReset={vi.fn()}
      />
    );

    const cancelBtn = screen.getByRole('button', { name: /Cancel Ride/i });
    expect(cancelBtn).not.toBeNull();
    fireEvent.click(cancelBtn);
    expect(onCancelRide).toHaveBeenCalledTimes(1);
  });

  it('disables Cancel Ride button and displays pending state when isCancelling is true', () => {
    const onCancelRide = vi.fn();
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'matched',
      matchedDriverId: 'DRIVER-CANCEL-IN-FLIGHT',
      updatedAt: '2026-10-18T10:00:00Z',
    };

    render(
      <DispatchPanel
        placeName="Hyderabad"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onCancelRide={onCancelRide}
        isCancelling={true}
        onReset={vi.fn()}
      />
    );

    const cancelBtn = screen.getByRole('button', { name: /Cancelling…/i }) as HTMLButtonElement;
    expect(cancelBtn).not.toBeNull();
    expect(cancelBtn.disabled).toBe(true);

    fireEvent.click(cancelBtn);
    expect(onCancelRide).not.toHaveBeenCalled();
  });

  it('renders driver arrived status when isVehicleArrived is true', () => {
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'matched',
      matchedDriverId: 'DRIVER-ARRIVED',
      updatedAt: '2026-10-18T10:00:00Z',
    };

    render(
      <DispatchPanel
        placeName="Hyderabad"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={vi.fn()}
        isVehicleArrived={true}
        remainingDistanceMeters={0}
        remainingDurationSeconds={0}
      />
    );

    expect(screen.getByText('Driver arrived at pickup')).not.toBeNull();
    expect(screen.getByText('Arrived')).not.toBeNull();
  });

  it('renders cancelled phase with Book Another Ride and Reset buttons', () => {
    const onRequestDispatch = vi.fn();
    const onReset = vi.fn();
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'cancelled',
    };

    render(
      <DispatchPanel
        placeName="Hyderabad"
        dispatchState={state}
        onRequestDispatch={onRequestDispatch}
        onReset={onReset}
      />
    );

    expect(screen.getByText('Ride Cancelled')).not.toBeNull();
    const bookAgainBtn = screen.getByRole('button', { name: /Book Another Ride/i });
    expect(bookAgainBtn).not.toBeNull();
    fireEvent.click(bookAgainBtn);
    expect(onRequestDispatch).toHaveBeenCalledTimes(1);
  });

  // ── Phase 11.3: New lifecycle tests ────────────────────────────────────────

  it('renders en_route phase with live ETA, distance, and cancel button', () => {
    const onCancelRide = vi.fn();
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'en_route',
      matchedDriverId: 'DRIVER-ENROUTE',
      updatedAt: '2026-10-18T10:00:00Z',
    };

    render(
      <DispatchPanel
        placeName="London"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onCancelRide={onCancelRide}
        onReset={vi.fn()}
        remainingDistanceMeters={850}
        remainingDurationSeconds={140}
      />
    );

    expect(screen.getByText('Driver heading to pickup')).not.toBeNull();
    expect(screen.getByText('850 m')).not.toBeNull();
    expect(screen.getByText('~3 mins')).not.toBeNull();
    expect(screen.getByRole('button', { name: /Cancel Ride/i })).not.toBeNull();
  });

  it('renders arrived phase with Start Trip button and enables boarding when destination is valid', () => {
    const onStartTrip = vi.fn();
    const onCancelRide = vi.fn();
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'arrived',
      matchedDriverId: 'DRIVER-WAITING',
      updatedAt: '2026-10-18T10:00:00Z',
    };

    render(
      <DispatchPanel
        placeName="London"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onStartTrip={onStartTrip}
        onCancelRide={onCancelRide}
        canStartTrip={true}
        onReset={vi.fn()}
      />
    );

    expect(screen.getByText('Driver arrived at pickup')).not.toBeNull();
    expect(screen.getByText(/Vehicle waiting at pickup location/i)).not.toBeNull();

    const startBtn = screen.getByRole('button', { name: /Start Trip/i }) as HTMLButtonElement;
    expect(startBtn).not.toBeNull();
    expect(startBtn.disabled).toBe(false);

    fireEvent.click(startBtn);
    expect(onStartTrip).toHaveBeenCalledTimes(1);
  });

  it('disables Start Trip and shows guidance when destination is missing in arrived phase', () => {
    const onStartTrip = vi.fn();
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'arrived',
      matchedDriverId: 'DRIVER-WAITING',
      updatedAt: '2026-10-18T10:00:00Z',
    };

    render(
      <DispatchPanel
        placeName="London"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onStartTrip={onStartTrip}
        canStartTrip={false}
        missingDestinationMessage="Please select a destination using the search bar before starting your trip."
        onReset={vi.fn()}
      />
    );

    expect(screen.getByText('Destination Required')).not.toBeNull();
    expect(screen.getByText(/Please select a destination/i)).not.toBeNull();

    const startBtn = screen.getByRole('button', { name: /Start Trip/i }) as HTMLButtonElement;
    expect(startBtn.disabled).toBe(true);

    fireEvent.click(startBtn);
    expect(onStartTrip).not.toHaveBeenCalled();
  });

  it('disables Start Trip and shows spinner when isStarting is true', () => {
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'arrived',
      matchedDriverId: 'DRIVER-WAITING',
    };

    render(
      <DispatchPanel
        placeName="London"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onStartTrip={vi.fn()}
        isStarting={true}
        canStartTrip={true}
        onReset={vi.fn()}
      />
    );

    const startBtn = screen.getByRole('button', { name: /Starting Trip…/i }) as HTMLButtonElement;
    expect(startBtn).not.toBeNull();
    expect(startBtn.disabled).toBe(true);
  });

  it('renders in_progress phase with destination info, live ETA, Complete Trip button, and NO cancel button', () => {
    const onCompleteTrip = vi.fn();
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'in_progress',
      matchedDriverId: 'DRIVER-ONTRIP',
    };

    const destinationPlace = {
      id: 'dest-1',
      name: 'Heathrow Airport',
      subtitle: 'London',
      type: 'airport' as const,
      lat: 51.47,
      lon: -0.45,
    };

    render(
      <DispatchPanel
        placeName="London Center"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onCompleteTrip={onCompleteTrip}
        destinationPlace={destinationPlace}
        remainingDistanceMeters={4200}
        remainingDurationSeconds={480}
        onReset={vi.fn()}
      />
    );

    expect(screen.getByText('On road to destination')).not.toBeNull();
    expect(screen.getByText(/Traveling to Heathrow Airport/i)).not.toBeNull();
    expect(screen.getByText('Destination ETA')).not.toBeNull();
    expect(screen.getByText('4.2 km')).not.toBeNull();
    expect(screen.getByText('~8 mins')).not.toBeNull();

    // Cancellation button must NOT be present
    expect(screen.queryByRole('button', { name: /Cancel/i })).toBeNull();

    const completeBtn = screen.getByRole('button', { name: /Complete Trip/i }) as HTMLButtonElement;
    expect(completeBtn).not.toBeNull();
    expect(completeBtn.disabled).toBe(false);

    fireEvent.click(completeBtn);
    expect(onCompleteTrip).toHaveBeenCalledTimes(1);
  });

  it('disables Complete Trip button when isCompleting is true', () => {
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'in_progress',
      matchedDriverId: 'DRIVER-ONTRIP',
    };

    render(
      <DispatchPanel
        placeName="London Center"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onCompleteTrip={vi.fn()}
        isCompleting={true}
        onReset={vi.fn()}
      />
    );

    const completeBtn = screen.getByRole('button', { name: /Completing Trip…/i }) as HTMLButtonElement;
    expect(completeBtn).not.toBeNull();
    expect(completeBtn.disabled).toBe(true);
  });

  it('renders completed phase with comprehensive trip summary and Book Another Ride action', () => {
    const onReset = vi.fn();
    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'completed',
      matchedDriverId: 'DRIVER-VIP',
    };

    const destinationPlace = {
      id: 'dest-1',
      name: 'Oxford Circus',
      subtitle: 'London',
      type: 'place' as const,
      lat: 51.515,
      lon: -0.141,
    };

    const mockRoute = {
      coordinates: [[-0.12, 51.5], [-0.14, 51.515]] as [number, number][],
      distanceMeters: 3500,
      distanceKm: 3.5,
      durationSeconds: 600,
      durationMinutes: 10,
      fares: [
        {
          tierId: 'standard' as const,
          name: 'Rendezvous Go',
          tagline: 'Affordable everyday rides',
          capacity: 4,
          baseFare: 50,
          distanceFare: 55,
          timeFare: 20,
          totalFare: 125,
          currency: '₹',
          etaMinutes: 3,
          icon: '🚗',
        },
      ],
      isFallback: false,
      attribution: 'OSRM',
    };

    render(
      <DispatchPanel
        placeName="Piccadilly"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={onReset}
        destinationPlace={destinationPlace}
        route={mockRoute}
        selectedTier="standard"
      />
    );

    expect(screen.getByText('Ride Completed')).not.toBeNull();
    expect(screen.getByText('DRIVER-VIP')).not.toBeNull();
    expect(screen.getAllByText('Piccadilly').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Oxford Circus')).not.toBeNull();
    expect(screen.getByText(/3.5 km · 10 min/i)).not.toBeNull();
    expect(screen.getByText('₹125')).not.toBeNull();
    expect(screen.getByText('Quoted Fare (Fixed Quote)')).not.toBeNull();

    const bookAnotherBtn = screen.getByRole('button', { name: /Book Another Ride/i });
    expect(bookAnotherBtn).not.toBeNull();
    fireEvent.click(bookAnotherBtn);
    expect(onReset).toHaveBeenCalledTimes(1);
  });

  // ── Phase 11.4E: Dispatch trace accordion integration ──────────────────────

  const mockTrace: DispatchTrace = {
    requestId: 'REQ-456',
    algorithm: 'AuctionMatcher',
    pickupLatitude: 12.9716,
    pickupLongitude: 77.5946,
    startedAt: '2026-10-19T02:00:00Z',
    durationNanos: 2_500_000,
    stages: [],
    candidates: [
      {
        driverId: 'D-WIN',
        latitude: 12.972,
        longitude: 77.595,
        distanceKm: 0.3,
        etaCost: 1.0,
        densityCost: 0.2,
        opportunityCost: 0.0,
        totalCost: 1.2,
        rank: 0,
        outcome: 'MATCHED',
        rejectionReason: null,
      },
    ],
    winnerDriverId: 'D-WIN',
    matched: true,
    totalFleetSize: 80,
    decisionSummary: 'Matched with driver D-WIN',
  };

  it('does not render dispatch trace card when requestId is null', () => {
    render(
      <DispatchPanel
        placeName="London"
        dispatchState={IDLE_STATE}
        onRequestDispatch={vi.fn()}
        onReset={vi.fn()}
      />
    );

    expect(screen.queryByRole('button', { name: /Dispatch Trace/i })).toBeNull();
  });

  it('renders collapsed dispatch trace card when requestId is present', () => {
    mockUseDispatchTrace.mockReturnValue({
      state: { status: 'ready', trace: mockTrace, errorMessage: null },
      refresh: vi.fn(),
    });

    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'matched',
      requestId: 'REQ-456',
      matchedDriverId: 'D-WIN',
    };

    render(
      <DispatchPanel
        placeName="London"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={vi.fn()}
      />
    );

    const toggle = screen.getByRole('button', { name: /Dispatch Trace/i });
    expect(toggle).not.toBeNull();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(screen.getByText(/AuctionMatcher \(2.50 ms\)/i)).not.toBeNull();

    // Detail content is hidden when collapsed
    expect(screen.queryByText('Execution Summary')).toBeNull();

    // Expand
    fireEvent.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByText('Execution Summary')).not.toBeNull();
    expect(screen.getByText('Matched with driver D-WIN')).not.toBeNull();
    expect(screen.getAllByText('D-WIN').length).toBeGreaterThanOrEqual(1);
  });

  it('triggers refreshTrace when retry is clicked in not_found state', () => {
    const mockRefresh = vi.fn();
    mockUseDispatchTrace.mockReturnValue({
      state: { status: 'not_found', trace: null, errorMessage: null },
      refresh: mockRefresh,
    });

    const state: DispatchState = {
      ...IDLE_STATE,
      phase: 'matched',
      requestId: 'REQ-456',
      matchedDriverId: 'D-WIN',
    };

    render(
      <DispatchPanel
        placeName="London"
        dispatchState={state}
        onRequestDispatch={vi.fn()}
        onReset={vi.fn()}
        defaultTraceExpanded={true}
      />
    );

    expect(screen.getByText('Trace Not Available')).not.toBeNull();
    const retryBtn = screen.getByRole('button', { name: /Check Again/i });
    fireEvent.click(retryBtn);
    expect(mockRefresh).toHaveBeenCalledTimes(1);
  });
});

