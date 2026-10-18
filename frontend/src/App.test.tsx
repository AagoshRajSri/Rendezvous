/**
 * Phase 7E.3 — End-to-end frontend integration tests for App.tsx.
 *
 * Verifies:
 * - Happy path: location selection → dispatch request → MATCHED with telemetry
 *   renders driver confirmation, verified badge, and fix timestamp.
 * - Stale/absent telemetry: MATCHED response without telemetry renders
 *   "Driver location unavailable" notice while preserving driver ID.
 * - Reset flow: clicking Reset clears dispatch state and panel.
 * - Duplicate-click protection: multiple clicks while in-flight execute only one dispatch flow.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import App from './App';
import * as dispatchService from './services/dispatch';
import { geocodingService } from './services/geocoding';

// ── Mock MapLibre GL JS ───────────────────────────────────────────────────────

vi.mock('maplibre-gl', () => {
  const mockMapInstance = {
    isStyleLoaded: () => true,
    setProjection: vi.fn(),
    setSky: vi.fn(),
    getSource: vi.fn(() => ({ setData: vi.fn() })),
    addSource: vi.fn(),
    getLayer: vi.fn(() => undefined),
    addLayer: vi.fn(),
    addControl: vi.fn(),
    fitBounds: vi.fn(),
    easeTo: vi.fn(),
    flyTo: vi.fn(),
    getZoom: () => 10,
    on: vi.fn((event: string, callback: () => void) => {
      if (event === 'style.load') {
        callback();
      }
    }),
    remove: vi.fn(),
  };

  return {
    setWorkerUrl: vi.fn(),
    Map: vi.fn(function () {
      return mockMapInstance;
    }),
    AttributionControl: vi.fn(),
    NavigationControl: vi.fn(),
    Marker: vi.fn(function () {
      return {
        setLngLat: vi.fn().mockReturnThis(),
        addTo: vi.fn().mockReturnThis(),
        remove: vi.fn(),
        getElement: vi.fn(() => document.createElement('div')),
      };
    }),
  };
});

vi.mock('maplibre-gl/dist/maplibre-gl.css', () => ({}));
vi.mock('maplibre-gl/dist/maplibre-gl-worker.mjs?url', () => ({ default: 'worker-url' }));

// ── Mock Dispatch & Geocoding Services ────────────────────────────────────────

vi.mock('./services/dispatch', () => ({
  registerDriver: vi.fn(),
  submitRideRequest: vi.fn(),
  getRequestStatus: vi.fn(),
  cancelTrip: vi.fn(),
  fetchEligibleDrivers: vi.fn(),
}));

vi.mock('./services/geocoding', () => ({
  geocodingService: {
    search: vi.fn(),
  },
}));

vi.mock('./services/routing', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./services/routing')>();
  return {
    ...actual,
    routingService: {
      getRoute: vi.fn().mockResolvedValue({
        status: 'ok',
        route: {
          distanceMeters: 6200,
          distanceKm: 6.2,
          durationSeconds: 900,
          durationMinutes: 15,
          coordinates: [
            [2.3522, 48.8566],
            [2.2945, 48.8584],
          ],
          fares: [
            {
              tierId: 'standard',
              name: 'Rendezvous Go',
              tagline: 'Standard',
              capacity: 4,
              baseFare: 50,
              distanceFare: 93,
              timeFare: 30,
              totalFare: 173,
              currency: '₹',
              etaMinutes: 3,
              icon: '🚗',
            },
          ],
          isFallback: false,
          attribution: 'OSRM',
        },
      }),
    },
  };
});

describe('App integrated dispatch & map flows (Phase 7E.3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // shouldAdvanceTime: true keeps real async/Promise resolution working while
    // giving us explicit control over setTimeout-based delays (polling sleep).
    vi.useFakeTimers({ shouldAdvanceTime: true });

    // Default geocoding result for "Paris"
    vi.mocked(geocodingService.search).mockResolvedValue({
      status: 'ok',
      suggestions: [
        {
          id: 'paris-1',
          name: 'Paris, France',
          subtitle: 'Île-de-France, France',
          lat: 48.8566,
          lon: 2.3522,
          type: 'city',
        },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** Drain the polling delay so the hook advances past its sleep. */
  async function drainPolling(): Promise<void> {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
  }

  /** Helper to search for and select "Paris" in SearchBar */
  async function selectParis() {
    const input = screen.getByRole('combobox');
    input.focus();
    fireEvent.change(input, { target: { value: 'Paris' } });

    // Wait for debounced search result option to appear
    await screen.findByRole('option', { name: /Paris, France/i });
    // Press Enter to select the first suggestion
    fireEvent.keyDown(input, { key: 'Enter' });
  }

  it('completes the dispatch journey with telemetry: MATCHED renders verified badge and timestamp', async () => {
    vi.mocked(dispatchService.registerDriver).mockResolvedValue({
      driverId: 'DRIVER-E2E-1',
      message: 'Driver registered',
    });

    vi.mocked(dispatchService.submitRideRequest).mockResolvedValue({
      requestId: 'REQ-E2E-1',
      message: 'Request queued',
    });

    vi.mocked(dispatchService.getRequestStatus).mockResolvedValue({
      requestId: 'REQ-E2E-1',
      status: 'MATCHED',
      driverId: 'DRIVER-E2E-1',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: 48.8600,
      driverLongitude: 2.3600,
      driverLocationAt: '2026-01-01T00:00:55Z',
    });

    render(<App />);

    // 1. Select Place
    await selectParis();

    // 2. Dispatch panel appears
    const requestBtn = await screen.findByRole('button', { name: /Request Real Dispatch/i });
    expect(requestBtn).not.toBeNull();

    // 3. Click Request Real Dispatch
    fireEvent.click(requestBtn);

    // 4. Drain polling delay so hook reaches MATCHED
    await drainPolling();

    // 5. MATCHED state must be visible
    expect(screen.getByText('DRIVER-E2E-1')).not.toBeNull();

    // 6. Verify telemetry badge and fix timestamp
    const badge = document.getElementById('dispatch-driver-location-badge');
    expect(badge).not.toBeNull();
    expect(screen.getByText('Driver location verified')).not.toBeNull();
    expect(badge?.textContent).toContain('Fix at');
  });

  it('completes the dispatch journey without telemetry: renders unavailable notice while preserving matched driver ID', async () => {
    vi.mocked(dispatchService.registerDriver).mockResolvedValue({
      driverId: 'DRIVER-NO-GPS',
      message: 'Driver registered',
    });

    vi.mocked(dispatchService.submitRideRequest).mockResolvedValue({
      requestId: 'REQ-NO-GPS',
      message: 'Request queued',
    });

    // Backend returns MATCHED with null coordinates (stale or unverified fix)
    vi.mocked(dispatchService.getRequestStatus).mockResolvedValue({
      requestId: 'REQ-NO-GPS',
      status: 'MATCHED',
      driverId: 'DRIVER-NO-GPS',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: null,
      driverLongitude: null,
      driverLocationAt: null,
    });

    render(<App />);

    await selectParis();

    const requestBtn = await screen.findByRole('button', { name: /Request Real Dispatch/i });
    fireEvent.click(requestBtn);

    // Drain polling delay so hook reaches MATCHED
    await drainPolling();

    // MATCHED driver ID must be displayed
    expect(screen.getByText('DRIVER-NO-GPS')).not.toBeNull();

    // Verified badge must not exist
    expect(document.getElementById('dispatch-driver-location-badge')).toBeNull();

    // Unavailable notice must be displayed
    const unavailableNotice = document.getElementById('dispatch-driver-location-unavailable');
    expect(unavailableNotice).not.toBeNull();
    expect(
      screen.getByText('Driver location unavailable (stale or unverified fix at match time)')
    ).not.toBeNull();
  });

  it('resets dispatch state and clears UI elements upon clicking Reset', async () => {
    vi.mocked(dispatchService.registerDriver).mockResolvedValue({
      driverId: 'DRIVER-RESET',
      message: 'Driver registered',
    });
    vi.mocked(dispatchService.submitRideRequest).mockResolvedValue({
      requestId: 'REQ-RESET',
      message: 'Request queued',
    });
    vi.mocked(dispatchService.getRequestStatus).mockResolvedValue({
      requestId: 'REQ-RESET',
      status: 'MATCHED',
      driverId: 'DRIVER-RESET',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: 48.86,
      driverLongitude: 2.36,
      driverLocationAt: '2026-01-01T00:00:55Z',
    });

    render(<App />);

    await selectParis();

    const requestBtn = await screen.findByRole('button', { name: /Request Real Dispatch/i });
    fireEvent.click(requestBtn);

    await drainPolling();

    expect(screen.getByText('DRIVER-RESET')).not.toBeNull();

    // Reset button should now be present
    const resetBtn = screen.getByRole('button', { name: /Reset/i });
    fireEvent.click(resetBtn);

    // Dispatch panel resets to idle: "Request Real Dispatch" is back
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Request Real Dispatch/i })).not.toBeNull();
    });
    expect(document.getElementById('dispatch-driver-location-badge')).toBeNull();
  });

  it('guards against duplicate submissions on rapid double-clicking of the request button', async () => {
    vi.mocked(dispatchService.registerDriver).mockResolvedValue({
      driverId: 'D-DUP',
      message: 'ok',
    });
    vi.mocked(dispatchService.submitRideRequest).mockResolvedValue({
      requestId: 'REQ-DUP',
      message: 'ok',
    });
    vi.mocked(dispatchService.getRequestStatus).mockResolvedValue({
      requestId: 'REQ-DUP',
      status: 'MATCHED',
      driverId: 'D-DUP',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: 48.86,
      driverLongitude: 2.36,
      driverLocationAt: '2026-01-01T00:00:55Z',
    });

    render(<App />);

    await selectParis();

    const requestBtn = await screen.findByRole('button', { name: /Request Real Dispatch/i });

    // Double-click rapidly
    fireEvent.click(requestBtn);
    fireEvent.click(requestBtn);

    await drainPolling();

    expect(screen.getByText('D-DUP')).not.toBeNull();

    // Should only have initiated registration for the fleet once (not duplicated across both clicks)
    expect(dispatchService.submitRideRequest).toHaveBeenCalledTimes(1);
    expect(dispatchService.registerDriver).toHaveBeenCalled();
  });

  it('previews road route, distance, and fare estimates when both pickup and destination are selected (Phase 11.1)', async () => {
    vi.mocked(geocodingService.search).mockImplementation(async (query: string) => {
      if (query.includes('Eiffel')) {
        return {
          status: 'ok',
          suggestions: [
            {
              id: 'eiffel-1',
              name: 'Eiffel Tower',
              subtitle: 'Paris, France',
              lat: 48.8584,
              lon: 2.2945,
              type: 'monument',
            },
          ],
        };
      }
      return {
        status: 'ok',
        suggestions: [
          {
            id: 'paris-1',
            name: 'Paris, France',
            subtitle: 'Île-de-France, France',
            lat: 48.8566,
            lon: 2.3522,
            type: 'city',
          },
        ],
      };
    });

    vi.mocked(dispatchService.registerDriver).mockResolvedValue({
      driverId: 'D-ROUTE-1',
      message: 'Driver registered',
    });
    vi.mocked(dispatchService.submitRideRequest).mockResolvedValue({
      requestId: 'REQ-ROUTE-1',
      message: 'Request queued',
    });
    vi.mocked(dispatchService.getRequestStatus).mockResolvedValue({
      requestId: 'REQ-ROUTE-1',
      status: 'MATCHED',
      driverId: 'D-ROUTE-1',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: 48.86,
      driverLongitude: 2.36,
      driverLocationAt: '2026-01-01T00:00:55Z',
    });

    render(<App />);

    // 1. Select Pickup (Paris)
    await selectParis();

    // 2. Select Destination (Eiffel Tower)
    const destInput = await screen.findByRole('combobox', { name: /Search destination/i });
    destInput.focus();
    fireEvent.change(destInput, { target: { value: 'Eiffel' } });

    await screen.findByRole('option', { name: /Eiffel Tower/i });
    fireEvent.keyDown(destInput, { key: 'Enter' });

    // 3. RoutePreviewCard should render with distance, duration, and fare
    expect(await screen.findByText('Trip Route Preview')).not.toBeNull();
    expect(screen.getByText('6.2 km')).not.toBeNull();
    expect(screen.getByText('15 min')).not.toBeNull();
    expect(screen.getByText('Request Rendezvous Go')).not.toBeNull();

    // 4. Click Request Rendezvous Go to initiate dispatch
    const requestRideBtn = screen.getByRole('button', { name: /Request Rendezvous Go/i });
    fireEvent.click(requestRideBtn);

    await drainPolling();

    // 5. Matched driver confirmation appears
    expect(screen.getByText('D-ROUTE-1')).not.toBeNull();
    expect(dispatchService.submitRideRequest).toHaveBeenCalledWith(
      expect.objectContaining({
        latitude: 48.8566,
        longitude: 2.3522,
      }),
      expect.any(AbortSignal),
    );
  });

  it('animates road approach with live ETA card and supports ride cancellation (Phase 11.2)', async () => {
    vi.mocked(dispatchService.registerDriver).mockResolvedValue({
      driverId: 'D-APPROACH',
      message: 'Driver registration accepted',
    });
    vi.mocked(dispatchService.submitRideRequest).mockResolvedValue({
      requestId: 'REQ-APPROACH-1',
      message: 'Ride request accepted',
    });
    vi.mocked(dispatchService.getRequestStatus).mockResolvedValue({
      requestId: 'REQ-APPROACH-1',
      status: 'MATCHED',
      driverId: 'D-APPROACH',
      receivedAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:01:00Z',
      driverLatitude: 48.86,
      driverLongitude: 2.36,
      driverLocationAt: '2026-01-01T00:00:55Z',
    });
    vi.mocked(dispatchService.cancelTrip).mockResolvedValue({
      requestId: 'REQ-APPROACH-1',
      driverId: 'D-APPROACH',
      status: 'CANCELLED',
      outcome: 'TRANSITIONED',
      message: 'Trip cancelled',
    });

    render(<App />);

    await selectParis();

    const requestBtn = await screen.findByRole('button', { name: /Request Real Dispatch/i });
    fireEvent.click(requestBtn);

    await drainPolling();

    // 1. Confirmed match and driver heading to pickup status
    expect(screen.getByText('D-APPROACH')).not.toBeNull();
    expect(screen.getByText('Driver heading to pickup')).not.toBeNull();

    // 2. Live ETA & Distance card is present
    expect(document.getElementById('dispatch-eta-card')).not.toBeNull();
    expect(document.getElementById('dispatch-eta-value')).not.toBeNull();
    expect(document.getElementById('dispatch-distance-value')).not.toBeNull();

    // 3. Cancel Ride button is available
    const cancelBtn = screen.getByRole('button', { name: /Cancel Ride/i });
    expect(cancelBtn).not.toBeNull();

    // 4. Click Cancel Ride
    fireEvent.click(cancelBtn);

    // 5. Verifies cancelTrip service was invoked with correct request and driver IDs
    await waitFor(() => {
      expect(dispatchService.cancelTrip).toHaveBeenCalledWith(
        expect.stringMatching(/^req-/),
        'D-APPROACH',
        expect.any(Object),
      );
    });

    // 6. UI transitions cleanly to cancelled state
    expect(await screen.findByText('Ride Cancelled')).not.toBeNull();
    expect(screen.getByRole('button', { name: /Book Another Ride/i })).not.toBeNull();
  });
});

