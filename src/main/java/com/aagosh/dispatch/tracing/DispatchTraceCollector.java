package com.aagosh.dispatch.tracing;

import java.util.Objects;

/**
 * Collector contract for recording spatial search and evaluation events during matching execution.
 *
 * <p><strong>Design invariants:</strong>
 * <ul>
 *   <li><strong>Thread safety:</strong> Each collector instance is created per-request on the consumer thread.</li>
 *   <li><strong>Observational invariance:</strong> Calling collector methods must never alter candidate ordering,
 *       driver claims, or matching decisions.</li>
 *   <li><strong>Failure isolation:</strong> Implementations must isolate errors internally so that tracing failures
 *       can never disrupt dispatch matching.</li>
 *   <li><strong>Zero overhead when disabled:</strong> {@link #noop(String)} bypasses all list operations and allocations.</li>
 * </ul>
 */
public interface DispatchTraceCollector {

    /** Returns the request ID associated with this trace. */
    String requestId();

    /** Whether trace collection is actively recording. */
    boolean isEnabled();

    /** Sets the matching algorithm identifier (e.g. "GreedyMatcher (Spatial QuadTree)"). */
    void setAlgorithm(String algorithm);

    /** Records the ride request pickup location. */
    void setPickupLocation(double latitude, double longitude);

    /** Records the total number of candidate drivers present in the fresh fleet snapshot. */
    void setTotalFleetSize(int totalFleetSize);

    /** Records a spatial search expansion or evaluation stage. */
    void addStage(SpatialStage stage);

    /** Records an evaluated candidate driver. Ignored once capacity cap is reached. */
    void addCandidate(TraceCandidate candidate);

    /** Records a successful match outcome with the winning driver ID and concise explanation. */
    void recordMatch(String winnerDriverId, String decisionSummary);

    /** Records an unfulfilled dispatch outcome (e.g. no drivers available or all busy). */
    void recordNoDrivers(String decisionSummary);

    /** Records matching execution duration in nanoseconds. */
    void finish(long durationNanos);

    /**
     * Builds and returns an immutable {@link DispatchTrace} snapshot.
     * Returns {@code null} if tracing is disabled or not initialized.
     */
    DispatchTrace build();

    /**
     * Returns a no-op collector that discards all events and allocates no internal collections.
     */
    static DispatchTraceCollector noop(String requestId) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        return new NoOpDispatchTraceCollector(requestId);
    }

    /**
     * Creates a bounded collector instance using the supplied configuration.
     * If tracing is disabled in {@code config}, returns {@link #noop(String)}.
     */
    static DispatchTraceCollector create(String requestId, DispatchTraceConfig config) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        DispatchTraceConfig effectiveConfig = config != null ? config : DispatchTraceConfig.DEFAULT;
        if (!effectiveConfig.enabled()) {
            return noop(requestId);
        }
        return new BoundedDispatchTraceCollector(requestId, effectiveConfig);
    }
}
