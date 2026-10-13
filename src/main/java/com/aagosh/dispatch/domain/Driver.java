package com.aagosh.dispatch.domain;

public record Driver(
        String id,
        GeoPoint location,
        DriverState state
) {
    public Driver {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "com.aagosh.dispatch.domain.Driver id must not be blank");
        }
        java.util.Objects.requireNonNull(location);
        java.util.Objects.requireNonNull(state);
    }
}