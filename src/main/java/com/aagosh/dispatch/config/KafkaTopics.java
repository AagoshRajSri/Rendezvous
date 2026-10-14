package com.aagosh.dispatch.config;

/**
 * Central constants for Kafka topic names used across producers and consumers.
 *
 * <p>Values are injected from {@code application.properties} via
 * {@code dispatch.kafka.topics.*} keys. This class provides the raw string names
 * for use in {@code @KafkaListener} annotations and
 * {@code KafkaTemplate.send()} calls.
 */
public final class KafkaTopics {

    /** Topic for inbound driver location telemetry and registration events. */
    public static final String DRIVER_PINGS = "driver-pings";

    /** Topic for inbound ride dispatch requests. */
    public static final String RIDE_REQUESTS = "ride-requests";

    private KafkaTopics() {}
}
