/**
 * Phase 11.4F — Unit tests for traceGeoJson utilities.
 */

import { describe, it, expect } from 'vitest';
import {
  isValidBoundingBox,
  stagesToFeatureCollection,
  candidatesToFeatureCollection,
  EMPTY_TRACE_FEATURE_COLLECTION,
} from './traceGeoJson';
import type { SpatialStage, TraceCandidate } from '../types/dispatch';

describe('isValidBoundingBox', () => {
  it('returns true for a complete and valid bounding box', () => {
    const stage: SpatialStage = {
      stageIndex: 0,
      stageType: 'QUAD_TREE_SEARCH',
      windowSize: 16,
      minLatitude: 12.9,
      minLongitude: 77.5,
      maxLatitude: 13.0,
      maxLongitude: 77.6,
      visitedNodeCount: 5,
      prunedNodeCount: 2,
      candidatesDiscovered: 4,
    };
    expect(isValidBoundingBox(stage)).toBe(true);
  });

  it('returns false when any bounding-box coordinate is null or undefined', () => {
    const stage1: SpatialStage = {
      stageIndex: 0,
      stageType: 'QUAD_TREE_SEARCH',
      windowSize: 16,
      minLatitude: null,
      minLongitude: 77.5,
      maxLatitude: 13.0,
      maxLongitude: 77.6,
      visitedNodeCount: 5,
      prunedNodeCount: 2,
      candidatesDiscovered: 4,
    };
    expect(isValidBoundingBox(stage1)).toBe(false);

    const stage2: SpatialStage = {
      ...stage1,
      minLatitude: 12.9,
      maxLongitude: null,
    };
    expect(isValidBoundingBox(stage2)).toBe(false);
  });

  it('returns false for non-finite coordinates', () => {
    const stage: SpatialStage = {
      stageIndex: 0,
      stageType: 'QUAD_TREE_SEARCH',
      windowSize: 16,
      minLatitude: NaN,
      minLongitude: 77.5,
      maxLatitude: 13.0,
      maxLongitude: 77.6,
      visitedNodeCount: 5,
      prunedNodeCount: 2,
      candidatesDiscovered: 4,
    };
    expect(isValidBoundingBox(stage)).toBe(false);
  });

  it('returns false when coordinates are inverted (min > max)', () => {
    const stageLatInverted: SpatialStage = {
      stageIndex: 0,
      stageType: 'QUAD_TREE_SEARCH',
      windowSize: 16,
      minLatitude: 13.5,
      minLongitude: 77.5,
      maxLatitude: 13.0,
      maxLongitude: 77.6,
      visitedNodeCount: 5,
      prunedNodeCount: 2,
      candidatesDiscovered: 4,
    };
    expect(isValidBoundingBox(stageLatInverted)).toBe(false);

    const stageLonInverted: SpatialStage = {
      ...stageLatInverted,
      minLatitude: 12.9,
      minLongitude: 78.5,
      maxLongitude: 77.6,
    };
    expect(isValidBoundingBox(stageLonInverted)).toBe(false);
  });
});

describe('stagesToFeatureCollection', () => {
  it('returns empty feature collection for null or empty stages', () => {
    expect(stagesToFeatureCollection(null)).toEqual(EMPTY_TRACE_FEATURE_COLLECTION);
    expect(stagesToFeatureCollection(undefined)).toEqual(EMPTY_TRACE_FEATURE_COLLECTION);
    expect(stagesToFeatureCollection([])).toEqual(EMPTY_TRACE_FEATURE_COLLECTION);
  });

  it('converts valid stages to closed Polygon features with strict [longitude, latitude] coordinates', () => {
    const stages: SpatialStage[] = [
      {
        stageIndex: 0,
        stageType: 'INITIAL_WINDOW',
        windowSize: 16,
        minLatitude: 12.90,
        minLongitude: 77.50,
        maxLatitude: 13.00,
        maxLongitude: 77.60,
        visitedNodeCount: 8,
        prunedNodeCount: 2,
        candidatesDiscovered: 3,
      },
    ];

    const fc = stagesToFeatureCollection(stages);
    expect(fc.features).toHaveLength(1);

    const feature = fc.features[0];
    expect(feature.id).toBe('stage-0');
    expect(feature.geometry.type).toBe('Polygon');

    const ring = feature.geometry.coordinates[0];
    // Must be a closed 5-point ring: SW -> SE -> NE -> NW -> SW
    expect(ring).toHaveLength(5);
    expect(ring[0]).toEqual([77.50, 12.90]); // [minLon, minLat]
    expect(ring[1]).toEqual([77.60, 12.90]); // [maxLon, minLat]
    expect(ring[2]).toEqual([77.60, 13.00]); // [maxLon, maxLat]
    expect(ring[3]).toEqual([77.50, 13.00]); // [minLon, maxLat]
    expect(ring[4]).toEqual([77.50, 12.90]); // closed ring (equals ring[0])

    expect(feature.properties?.stageIndex).toBe(0);
    expect(feature.properties?.stageType).toBe('INITIAL_WINDOW');
    expect(feature.properties?.windowSize).toBe(16);
    expect(feature.properties?.visitedNodeCount).toBe(8);
  });

  it('safely skips stages with missing or invalid bounding boxes', () => {
    const stages: SpatialStage[] = [
      {
        stageIndex: 0,
        stageType: 'NO_BBOX',
        windowSize: 16,
        minLatitude: null,
        minLongitude: null,
        maxLatitude: null,
        maxLongitude: null,
        visitedNodeCount: 2,
        prunedNodeCount: 1,
        candidatesDiscovered: 1,
      },
      {
        stageIndex: 1,
        stageType: 'VALID_STAGE',
        windowSize: 32,
        minLatitude: 12.95,
        minLongitude: 77.55,
        maxLatitude: 13.05,
        maxLongitude: 77.65,
        visitedNodeCount: 4,
        prunedNodeCount: 2,
        candidatesDiscovered: 2,
      },
    ];

    const fc = stagesToFeatureCollection(stages);
    expect(fc.features).toHaveLength(1);
    expect(fc.features[0].id).toBe('stage-1');
  });
});

