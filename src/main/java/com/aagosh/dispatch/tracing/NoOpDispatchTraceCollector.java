package com.aagosh.dispatch.tracing;

import java.util.Objects;

/**
 * No-op collector implementation used when dispatch tracing is disabled.
 *
 * <p>All event recording methods are safe no-ops and perform zero allocations.
 */
final class NoOpDispatchTraceCollector implements DispatchTraceCollector {

    private final String requestId;

    NoOpDispatchTraceCollector(String requestId) {
        this.requestId = Objects.requireNonNull(requestId, "requestId must not be null");
    }

    @Override
    public String requestId() {
        return requestId;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void setAlgorithm(String algorithm) {}

    @Override
    public void setPickupLocation(double latitude, double longitude) {}

    @Override
    public void setTotalFleetSize(int totalFleetSize) {}

    @Override
    public void addStage(SpatialStage stage) {}

    @Override
    public void addCandidate(TraceCandidate candidate) {}

    @Override
    public void recordMatch(String winnerDriverId, String decisionSummary) {}

    @Override
    public void recordNoDrivers(String decisionSummary) {}

    @Override
    public void finish(long durationNanos) {}

    @Override
    public DispatchTrace build() {
        return null;
    }
}
