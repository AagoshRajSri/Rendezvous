package com.aagosh.dispatch.spatial;

/**
 * Lightweight observer hook for QuadTree spatial traversal telemetry.
 *
 * <p>Observes node visits and branch-and-bound pruning events during spatial searches.
 * Implementations must be observational only and must never modify search state or results.
 */
public interface QuadTreeObserver {

    /**
     * Called when a QuadTree node is visited during spatial search.
     *
     * @param boundary the bounding box of the visited node
     * @param isLeaf   true if the node is a leaf containing points; false if an internal node
     */
    void onNodeVisited(BoundingBox boundary, boolean isLeaf);

    /**
     * Called when a QuadTree node (and its subtree) is pruned by branch-and-bound pruning.
     *
     * @param boundary the bounding box of the pruned subtree
     */
    void onNodePruned(BoundingBox boundary);
}
