package com.aagosh.dispatch.spatial;

/**
 * Lightweight, mutable accumulator implementing {@link QuadTreeObserver}.
 *
 * <p>Tracks counts of visited and pruned nodes during a QuadTree spatial search.
 */
public final class QuadTreeSearchStats implements QuadTreeObserver {

    private int visitedNodes = 0;
    private int prunedNodes = 0;

    @Override
    public void onNodeVisited(BoundingBox boundary, boolean isLeaf) {
        visitedNodes++;
    }

    @Override
    public void onNodePruned(BoundingBox boundary) {
        prunedNodes++;
    }

    public int visitedNodes() {
        return visitedNodes;
    }

    public int prunedNodes() {
        return prunedNodes;
    }

    public void reset() {
        visitedNodes = 0;
        prunedNodes = 0;
    }
}
