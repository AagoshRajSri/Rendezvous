package com.aagosh.dispatch.api;

import com.aagosh.dispatch.persistence.TripEntity;

import java.time.Instant;
import java.util.Objects;

/**
 * Detailed representation of a trip's current operational state and timestamps.
 */
public record TripResponse(
        String requestId,
        String driverId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static TripResponse from(TripEntity entity) {
        Objects.requireNonNull(entity, "entity");
        return new TripResponse(
                entity.getRequestId(),
                entity.getDriverId(),
                entity.getStatus().name(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
