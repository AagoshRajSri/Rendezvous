package com.aagosh.dispatch.spatial;

import com.aagosh.dispatch.domain.GeoPoint;

public record BoundingBox(
        double minLatitude,
        double minLongitude,
        double maxLatitude,
        double maxLongitude
) {
    public BoundingBox {
        if (minLatitude < -90 || maxLatitude > 90
                || minLongitude < -180 || maxLongitude > 180
                || minLatitude > maxLatitude
                || minLongitude > maxLongitude) {
            throw new IllegalArgumentException("Invalid geographic bounds");
        }
    }

    public boolean contains(GeoPoint point) {
        return point.latitude() >= minLatitude
                && point.latitude() <= maxLatitude
                && point.longitude() >= minLongitude
                && point.longitude() <= maxLongitude;
    }
}
