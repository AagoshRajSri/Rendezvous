package com.aagosh.dispatch.api;

/**
 * Response payload for an asynchronously accepted ride request.
 *
 * <p>HTTP 202 Accepted indicates that the ride request event has been durably
 * registered and published to the {@code ride-requests} Kafka topic.
 *
 * <p>Provides both {@link #requestId()} and {@link #rideId()} as aliases for
 * backwards compatibility across API and test clients.
 */
public record RideAcceptedResponse(
        String requestId,
        String rideId,
        String message
) {
    public RideAcceptedResponse(String requestId, String message) {
        this(requestId, requestId, message);
    }
}
