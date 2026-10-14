package com.aagosh.dispatch.api;

/**
 * Response payload for an asynchronously accepted ride request.
 *
 * <p>HTTP 202 Accepted indicates that the ride request event has been durably published to
 * the {@code ride-requests} Kafka topic. Actual driver matching is performed asynchronously
 * by {@code RideRequestConsumer}. The caller will <em>not</em> receive an immediate
 * driver assignment in this response.
 *
 * <h2>Limitations</h2>
 * <p>Because matching is now asynchronous, the client cannot determine whether a driver was
 * found by inspecting this response. A future phase will add a status endpoint or push
 * notification to return the assignment result.
 */
public record RideAcceptedResponse(
        String requestId,
        String message
) {}