describe('candidatesToFeatureCollection', () => {
  it('returns empty feature collection for null or empty candidates', () => {
    expect(candidatesToFeatureCollection(null)).toEqual(EMPTY_TRACE_FEATURE_COLLECTION);
    expect(candidatesToFeatureCollection(undefined)).toEqual(EMPTY_TRACE_FEATURE_COLLECTION);
    expect(candidatesToFeatureCollection([])).toEqual(EMPTY_TRACE_FEATURE_COLLECTION);
  });

  it('converts candidates to Point features with [longitude, latitude] coordinates and outcome properties', () => {
    const candidates: TraceCandidate[] = [
      {
        driverId: 'D-WINNER',
        latitude: 12.972,
        longitude: 77.595,
        distanceKm: 0.45,
        etaCost: 1.2,
        densityCost: 0.3,
        opportunityCost: 0.1,
        totalCost: 1.6,
        rank: 0,
        outcome: 'MATCHED',
        rejectionReason: null,
      },
      {
        driverId: 'D-LOSER',
        latitude: 12.980,
        longitude: 77.610,
        distanceKm: 1.8,
        etaCost: null,
        densityCost: null,
        opportunityCost: null,
        totalCost: null,
        rank: 1,
        outcome: 'OUTCOMPETED',
        rejectionReason: 'Higher total cost',
      },
    ];

    const fc = candidatesToFeatureCollection(candidates, 'D-WINNER');
    expect(fc.features).toHaveLength(2);

    const f1 = fc.features[0];
    expect(f1.id).toBe('D-WINNER');
    expect(f1.geometry.type).toBe('Point');
    expect(f1.geometry.coordinates).toEqual([77.595, 12.972]); // [lon, lat]
    expect(f1.properties?.isWinner).toBe(true);
    expect(f1.properties?.outcome).toBe('MATCHED');
    expect(f1.properties?.rank).toBe(0);

    const f2 = fc.features[1];
    expect(f2.id).toBe('D-LOSER');
    expect(f2.geometry.coordinates).toEqual([77.610, 12.980]); // [lon, lat]
    expect(f2.properties?.isWinner).toBe(false);
    expect(f2.properties?.outcome).toBe('OUTCOMPETED');
  });

  it('safely skips candidates with missing or non-finite coordinates', () => {
    const candidates = [
      {
        driverId: 'D-INVALID',
        latitude: NaN,
        longitude: 77.595,
        distanceKm: 0.5,
        etaCost: null,
        densityCost: null,
        opportunityCost: null,
        totalCost: null,
        rank: 0,
        outcome: 'UNAVAILABLE' as const,
        rejectionReason: null,
      },
      {
        driverId: 'D-VALID',
        latitude: 12.972,
        longitude: 77.595,
        distanceKm: 0.5,
        etaCost: null,
        densityCost: null,
        opportunityCost: null,
        totalCost: null,
        rank: 1,
        outcome: 'OUTCOMPETED' as const,
        rejectionReason: null,
      },
    ];

    const fc = candidatesToFeatureCollection(candidates);
    expect(fc.features).toHaveLength(1);
    expect(fc.features[0].id).toBe('D-VALID');
  });
});
