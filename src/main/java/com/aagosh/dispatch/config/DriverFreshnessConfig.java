package com.aagosh.dispatch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configurable thresholds used to determine whether a driver's last-known location
 * is fresh enough for dispatch matching and telemetry responses.
 *
 * <h2>Staleness</h2>
 * <p>A driver whose {@code updatedAt} epoch-millisecond timestamp is more than
 * {@link #stalenessSeconds()} seconds in the past is considered stale and is excluded
 * from {@link com.aagosh.dispatch.domain.DriverRegistry#snapshotFresh} results and
 * from the {@code GET /api/drivers/{driverId}/location} fresh response.
 *
 * <h2>Future-skew guard</h2>
 * <p>A driver whose {@code updatedAt} timestamp is more than {@link #maxFutureSkewSeconds()}
 * seconds in the future (relative to the server's wall clock) is also excluded.
 * This prevents misbehaving clients from injecting far-future timestamps that would
 * permanently suppress subsequent legitimate updates.
 *
 * <h2>Timestamp-less drivers</h2>
 * <p>Drivers with {@code updatedAt == 0L} have no verified timestamp and are excluded
 * from fresh snapshots. They must not enter production matching.
 */
@ConfigurationProperties(prefix = "dispatch.matching")
public record DriverFreshnessConfig(
        /**
         * Drivers older than this many seconds are excluded from fresh snapshots.
         * Default: 60 seconds.
         */
        @DefaultValue("60") long stalenessSeconds,

        /**
         * Timestamps this many seconds in the future are rejected as clock-skewed.
         * Default: 5 seconds.
         */
        @DefaultValue("5") long maxFutureSkewSeconds
) {
    /** Canonical default used in tests and as a safe fallback. */
    public static final DriverFreshnessConfig DEFAULT =
            new DriverFreshnessConfig(60L, 5L);

    public DriverFreshnessConfig {
        if (stalenessSeconds <= 0) {
            throw new IllegalArgumentException(
                    "dispatch.matching.driver-staleness-seconds must be > 0, got: " + stalenessSeconds);
        }
        if (maxFutureSkewSeconds < 0) {
            throw new IllegalArgumentException(
                    "dispatch.matching.max-future-skew-seconds must be >= 0, got: " + maxFutureSkewSeconds);
        }
    }
}
