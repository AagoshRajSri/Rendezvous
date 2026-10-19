
package com.aagosh.dispatch.spatial;

import com.aagosh.dispatch.domain.GeoPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

public final class QuadTree {

    private static final int CAPACITY = 8;
    private static final int MAX_DEPTH = 24;
    private static final double EARTH_RADIUS_KM = 6371.0088;
    private static final double PRUNING_TOLERANCE_KM = 1e-9;

    // Pairs a point with its already-computed distance to the current query.
    // Stored in the max-heap so the comparator never re-invokes distanceToKm.
    private record Candidate(GeoPoint point, double distKm)
            implements Comparable<Candidate> {

        @Override
        public int compareTo(Candidate other) {
            // Natural order is nearest-first; the heap uses reversed order
            // (max-heap so peek() returns the farthest / worst candidate).
            int c = Double.compare(this.distKm, other.distKm);
            if (c != 0) return c;
            // Stable tie-break on coordinates preserves deterministic order
            // when distances are equal (required by tests such as
            // returnsNearestPointsInDistanceOrder).
            c = Double.compare(this.point.latitude(), other.point.latitude());
            if (c != 0) return c;
            return Double.compare(this.point.longitude(),
                    other.point.longitude());
        }
    }

    // Comparator that makes PriorityQueue a max-heap by distKm.
    private static final Comparator<Candidate> WORST_FIRST =
            Comparator.reverseOrder();

    private final int depth;
    private final BoundingBox boundary;
    private final double midLatitude;
    private final double midLongitude;
    private final List<GeoPoint> points = new ArrayList<>();

    private QuadTree northWest;
    private QuadTree northEast;
    private QuadTree southWest;
    private QuadTree southEast;

    public QuadTree(BoundingBox boundary) {
        this(boundary, 0);
    }

    private QuadTree(BoundingBox boundary, int depth) {
        this.boundary = Objects.requireNonNull(boundary, "boundary");
        this.depth = depth;
        this.midLatitude =
                (boundary.minLatitude() + boundary.maxLatitude()) / 2.0;
        this.midLongitude =
                (boundary.minLongitude() + boundary.maxLongitude()) / 2.0;
    }

    private double minimumPossibleDistanceKm(GeoPoint query) {
        double queryLat = Math.toRadians(query.latitude());
        double queryLon = Math.toRadians(query.longitude());

        double minLat = Math.toRadians(boundary.minLatitude());
        double maxLat = Math.toRadians(boundary.maxLatitude());
        double minLon = Math.toRadians(boundary.minLongitude());
        double maxLon = Math.toRadians(boundary.maxLongitude());

        double deltaLon = minimumLongitudeDifference(
                queryLon, minLon, maxLon
        );

        double a = Math.sin(queryLat);
        double b = Math.cos(queryLat) * Math.cos(deltaLon);

        double bestDot = Math.max(
                dotProduct(queryLat, minLat, deltaLon),
                dotProduct(queryLat, maxLat, deltaLon)
        );

        double optimalLat = Math.atan2(a, b);

        if (optimalLat >= minLat && optimalLat <= maxLat) {
            bestDot = Math.max(
                    bestDot,
                    a * Math.sin(optimalLat)
                            + b * Math.cos(optimalLat)
            );
        }

        double clampedDot = Math.max(-1.0, Math.min(1.0, bestDot));
        double centralAngle = Math.acos(clampedDot);

        return EARTH_RADIUS_KM * centralAngle;
    }

    private double dotProduct(
            double queryLat,
            double candidateLat,
            double deltaLon
    ) {
        return Math.sin(queryLat) * Math.sin(candidateLat)
                + Math.cos(queryLat) * Math.cos(candidateLat)
                * Math.cos(deltaLon);
    }

    private double minimumLongitudeDifference(
            double queryLon,
            double minLon,
            double maxLon
    ) {
        if (queryLon >= minLon && queryLon <= maxLon) {
            return 0.0;
        }

        double toMin = Math.abs(normalizeRadians(queryLon - minLon));
        double toMax = Math.abs(normalizeRadians(queryLon - maxLon));

        return Math.min(toMin, toMax);
    }

    private double normalizeRadians(double angle) {
        double fullTurn = 2.0 * Math.PI;

        return ((angle + Math.PI) % fullTurn + fullTurn) % fullTurn
                - Math.PI;
    }

    public boolean insert(GeoPoint point) {
        Objects.requireNonNull(point, "point");

        if (!boundary.contains(point)) {
            return false;
        }

        if (northWest == null
                && (points.size() < CAPACITY || depth >= MAX_DEPTH)) {
            points.add(point);
            return true;
        }

        if (northWest == null) {
            subdivide();
        }

        return childFor(point).insert(point);
    }

