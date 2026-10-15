package com.aagosh.dispatch.api;

import java.time.Instant;

/**
 * Response payload for a ride-request status query.
 *
 * <h2>Status values</h2>
 * <ul>
 *   <li>{@code PENDING}    – the request has been received and is awaiting matching.</li>
 *   <li>{@code MATCHED}    – a driver was successfully assigned (see {@link #driverId}).</li>
 *   <li>{@code NO_DRIVERS} – no available driver was found at processing time (terminal).</li>
 * </ul>
 *
 * <p>{@code driverId} is populated only when {@code status} is {@code MATCHED}.
 * It is {@code null} for {@code PENDING} and {@code NO_DRIVERS}.
 */
public record RequestStatusResponse(
        String requestId,
        String status,
        String driverId,
        Instant receivedAt,
        Instant updatedAt
) {}
