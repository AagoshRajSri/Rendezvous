package com.aagosh.dispatch.api;

/**
 * Response payload for an asynchronously accepted driver ping event.
 *
 * <p>HTTP 202 Accepted indicates that the telemetry or registration event has been durably
 * published to the {@code driver-pings} Kafka topic. Processing (registry update or driver
 * registration) occurs asynchronously in {@code DriverPingConsumer}. No registration or
 * match result is returned here.
 */
public record DriverPingAcceptedResponse(
        String driverId,
        String message
) {}