    private void subdivide() {
        northWest = new QuadTree(new BoundingBox(
                midLatitude,
                boundary.minLongitude(),
                boundary.maxLatitude(),
                midLongitude
        ), depth + 1);

        northEast = new QuadTree(new BoundingBox(
                midLatitude,
                midLongitude,
                boundary.maxLatitude(),
                boundary.maxLongitude()
        ), depth + 1);

        southWest = new QuadTree(new BoundingBox(
                boundary.minLatitude(),
                boundary.minLongitude(),
                midLatitude,
                midLongitude
        ), depth + 1);

        southEast = new QuadTree(new BoundingBox(
                boundary.minLatitude(),
                midLongitude,
                midLatitude,
                boundary.maxLongitude()
        ), depth + 1);

        List<GeoPoint> existingPoints = new ArrayList<>(points);
        points.clear();

        for (GeoPoint existingPoint : existingPoints) {
            childFor(existingPoint).insert(existingPoint);
        }
    }

    private QuadTree childFor(GeoPoint point) {
        boolean north = point.latitude() >= midLatitude;
        boolean east = point.longitude() >= midLongitude;

        if (north && !east) {
            return northWest;
        }

        if (north) {
            return northEast;
        }

        if (!east) {
            return southWest;
        }

        return southEast;
    }

    public List<GeoPoint> kNearest(GeoPoint query, int k) {
        return kNearest(query, k, null);
    }

    public List<GeoPoint> kNearest(GeoPoint query, int k, QuadTreeObserver observer) {
        Objects.requireNonNull(query, "query");

        if (k <= 0) {
            throw new IllegalArgumentException(
                    "k must be greater than zero"
            );
        }

        // Max-heap: peek() always returns the farthest (worst) candidate.
        PriorityQueue<Candidate> nearest = new PriorityQueue<>(WORST_FIRST);

        // worstKm tracks nearest.peek().distKm without re-invoking
        // distanceToKm. Initialized to +Inf so every point is accepted
        // until the heap reaches capacity k.
        double[] worstKm = {Double.POSITIVE_INFINITY};

        collectNearest(query, k, nearest, worstKm, observer);

        // Extract in nearest-first order using the cached distances.
        // The heap holds at most k elements; sort is O(k log k) on
        // already-computed values — no new distanceToKm calls.
        List<Candidate> result = new ArrayList<>(nearest);
        result.sort(Comparator.naturalOrder()); // nearest-first via Comparable
        List<GeoPoint> points = new ArrayList<>(result.size());
        for (Candidate c : result) {
            points.add(c.point());
        }
        return points;
    }

