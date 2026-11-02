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

    /**
     * Dead Letter Queue for ride-request events that exhaust all retry attempts.
     *
     * <p>Messages land here when a {@link com.aagosh.dispatch.messaging.RideRequestConsumer}
     * listener throws an unhandled exception (e.g., matching engine failure, DB unavailability)
     * and the {@link org.springframework.kafka.listener.DefaultErrorHandler} exponential-backoff
     * policy is exhausted after {@value com.aagosh.dispatch.config.KafkaConfig#DLQ_MAX_ATTEMPTS}
     * attempts. The original message, headers, and exception cause are preserved for forensic
     * replay or alerting.
     */
    public static final String DISPATCH_FAILURES_DLQ = "dispatch-failures-dlq";

    private KafkaTopics() {}
}
