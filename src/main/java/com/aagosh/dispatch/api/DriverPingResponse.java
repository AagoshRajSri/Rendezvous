package com.aagosh.dispatch.api;

import com.aagosh.dispatch.domain.MatchStatus;

/**
 * Response payload confirming driver registration or location update.
 */
public record DriverPingResponse(
        String driverId,
        String status,
        double latitude,
        double longitude,
        MatchStatus matchStatus,
        String message
) {}
