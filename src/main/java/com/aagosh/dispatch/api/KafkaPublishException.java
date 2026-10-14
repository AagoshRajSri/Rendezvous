package com.aagosh.dispatch.api;

/**
 * Thrown when a Kafka publish operation fails after configured retries.
 * Mapped to HTTP 503 Service Unavailable by {@link GlobalExceptionHandler}.
 */
public class KafkaPublishException extends RuntimeException {

    public KafkaPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
