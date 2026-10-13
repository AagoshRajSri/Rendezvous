package com.aagosh.dispatch.api;

/**
 * Response payload detailing dispatch match outcome.
 */
public record RideResponse(
        String requestId,
        boolean matched,
        String driverId,
        Double driverLatitude,
        Double driverLongitude,
        String driverStatus,
        Double cost,
        String message
) {}
