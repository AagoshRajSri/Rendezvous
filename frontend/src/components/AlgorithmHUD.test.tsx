import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { AlgorithmHUD } from './AlgorithmHUD';
import type { DispatchTrace } from '../types/dispatch';
import type { RouteSummary } from '../types/routing';

const MOCK_TRACE: DispatchTrace = {
  requestId: 'REQ-123',
  algorithm: 'QUADTREE_SPATIAL_INDEX + MULTIOBJECTIVE_SCORING',
  pickupLatitude: 18.65,
  pickupLongitude: 73.78,
  startedAt: '2026-10-20T10:00:00Z',
  durationNanos: 15_400_000,
  stages: [
    {
      stageIndex: 0,
      stageType: 'QUAD_TREE_SEARCH',
      windowSize: 1,
      minLatitude: 18.64,
      minLongitude: 73.77,
      maxLatitude: 18.66,
      maxLongitude: 73.79,
      visitedNodeCount: 4,
      prunedNodeCount: 1,
      candidatesDiscovered: 6,
    },
  ],
  candidates: [
    {
      driverId: 'drv-winner',
      latitude: 18.652,
      longitude: 73.782,
      distanceKm: 0.8,
      etaCost: 0.4,
      densityCost: 0.2,
      opportunityCost: 0.1,
      totalCost: 0.7,
      rank: 0,
      outcome: 'MATCHED',
      rejectionReason: null,
    },
    {
      driverId: 'drv-loser',
      latitude: 18.659,
      longitude: 73.789,
      distanceKm: 2.1,
      etaCost: 1.2,
      densityCost: 0.5,
      opportunityCost: 0.3,
      totalCost: 2.0,
      rank: 1,
      outcome: 'OUTCOMPETED',
      rejectionReason: 'Suboptimal total cost',
    },
  ],
  winnerDriverId: 'drv-winner',
  matched: true,
  totalFleetSize: 15,
  decisionSummary: 'Matched drv-winner with score 0.700',
};

const MOCK_ROUTE: RouteSummary = {
  distanceMeters: 4500,
  distanceKm: 4.5,
  durationSeconds: 600,
  durationMinutes: 10,
  coordinates: [
    [73.78, 18.65],
    [73.79, 18.66],
  ],
  fares: [],
  isFallback: false,
  attribution: 'OSRM OpenStreetMap contributors',
};

describe('AlgorithmHUD component', () => {
  it('renders null when neither trace nor active route exists', () => {
    const { container } = render(
      <AlgorithmHUD
        trace={null}
        route={null}
        approachRoute={null}
        isAlgorithmMode={true}
        onToggleAlgorithmMode={() => {}}
        phase="idle"
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders HUD title, stages, shortest-path stats, and candidate rankings', () => {
    render(
      <AlgorithmHUD
        trace={MOCK_TRACE}
        route={MOCK_ROUTE}
        approachRoute={null}
        isAlgorithmMode={true}
        onToggleAlgorithmMode={() => {}}
        phase="idle"
      />,
    );

    expect(screen.getByText('ALGORITHM VISUALIZER')).not.toBeNull();
    expect(screen.getByText(/Spatial QuadTree Partition/i)).not.toBeNull();
    expect(screen.getByText(/Shortest Path/i)).not.toBeNull();
    expect(screen.getByText('~4.5 km')).not.toBeNull();
    expect(screen.getByText('~10 min')).not.toBeNull();
    expect(screen.getByText('ID: drv-winner')).not.toBeNull();
    expect(screen.getByText('ID: drv-loser')).not.toBeNull();
  });

  it('handles toggle and minimize actions', () => {
    const onToggle = vi.fn();
    render(
      <AlgorithmHUD
        trace={MOCK_TRACE}
        route={MOCK_ROUTE}
        approachRoute={null}
        isAlgorithmMode={true}
        onToggleAlgorithmMode={onToggle}
        phase="idle"
      />,
    );

    const toggleBtn = screen.getByText('MODE: ON');
    fireEvent.click(toggleBtn);
    expect(onToggle).toHaveBeenCalledTimes(1);

    const minimizeBtn = screen.getByTitle('Minimize');
    fireEvent.click(minimizeBtn);
    expect(screen.getByTitle('Expand')).not.toBeNull();
  });

  it('calls onReplayPathSearch when replay button is clicked', () => {
    const onReplay = vi.fn();
    render(
      <AlgorithmHUD
        trace={MOCK_TRACE}
        route={MOCK_ROUTE}
        approachRoute={null}
        isAlgorithmMode={true}
        onToggleAlgorithmMode={() => {}}
        onReplayPathSearch={onReplay}
        phase="idle"
      />,
    );

    const replayBtn = screen.getByText(/Replay Pulse/i);
    fireEvent.click(replayBtn);
    expect(onReplay).toHaveBeenCalledTimes(1);
  });
});