    private void collectNearest(
            GeoPoint query,
            int k,
            PriorityQueue<Candidate> nearest,
            double[] worstKm,
            QuadTreeObserver observer
    ) {
        if (nearest.size() == k) {
            double lowerBound = minimumPossibleDistanceKm(query);

            // worstKm[0] is always nearest.peek().distKm — no re-computation.
            if (lowerBound > worstKm[0] + PRUNING_TOLERANCE_KM) {
                if (observer != null) {
                    try {
                        observer.onNodePruned(this.boundary);
                    } catch (Exception ignored) {
                        // Suppress observer failures to protect spatial search
                    }
                }
                return;
            }
        }

        if (observer != null) {
            try {
                observer.onNodeVisited(this.boundary, northWest == null);
            } catch (Exception ignored) {
                // Suppress observer failures to protect spatial search
            }
        }

        if (northWest == null) {
            for (GeoPoint point : this.points) {
                if (observer != null) {
                    try {
                        observer.onDistanceEvaluated();
                    } catch (Exception ignored) {
                        // Suppress observer failures to protect spatial search
                    }
                }
                double dist = query.distanceToKm(point); // computed exactly once

                if (nearest.size() < k) {
                    nearest.offer(new Candidate(point, dist));
                    // Update worstKm after every insertion while heap fills.
                    if (nearest.size() == k) {
                        worstKm[0] = nearest.peek().distKm();
                    }
                } else if (dist < worstKm[0]) {
                    // Replace the current worst with this closer candidate.
                    nearest.poll();
                    nearest.offer(new Candidate(point, dist));
                    // Peek reflects the new worst after re-heapify.
                    worstKm[0] = nearest.peek().distKm();
                } else if (dist == worstKm[0]) {
                    Candidate candidate = new Candidate(point, dist);
                    if (candidate.compareTo(nearest.peek()) < 0) {
                        nearest.poll();
                        nearest.offer(candidate);
                        worstKm[0] = nearest.peek().distKm();
                    }
                }
            }

            return;
        }

        // Distance-ordered traversal: visit the closest child quadrant first so
        // the heap fills with true nearest neighbours quickly, tightening worstKm[0]
        // and allowing later siblings to be pruned at this level.
        //
        // Sorting network (5 compare-and-swap, 0 allocations) on (distance, child)
        // pairs stored in stack-local doubles and QuadTree references.
        double d0 = northWest.minimumPossibleDistanceKm(query);
        double d1 = northEast.minimumPossibleDistanceKm(query);
        double d2 = southWest.minimumPossibleDistanceKm(query);
        double d3 = southEast.minimumPossibleDistanceKm(query);

        QuadTree c0 = northWest;
        QuadTree c1 = northEast;
        QuadTree c2 = southWest;
        QuadTree c3 = southEast;

        // Sorting network for 4 elements: (0,1),(2,3),(0,2),(1,3),(1,2)
        double td; QuadTree tc;
        if (d0 > d1) { td = d0; d0 = d1; d1 = td; tc = c0; c0 = c1; c1 = tc; }
        if (d2 > d3) { td = d2; d2 = d3; d3 = td; tc = c2; c2 = c3; c3 = tc; }
        if (d0 > d2) { td = d0; d0 = d2; d2 = td; tc = c0; c0 = c2; c2 = tc; }
        if (d1 > d3) { td = d1; d1 = d3; d3 = td; tc = c1; c1 = c3; c3 = tc; }
        if (d1 > d2) { td = d1; d1 = d2; d2 = td; tc = c1; c1 = c2; c2 = tc; }

        // Visit children in nearest-first order; prune at this level after each
        // descent so siblings benefit from the tightened worstKm[0].
        c0.collectNearest(query, k, nearest, worstKm, observer);
        if (nearest.size() < k || d1 <= worstKm[0] + PRUNING_TOLERANCE_KM) {
            c1.collectNearest(query, k, nearest, worstKm, observer);
        } else if (observer != null) {
            try { observer.onNodePruned(c1.boundary); } catch (Exception ignored) {}
        }
        if (nearest.size() < k || d2 <= worstKm[0] + PRUNING_TOLERANCE_KM) {
            c2.collectNearest(query, k, nearest, worstKm, observer);
        } else if (observer != null) {
            try { observer.onNodePruned(c2.boundary); } catch (Exception ignored) {}
        }
        if (nearest.size() < k || d3 <= worstKm[0] + PRUNING_TOLERANCE_KM) {
            c3.collectNearest(query, k, nearest, worstKm, observer);
        } else if (observer != null) {
            try { observer.onNodePruned(c3.boundary); } catch (Exception ignored) {}
        }
    }

    /**
     * Returns the number of points in this tree whose geodesic distance to
     * {@code center} is less than or equal to {@code radiusKm} (inclusive
     * boundary).
     *
     * <p>Uses the same branch-and-bound pruning as {@link #kNearest} to skip
     * subtrees whose minimum possible distance to {@code center} exceeds
     * {@code radiusKm}. The geodesic distance is computed with the same
     * Haversine formula used by {@link com.aagosh.dispatch.domain.GeoPoint#distanceToKm}.
     *
     * @param center   the query location; must not be {@code null}
     * @param radiusKm the search radius in kilometres; must be finite and ≥ 0
     * @return the count of points within the radius (≥ 0)
     * @throws IllegalArgumentException if {@code radiusKm} is negative or
     *                                  non-finite
     */
    public int countWithinRadius(GeoPoint center, double radiusKm) {
        Objects.requireNonNull(center, "center");

        if (!Double.isFinite(radiusKm) || radiusKm < 0) {
            throw new IllegalArgumentException(
                    "radiusKm must be finite and non-negative, got: " + radiusKm
            );
        }

        int[] count = {0};
        collectWithinRadius(center, radiusKm, count);
        return count[0];
    }

    private void collectWithinRadius(
            GeoPoint center,
            double radiusKm,
            int[] count
    ) {
        // Prune this entire subtree if its closest possible point
        // is already farther than the search radius.
        if (minimumPossibleDistanceKm(center) > radiusKm) {
            return;
        }

        if (northWest == null) {
            // Leaf node — check each stored point directly.
            for (GeoPoint point : this.points) {
                if (center.distanceToKm(point) <= radiusKm) {
                    count[0]++;
                }
            }
            return;
        }

        northWest.collectWithinRadius(center, radiusKm, count);
        northEast.collectWithinRadius(center, radiusKm, count);
        southWest.collectWithinRadius(center, radiusKm, count);
        southEast.collectWithinRadius(center, radiusKm, count);
    }

    public int size() {
        if (northWest == null) {
            return points.size();
        }

        return northWest.size()
                + northEast.size()
                + southWest.size()
                + southEast.size();
    }
}
