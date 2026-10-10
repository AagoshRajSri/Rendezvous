package com.aagosh.dispatch.domain;

public record GeoPoint(double latitude, double longitude) {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    public GeoPoint {
        if (!Double.isFinite(latitude)
                || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException(
                    "Latitude must be between -90 and 90");
        }

        if (!Double.isFinite(longitude)
                || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException(
                    "Longitude must be between -180 and 180");
        }
    }

    public double distanceToKm(GeoPoint other) {
        double lat1 = Math.toRadians(this.latitude);
        double lat2 = Math.toRadians(other.latitude);
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(
                other.longitude - this.longitude);

        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.pow(Math.sin(deltaLon / 2), 2);

        double clampedA = Math.max(0.0, Math.min(1.0, a));
        double centralAngle = 2 * Math.atan2(
                Math.sqrt(clampedA),
                Math.sqrt(1.0 - clampedA));

        return EARTH_RADIUS_KM * centralAngle;
    }
}
