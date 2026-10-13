package com.aagosh.dispatch.api;

/**
 * Request payload for driver registration and location telemetry.
 *
 * <p><strong>Ordering caveat:</strong> Location telemetry updates have no chronological
 * or event-time ordering guarantees. Last write to succeed compare-and-set wins.
 */
public record DriverPingRequest(
        String driverId,
        Double latitude,
        Double longitude,
        PingAction action
) {
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
    }

    public PingAction resolveAction() {
        return action != null ? action : PingAction.AUTO;
    }
}
