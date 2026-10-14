package com.aagosh.dispatch.domain;

import java.util.Objects;

/**
 * Immutable snapshot of a driver at a point in time.
 *
 * <h2>updatedAt semantics</h2>
 * <p>{@code updatedAt} records when the driver's location was last accepted, expressed as
 * milliseconds since the Unix epoch (UTC). It is used by {@link DriverRegistry} to enforce
 * monotone ordering: an incoming location update is rejected if its timestamp is not strictly
 * greater than the stored value.
 *
 * <p>For the initial registration snapshot the caller supplies the best available timestamp
 * (client-provided {@code capturedAt}, or Kafka record timestamp as a fallback).
 *
 * <h2>Backward compatibility</h2>
 * <p>A three-argument convenience constructor {@code Driver(id, location, state)} is provided
 * for existing tests and call sites that have no ordering information. It sets {@code updatedAt}
 * to {@code 0L}, which means any real timestamp will supersede it on the first update.
 */
public record Driver(
        String id,
        GeoPoint location,
        DriverState state,
        long updatedAt
) {
    public Driver {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "com.aagosh.dispatch.domain.Driver id must not be blank");
        }
        Objects.requireNonNull(location);
        Objects.requireNonNull(state);
        // updatedAt == 0 is allowed for legacy/test construction with no timestamp
    }

    /**
     * Convenience constructor for call sites that have no location timestamp.
     * Sets {@code updatedAt} to {@code 0L}.
     */
    public Driver(String id, GeoPoint location, DriverState state) {
        this(id, location, state, 0L);
    }
}