/**
 * Phase 11.4F — GeoJSON conversion utilities for dispatch execution trace map overlays.
 *
 * Converts backend SpatialStage bounding boxes to GeoJSON Polygons and TraceCandidate
 * coordinates to GeoJSON Points, ensuring strict [longitude, latitude] WGS-84 coordinate ordering.
 */

import type { FeatureCollection, Feature, Polygon, Point, GeoJsonProperties } from 'geojson';
import type { SpatialStage, TraceCandidate } from '../types/dispatch';

export const EMPTY_TRACE_FEATURE_COLLECTION: FeatureCollection = {
  type: 'FeatureCollection',
  features: [],
};

/**
 * Checks whether a spatial exploration stage contains a complete, valid bounding box.
 */
export function isValidBoundingBox(stage: SpatialStage): boolean {
  if (
    stage.minLatitude == null ||
    stage.minLongitude == null ||
    stage.maxLatitude == null ||
    stage.maxLongitude == null
  ) {
    return false;
  }

  if (
    !Number.isFinite(stage.minLatitude) ||
    !Number.isFinite(stage.minLongitude) ||
    !Number.isFinite(stage.maxLatitude) ||
    !Number.isFinite(stage.maxLongitude)
  ) {
    return false;
  }

  // Minimums must be less than or equal to maximums
  return stage.minLatitude <= stage.maxLatitude && stage.minLongitude <= stage.maxLongitude;
}

/**
 * Converts spatial exploration stages into a GeoJSON FeatureCollection of Polygon bounding boxes.
 * Skips stages without a valid bounding box.
 *
 * Coordinate order is strictly [longitude, latitude], and polygons form a closed ring (5 coordinates).
 */
export function stagesToFeatureCollection(
  stages: SpatialStage[] | null | undefined,
): FeatureCollection<Polygon, GeoJsonProperties> {
  if (!stages || stages.length === 0) {
    return EMPTY_TRACE_FEATURE_COLLECTION as FeatureCollection<Polygon, GeoJsonProperties>;
  }

  const features: Feature<Polygon, GeoJsonProperties>[] = [];

  for (const stage of stages) {
    if (!isValidBoundingBox(stage)) {
      continue;
    }

    const minLon = stage.minLongitude as number;
    const minLat = stage.minLatitude as number;
    const maxLon = stage.maxLongitude as number;
    const maxLat = stage.maxLatitude as number;

    // GeoJSON polygon ring: [west, south] -> [east, south] -> [east, north] -> [west, north] -> [west, south]
    const ring: [number, number][] = [
      [minLon, minLat],
      [maxLon, minLat],
      [maxLon, maxLat],
      [minLon, maxLat],
      [minLon, minLat],
    ];

    features.push({
      type: 'Feature',
      id: `stage-${stage.stageIndex}`,
      geometry: {
        type: 'Polygon',
        coordinates: [ring],
      },
      properties: {
        stageIndex: stage.stageIndex,
        stageType: stage.stageType,
        windowSize: stage.windowSize,
        visitedNodeCount: stage.visitedNodeCount,
        prunedNodeCount: stage.prunedNodeCount,
        candidatesDiscovered: stage.candidatesDiscovered,
      },
    });
  }

  return {
    type: 'FeatureCollection',
    features,
  };
}

/**
 * Converts candidate drivers into a GeoJSON FeatureCollection of Point features.
 * Skips candidates with invalid coordinates.
 *
 * Coordinates are strictly [longitude, latitude].
 */
export function candidatesToFeatureCollection(
  candidates: TraceCandidate[] | null | undefined,
  winnerDriverId?: string | null,
): FeatureCollection<Point, GeoJsonProperties> {
  if (!candidates || candidates.length === 0) {
    return EMPTY_TRACE_FEATURE_COLLECTION as FeatureCollection<Point, GeoJsonProperties>;
  }

  const features: Feature<Point, GeoJsonProperties>[] = [];

  for (const candidate of candidates) {
    if (
      candidate.latitude == null ||
      candidate.longitude == null ||
      !Number.isFinite(candidate.latitude) ||
      !Number.isFinite(candidate.longitude)
    ) {
      continue;
    }

    const isWinner =
      candidate.outcome === 'MATCHED' ||
      (winnerDriverId != null && candidate.driverId === winnerDriverId);

    features.push({
      type: 'Feature',
      id: candidate.driverId,
      geometry: {
        type: 'Point',
        coordinates: [candidate.longitude, candidate.latitude],
      },
      properties: {
        driverId: candidate.driverId,
        rank: candidate.rank,
        distanceKm: candidate.distanceKm,
        outcome: candidate.outcome,
        isWinner,
        totalCost: candidate.totalCost,
        rejectionReason: candidate.rejectionReason,
      },
    });
  }

  return {
    type: 'FeatureCollection',
    features,
  };
}
