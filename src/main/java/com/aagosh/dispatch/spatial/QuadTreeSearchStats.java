package com.aagosh.dispatch.spatial;

/**
 * Lightweight, mutable accumulator implementing {@link QuadTreeObserver}.
 *
 * <p>Tracks counts of visited and pruned nodes during a QuadTree spatial search.
 */
public final class QuadTreeSearchStats implements QuadTreeObserver {

    private int visitedNodes = 0;
    private int prunedNodes = 0;
    private int distanceEvaluations = 0;

    @Override
    public void onNodeVisited(BoundingBox boundary, boolean isLeaf) {
        visitedNodes++;
    }

    @Override
    public void onNodePruned(BoundingBox boundary) {
        prunedNodes++;
    }

    @Override
    public void onDistanceEvaluated() {
        distanceEvaluations++;
    }

    public int visitedNodes() {
        return visitedNodes;
    }

    public int prunedNodes() {
        return prunedNodes;
    }

    public int distanceEvaluations() {
        return distanceEvaluations;
    }

    /**
     * Calculates the distance-calculation pruning percentage against a brute-force
     * comparison baseline of {@code totalIndexedPoints} distance evaluations.
     *
     * <p>Pruning percentage = 100 * (1 - measured distance evaluations / totalIndexedPoints).
     *
     * @param totalIndexedPoints total number of valid points indexed in the QuadTree
     * @return pruning percentage between 0.0 and 100.0 (returns 0.0 if totalIndexedPoints <= 0)
     */
    public double pruningPercentage(int totalIndexedPoints) {
        if (totalIndexedPoints <= 0) {
            return 0.0;
        }
        return 100.0 * (1.0 - ((double) distanceEvaluations / totalIndexedPoints));
    }

    public void reset() {
        visitedNodes = 0;
        prunedNodes = 0;
        distanceEvaluations = 0;
    }
}
