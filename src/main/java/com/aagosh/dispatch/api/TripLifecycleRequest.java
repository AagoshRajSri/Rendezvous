package com.aagosh.dispatch.api;

/**
 * Request payload identifying the driver attempting a trip lifecycle transition.
 *
 * <h2>Security / Authentication Notice</h2>
 * <p>{@code driverId} is an unauthenticated, caller-supplied identifier used exclusively
 * for demonstration-level ownership checks against the persistent trip record.
 * It does not constitute proof of driver identity. Production deployments must authenticate
 * the driver via secure credentials (e.g. JWT, session tokens, mTLS).
 */
public record TripLifecycleRequest(
        String driverId
) {
    public void validate() {
        if (driverId == null || driverId.isBlank()) {
            throw new InvalidRequestException("driverId must not be null or blank");
        }
    }
}
