package com.aagosh.dispatch.api;

import java.time.Instant;

/**
 * Response payload for a ride-request status query.
 *
 * <h2>Status values</h2>
 * <ul>
 *   <li>{@code PENDING} - the request has been received and is awaiting matching.</li>
 *   <li>{@code MATCHED} - a driver was successfully assigned (see {@link #driverId}).</li>
 *   <li>{@code NO_DRIVERS} - no available driver was found at processing time (terminal).</li>
 * </ul>
 *
 * <p>{@code driverId} is populated only when {@code status} is {@code MATCHED}.
 * It is {@code null} for {@code PENDING} and {@code NO_DRIVERS}.
 *
 * <h2>Driver telemetry fields</h2>
 * <p>{@code driverLatitude}, {@code driverLongitude}, and {@code driverLocationAt} carry
 * the matched driver's <em>last-known</em> telemetry coordinates at the time of the status
 * query (sourced from the in-memory registry). They are populated only when the status is
 * {@code MATCHED} and a verified, fresh location exists in the registry. They are
 * {@code null} for {@code PENDING}, {@code NO_DRIVERS}, and any case where the driver's
 * location is unavailable or stale.
 *
 * <p><strong>Important:</strong> these coordinates reflect the driver's cached position
 * at query time, <em>not</em> the driver's position at assignment time. They must not be
 * presented as assignment-time coordinates.
 */
public record RequestStatusResponse(
        String  requestId,
        String  status,
        String  driverId,
        Instant receivedAt,
        Instant updatedAt,
        /** Last-known driver latitude; {@code null} when unavailable. */
        Double  driverLatitude,
        /** Last-known driver longitude; {@code null} when unavailable. */
        Double  driverLongitude,
        /** Wall-clock instant of the driver's last location fix; {@code null} when unavailable. */
        Instant driverLocationAt
) {
    /**
     * Convenience factory for responses without driver telemetry (PENDING / NO_DRIVERS).
     */
    public static RequestStatusResponse withoutTelemetry(
            String requestId, String status, String driverId,
            Instant receivedAt, Instant updatedAt) {
        return new RequestStatusResponse(
                requestId, status, driverId, receivedAt, updatedAt,
                null, null, null);
    }
}
