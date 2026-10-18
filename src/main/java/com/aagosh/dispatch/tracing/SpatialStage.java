package com.aagosh.dispatch.tracing;

import java.util.Objects;

/**
 * Immutable value object representing an exploration or refinement stage in the spatial dispatch search.
 *
 * <p>For QuadTree / nearest-window search, records the current nearest-window size, bounding box coordinates
 * of the explored region (if applicable), and counts of nodes visited vs. pruned via branch-and-bound.
 */
public record SpatialStage(
        int stageIndex,
        String stageType,
        int windowSize,
        Double minLatitude,
        Double minLongitude,
        Double maxLatitude,
        Double maxLongitude,
        int visitedNodeCount,
        int prunedNodeCount,
        int candidatesDiscovered
) {
    public SpatialStage {
        Objects.requireNonNull(stageType, "stageType must not be null");

        if (stageIndex < 0) {
            throw new IllegalArgumentException("stageIndex must be non-negative, got: " + stageIndex);
        }
        if (visitedNodeCount < 0) {
            throw new IllegalArgumentException("visitedNodeCount must be non-negative, got: " + visitedNodeCount);
        }
        if (prunedNodeCount < 0) {
            throw new IllegalArgumentException("prunedNodeCount must be non-negative, got: " + prunedNodeCount);
        }
        if (candidatesDiscovered < 0) {
            throw new IllegalArgumentException("candidatesDiscovered must be non-negative, got: " + candidatesDiscovered);
        }
    }
}
