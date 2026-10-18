package com.aagosh.dispatch.api;

import java.time.Instant;

/**
 * Read-only DTO representing a registered, fresh driver eligible for dispatch.
 * Exposes only map-required fields without sensitive PII or credentials.
 */
public record EligibleDriverResponse(
        String driverId,
        double latitude,
        double longitude,
        String status,
        Instant updatedAt
) {
    public EligibleDriverResponse {
        if (driverId == null || driverId.isBlank()) {
            throw new IllegalArgumentException("driverId must not be null or blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be null or blank");
        }
    }
}
