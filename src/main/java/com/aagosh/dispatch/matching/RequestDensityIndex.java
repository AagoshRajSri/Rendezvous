package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.spatial.BoundingBox;
import com.aagosh.dispatch.spatial.QuadTree;

import java.util.List;
import java.util.Objects;

/**
 * A snapshot spatial index of ride-request pickup locations, used to estimate
 * competition density around a candidate driver.
 *
 * <h2>Lifecycle and data consistency</h2>
 * <p>The index is built once from the request list provided at construction
 * time and is subsequently immutable. If the set of unmatched requests changes
 * (for example, because some requests were already matched before this index
 * was built), callers must rebuild the index from the updated list.
 * {@code RequestDensityIndex} does not observe or react to claim events; it is
 * the caller's responsibility to supply only the requests that should be
 * counted as unmatched.
 *
 * <h2>Multiplicity</h2>
 * <p>Multiple requests that share the same pickup {@link GeoPoint} are each
 * inserted individually, so the count reflects request multiplicity.
 *
 * <h2>Boundary semantics</h2>
 * <p>The radius boundary is <em>inclusive</em>: a request whose pickup
 * location is exactly {@code radiusKm} kilometres from the query point
 * is counted.
 */
public final class RequestDensityIndex {

    private static final BoundingBox WORLD_BOUNDS =
            new BoundingBox(-90, -180, 90, 180);

    private final QuadTree tree;

    /**
     * Builds the index from the supplied list of unmatched requests.
     *
     * @param unmatchedRequests the snapshot of unmatched requests; may be
     *                          empty but must not be {@code null} and must
     *                          not contain {@code null} elements
     */
    public RequestDensityIndex(List<RideRequest> unmatchedRequests) {
        Objects.requireNonNull(unmatchedRequests, "unmatchedRequests");

        this.tree = new QuadTree(WORLD_BOUNDS);

        for (RideRequest request : unmatchedRequests) {
            Objects.requireNonNull(request, "request element");
            tree.insert(request.pickupLocation());
        }
    }

    /**
     * Returns the number of unmatched-request pickup locations within
     * {@code radiusKm} of {@code driverLocation} (inclusive boundary).
     *
     * @param driverLocation the candidate driver's location; must not be
     *                       {@code null}
     * @param radiusKm       the search radius in kilometres; must be finite
     *                       and ≥ 0
     * @return the count of unmatched requests within the radius (≥ 0)
     */
    public int countNearby(GeoPoint driverLocation, double radiusKm) {
        Objects.requireNonNull(driverLocation, "driverLocation");
        return tree.countWithinRadius(driverLocation, radiusKm);
    }
}
