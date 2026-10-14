package com.aagosh.dispatch.api;

/**
 * Response payload detailing the outcome of a lifecycle transition attempt.
 */
public record TripLifecycleResponse(
        String requestId,
        String driverId,
        String status,
        String outcome,
        String message
) {}
