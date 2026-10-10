package com.aagosh.dispatch.spatial;

import com.aagosh.dispatch.domain.GeoPoint;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class QuadTree {

    private static final int CAPACITY = 8;
    private static final int MAX_DEPTH = 24;
    private final int depth;

    private final BoundingBox boundary;
    private final List<GeoPoint> points = new ArrayList<>();

    private QuadTree northWest;
    private QuadTree northEast;
    private QuadTree southWest;
    private QuadTree southEast;

    public QuadTree(BoundingBox boundary) {
        this(boundary, 0);
    }


    private double minimumPossibleDistanceKm(GeoPoint query) {
        double nearestLatitude = Math.max(
                boundary.minLatitude(),
                Math.min(query.latitude(), boundary.maxLatitude())
        );

        return query.distanceToKm(
                new GeoPoint(nearestLatitude, query.longitude())
        );
    }


    private QuadTree(BoundingBox boundary, int depth) {
        this.boundary = Objects.requireNonNull(boundary, "boundary");
        this.depth = depth;
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
        double midLatitude =
                (boundary.minLatitude() + boundary.maxLatitude()) / 2.0;

        double midLongitude =
                (boundary.minLongitude() + boundary.maxLongitude()) / 2.0;

        northWest = new QuadTree(new BoundingBox(
                midLatitude, boundary.minLongitude(),
                boundary.maxLatitude(), midLongitude), depth + 1);

        northEast = new QuadTree(new BoundingBox(
                midLatitude, midLongitude,
                boundary.maxLatitude(), boundary.maxLongitude()), depth + 1);

        southWest = new QuadTree(new BoundingBox(
                boundary.minLatitude(), boundary.minLongitude(),
                midLatitude, midLongitude), depth + 1);

        southEast = new QuadTree(new BoundingBox(
                boundary.minLatitude(), midLongitude,
                midLatitude, boundary.maxLongitude()), depth + 1);

        List<GeoPoint> existingPoints = new ArrayList<>(points);
        points.clear();

        for (GeoPoint existingPoint : existingPoints) {
            childFor(existingPoint).insert(existingPoint);
        }
    }

    private QuadTree childFor(GeoPoint point) {
        double midLatitude =
                (boundary.minLatitude() + boundary.maxLatitude()) / 2.0;

        double midLongitude =
                (boundary.minLongitude() + boundary.maxLongitude()) / 2.0;

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
        Objects.requireNonNull(query, "query");

        if (k <= 0) {
            throw new IllegalArgumentException("k must be greater than zero");
        }

        PriorityQueue<GeoPoint> nearest = new PriorityQueue<>(
                Comparator.comparingDouble(query::distanceToKm).reversed()
        );

        collectNearest(query, k, nearest);

        List<GeoPoint> result = new ArrayList<>(nearest);
        result.sort(Comparator.comparingDouble(query::distanceToKm));
        return result;
    }


    private void collectNearest(
            GeoPoint query,
            int k,
            PriorityQueue<GeoPoint> nearest
    ) {
        if (nearest.size() == k
                && minimumPossibleDistanceKm(query)
                > query.distanceToKm(nearest.peek())) {
            return;
        }

        if (northWest == null) {
            for (GeoPoint point : points) {
                if (nearest.size() < k) {
                    nearest.offer(point);
                } else if (query.distanceToKm(point)
                        < query.distanceToKm(nearest.peek())) {
                    nearest.poll();
                    nearest.offer(point);
                }
            }
            return;
        }

        northWest.collectNearest(query, k, nearest);
        northEast.collectNearest(query, k, nearest);
        southWest.collectNearest(query, k, nearest);
        southEast.collectNearest(query, k, nearest);
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
