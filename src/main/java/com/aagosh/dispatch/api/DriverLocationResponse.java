package com.aagosh.dispatch.api;

import java.time.Instant;

/**
 * Response payload for {@code GET /api/drivers/{driverId}/location}.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code driverId} - the driver's logical identifier.</li>
 *   <li>{@code latitude} - last-known latitude in decimal degrees (WGS-84).</li>
 *   <li>{@code longitude} - last-known longitude in decimal degrees (WGS-84).</li>
 *   <li>{@code updatedAt} - wall-clock instant when the location was last accepted by the server.</li>
 *   <li>{@code fresh} - {@code true} if the location is within the configured staleness
 *                             threshold and the timestamp is not implausibly in the future.</li>
 * </ul>
 *
 * <h2>HTTP status mapping</h2>
 * <ul>
 *   <li>HTTP 200 - a fresh, verified location is available ({@code fresh == true}).</li>
 *   <li>HTTP 404 - the driver is not registered in the in-memory registry.</li>
 *   <li>HTTP 409 - the driver is known but the location is stale, missing, or unverified
 *                  ({@code fresh == false}). The response body still carries whatever last-known
 *                  coordinates exist so the caller can make an informed decision.</li>
 * </ul>
 *
 * <p><strong>Security disclaimer:</strong> coordinates are sourced from un-authenticated driver
 * pings. This is demonstration infrastructure; production deployments must authenticate drivers.
 */
public record DriverLocationResponse(
        String  driverId,
        Double  latitude,
        Double  longitude,
        Instant updatedAt,
        boolean fresh
) {}
