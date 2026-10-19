/**
 * Phase 11.4E — Unit tests for DispatchTraceCard component.
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DispatchTraceCard, formatTraceCost, formatTraceDuration } from './DispatchTraceCard';
import type { DispatchTraceState } from '../hooks/useDispatchTrace';
import type { DispatchTrace } from '../types/dispatch';

function sampleTrace(overrides: Partial<DispatchTrace> = {}): DispatchTrace {
  return {
    requestId: 'REQ-100',
    algorithm: 'AuctionMatcher',
    pickupLatitude: 12.9716,
    pickupLongitude: 77.5946,
    startedAt: '2026-10-19T02:00:00Z',
    durationNanos: 2_500_000,
    stages: [
      {
        stageIndex: 0,
        stageType: 'QUAD_TREE_SEARCH',
        windowSize: 16,
        minLatitude: 12.90,
        minLongitude: 77.50,
        maxLatitude: 13.00,
        maxLongitude: 77.60,
        visitedNodeCount: 12,
        prunedNodeCount: 4,
        candidatesDiscovered: 3,
      },
    ],
    candidates: [
      {
        driverId: 'DRV-1',
        latitude: 12.9720,
        longitude: 77.5950,
        distanceKm: 0.5,
        etaCost: 1.2,
        densityCost: 0.4,
        opportunityCost: 0.1,
        totalCost: 1.7,
        rank: 0,
        outcome: 'MATCHED',
        rejectionReason: null,
      },
      {
        driverId: 'DRV-2',
        latitude: 12.9800,
        longitude: 77.6000,
        distanceKm: 1.2,
        etaCost: null,
        densityCost: null,
        opportunityCost: null,
        totalCost: null,
        rank: 1,
        outcome: 'OUTCOMPETED',
        rejectionReason: 'Higher total cost than winner',
      },
    ],
    winnerDriverId: 'DRV-1',
    matched: true,
    totalFleetSize: 150,
    decisionSummary: 'Matched with driver DRV-1 (total cost: 1.70)',
    ...overrides,
  };
}

describe('DispatchTraceCard formatters', () => {
  it('formats duration nanoseconds accurately', () => {
    expect(formatTraceDuration(500)).toBe('500 ns');
    expect(formatTraceDuration(25_000)).toBe('25.0 µs');
    expect(formatTraceDuration(2_500_000)).toBe('2.50 ms');
    expect(formatTraceDuration(NaN)).toBe('—');
    expect(formatTraceDuration(-10)).toBe('—');
  });

  it('formats cost safely without ever outputting NaN', () => {
    expect(formatTraceCost(1.7)).toBe('1.70');
    expect(formatTraceCost(0)).toBe('0.00');
    expect(formatTraceCost(null)).toBe('—');
    expect(formatTraceCost(undefined)).toBe('—');
    expect(formatTraceCost(NaN)).toBe('—');
  });
});

describe('DispatchTraceCard component', () => {
  it('returns null when state status is idle', () => {
    const state: DispatchTraceState = { status: 'idle', trace: null, errorMessage: null };
    const { container } = render(<DispatchTraceCard state={state} onRefresh={vi.fn()} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders collapsed by default with status pill and toggles on click', () => {
    const state: DispatchTraceState = {
      status: 'ready',
      trace: sampleTrace(),
      errorMessage: null,
    };
    const { container } = render(<DispatchTraceCard state={state} onRefresh={vi.fn()} />);

    const toggle = screen.getByRole('button', { name: /Dispatch Trace/i });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(screen.getByText(/AuctionMatcher \(2.50 ms\)/i)).not.toBeNull();

    // Body is not rendered when collapsed
    expect(container.querySelector('#dispatch-trace-panel-body')).toBeNull();

    // Click to expand
    fireEvent.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(container.querySelector('#dispatch-trace-panel-body')).not.toBeNull();
    expect(screen.getByText(/Execution Summary/i)).not.toBeNull();

    // Click again to collapse
    fireEvent.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(container.querySelector('#dispatch-trace-panel-body')).toBeNull();
  });

  it('renders loading state when expanded', () => {
    const state: DispatchTraceState = { status: 'loading', trace: null, errorMessage: null };
    render(<DispatchTraceCard state={state} onRefresh={vi.fn()} defaultExpanded />);

    expect(screen.getByText('Loading…')).not.toBeNull();
    expect(screen.getByText(/Retrieving dispatch execution trace…/i)).not.toBeNull();
  });

  it('renders neutral 404 not-found state with retry button', () => {
    const onRefresh = vi.fn();
    const state: DispatchTraceState = { status: 'not_found', trace: null, errorMessage: null };
    render(<DispatchTraceCard state={state} onRefresh={onRefresh} defaultExpanded />);

    expect(screen.getByText('Pending')).not.toBeNull();
    expect(screen.getByText('Trace Not Available')).not.toBeNull();
    expect(
      screen.getByText(
        /Dispatch trace is currently unavailable. It may not yet be generated, or has expired or been evicted from memory./i,
      ),
    ).not.toBeNull();

    const checkBtn = screen.getByRole('button', { name: /Check Again/i });
    fireEvent.click(checkBtn);
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it('renders error state with retry button', () => {
    const onRefresh = vi.fn();
    const state: DispatchTraceState = {
      status: 'error',
      trace: null,
      errorMessage: 'Backend store unavailable',
    };
    render(<DispatchTraceCard state={state} onRefresh={onRefresh} defaultExpanded />);

    expect(screen.getByText('Error')).not.toBeNull();
    expect(screen.getByText('Failed to Load Trace')).not.toBeNull();
    expect(screen.getByText('Backend store unavailable')).not.toBeNull();

    const retryBtn = screen.getByRole('button', { name: /Retry/i });
    fireEvent.click(retryBtn);
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it('renders complete trace summary, candidates, and spatial stages in ready state', () => {
    const onRefresh = vi.fn();
    const trace = sampleTrace();
    const state: DispatchTraceState = { status: 'ready', trace, errorMessage: null };

    render(<DispatchTraceCard state={state} onRefresh={onRefresh} defaultExpanded />);

    // Header toolbar & summary
    expect(screen.getByText('ID: REQ-100')).not.toBeNull();
    expect(screen.getByText('AuctionMatcher')).not.toBeNull();
    expect(screen.getByText('2.50 ms')).not.toBeNull();
    expect(screen.getByText('Matched with driver DRV-1 (total cost: 1.70)')).not.toBeNull();
    expect(screen.getByText('2 / 150')).not.toBeNull();

    // Candidates table
    expect(screen.getAllByText('DRV-1').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('0.50 km')).not.toBeNull();
    expect(screen.getByText('1.70')).not.toBeNull();
    expect(screen.getByText('1.20 / 0.40 / 0.10')).not.toBeNull();
    expect(screen.getByText('MATCHED')).not.toBeNull();

    // Candidate 2 with null costs
    expect(screen.getByText('DRV-2')).not.toBeNull();
    expect(screen.getByText('1.20 km')).not.toBeNull();
    expect(screen.getByText('— / — / —')).not.toBeNull();
    expect(screen.getByText('OUTCOMPETED')).not.toBeNull();
    expect(screen.getByText('Higher total cost than winner')).not.toBeNull();

    // Spatial stages
    expect(screen.getByText(/Stage 1: QUAD_TREE_SEARCH/i)).not.toBeNull();
    expect(screen.getByText(/Window: 16/i)).not.toBeNull();
    expect(screen.getByText(/Visited: 12/i)).not.toBeNull();
    expect(screen.getByText(/Pruned: 4/i)).not.toBeNull();
    expect(screen.getByText(/Discovered: 3/i)).not.toBeNull();
    expect(screen.getByText(/Bounds: \[12.90, 77.50\] → \[13.00, 77.60\]/i)).not.toBeNull();

    // Toolbar refresh button
    const refreshBtn = screen.getByRole('button', { name: /↻ Refresh/i });
    fireEvent.click(refreshBtn);
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it('handles empty spatial stages list gracefully without crashing', () => {
    const trace = sampleTrace({ stages: [] });
    const state: DispatchTraceState = { status: 'ready', trace, errorMessage: null };

    render(<DispatchTraceCard state={state} onRefresh={vi.fn()} defaultExpanded />);

    expect(
      screen.getByText('No spatial exploration stages recorded for this algorithm.'),
    ).not.toBeNull();
  });

  it('handles unmatched decision outcome gracefully', () => {
    const trace = sampleTrace({
      matched: false,
      winnerDriverId: null,
      decisionSummary: 'Fleet exhausted or no available candidate',
      candidates: [],
    });
    const state: DispatchTraceState = { status: 'ready', trace, errorMessage: null };

    render(<DispatchTraceCard state={state} onRefresh={vi.fn()} defaultExpanded />);

    expect(screen.getByText('Unmatched')).not.toBeNull();
    expect(screen.getByText('None')).not.toBeNull();
    expect(screen.getByText('No candidate drivers were evaluated.')).not.toBeNull();
  });
});
