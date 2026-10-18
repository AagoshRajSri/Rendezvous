package com.aagosh.dispatch.tracing;

import java.time.Duration;

/**
 * Immutable configuration defining capacity caps, memory bounds, and retention limits for dispatch tracing.
 *
 * <p>Enforces strict upper bounds on trace size and cache capacity to prevent excessive allocations or
 * unbounded memory retention in high-throughput dispatch environments.
 */
public record DispatchTraceConfig(
        int maxCandidatesPerTrace,
        int maxStagesPerTrace,
        int maxStoredTraces,
        Duration traceTtl,
        boolean enabled
) {
    /**
     * Default production configuration:
     * <ul>
     *   <li>Max 32 candidates per trace.</li>
     *   <li>Max 16 spatial stages per trace.</li>
     *   <li>Max 200 stored traces in memory.</li>
     *   <li>10-minute retention TTL.</li>
     *   <li>Tracing enabled.</li>
     * </ul>
     */
    public static final DispatchTraceConfig DEFAULT =
            new DispatchTraceConfig(32, 16, 200, Duration.ofMinutes(10), true);

    public DispatchTraceConfig {
        // Enforce safe boundaries on all configuration properties
        maxCandidatesPerTrace = Math.max(1, Math.min(100, maxCandidatesPerTrace));
        maxStagesPerTrace     = Math.max(1, Math.min(50, maxStagesPerTrace));
        maxStoredTraces       = Math.max(1, Math.min(5_000, maxStoredTraces));
        traceTtl              = (traceTtl != null && !traceTtl.isNegative() && !traceTtl.isZero())
                ? traceTtl
                : Duration.ofMinutes(10);
    }
}
