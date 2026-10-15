import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { DispatchPanel } from './DispatchPanel';
import type { DispatchState } from '../types/dispatch';

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
});
