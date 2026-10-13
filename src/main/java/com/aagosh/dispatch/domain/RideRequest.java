package com.aagosh.dispatch.domain;

public record RideRequest(
        String id,
        GeoPoint pickupLocation
) {
    public RideRequest {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Request id must not be blank");
        }
        java.util.Objects.requireNonNull(pickupLocation);
    }
}