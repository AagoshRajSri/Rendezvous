package com.aagosh.dispatch.api;

/**
 * Request payload for driver registration and location telemetry.
 *
 * <h2>Timestamp semantics</h2>
 * <p>The optional {@code capturedAt} field represents when the GPS fix was captured on the
 * client device, expressed as milliseconds since the Unix epoch (UTC).
 *
 * <p><strong>Backward compatibility:</strong> The field is nullable. Existing producers
 * that do not include {@code capturedAt} will deserialise to {@code null}, which consumers
 * treat as "no client-side timestamp available". In that case the consumer falls back to
 * the Kafka record's broker-append timestamp as a best-effort ordering signal.
 *
 * <p><strong>Limitations of the fallback:</strong> Broker-append timestamps reflect when
 * the message reached the broker, not when the GPS fix was captured. Under network delay or
 * producer retries, two messages may arrive in reverse chronological order even with the
 * same partition. The fallback is documented here and in {@code DriverPingConsumer}.
 *
 * <h2>Ordering caveat</h2>
 * <p>Location telemetry updates have no chronological ordering guarantee unless
 * {@code capturedAt} is present. When both events carry {@code capturedAt}, the newer
 * timestamp always wins in both the local registry and the Redis location cache.
 */
public record DriverPingRequest(
        String driverId,
        Double latitude,
        Double longitude,
        PingAction action,
        Long capturedAt
) {
    /**
     * Backward-compatible compact constructor: four-argument variant used by existing
     * producers and tests that do not supply {@code capturedAt}.
     */
    public DriverPingRequest(String driverId, Double latitude, Double longitude, PingAction action) {
        this(driverId, latitude, longitude, action, null);
    }

    public void validate() {
        if (driverId == null || driverId.isBlank()) {
            throw new InvalidRequestException("driverId must not be null or blank");
        }
        if (latitude == null || !Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
            throw new InvalidRequestException("latitude must be a finite number between -90.0 and 90.0");
        }
        if (longitude == null || !Double.isFinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            throw new InvalidRequestException("longitude must be a finite number between -180.0 and 180.0");
        }
        if (capturedAt != null && capturedAt <= 0) {
            throw new InvalidRequestException("capturedAt must be a positive epoch-millisecond timestamp");
        }
    }

    public PingAction resolveAction() {
        return action != null ? action : PingAction.AUTO;
    }
}
