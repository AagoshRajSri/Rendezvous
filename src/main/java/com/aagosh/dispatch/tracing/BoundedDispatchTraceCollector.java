package com.aagosh.dispatch.tracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, fail-safe collector that records dispatch evaluation events up to configured capacity limits.
 *
 * <h2>Failure Isolation</h2>
 * <p>All recording methods are protected by internal exception guards. If an unexpected runtime error occurs
 * during trace recording, it is logged at debug level and safely suppressed. Trace collection failures
 * <strong>never</strong> throw into the caller or disrupt the matching engine.
 */
final class BoundedDispatchTraceCollector implements DispatchTraceCollector {

    private static final Logger log = LoggerFactory.getLogger(BoundedDispatchTraceCollector.class);

    private final String requestId;
    private final DispatchTraceConfig config;
    private final Instant startedAt;

    private String algorithm = "Unknown";
    private double pickupLatitude = 0.0;
    private double pickupLongitude = 0.0;
    private int totalFleetSize = 0;
    private long durationNanos = 0L;
    private String winnerDriverId = null;
    private boolean matched = false;
    private String decisionSummary = "";

    private final List<SpatialStage> stages;
    private final List<TraceCandidate> candidates;

    BoundedDispatchTraceCollector(String requestId, DispatchTraceConfig config) {
        this.requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.startedAt = Instant.now();
        this.stages = new ArrayList<>(Math.min(config.maxStagesPerTrace(), 8));
        this.candidates = new ArrayList<>(Math.min(config.maxCandidatesPerTrace(), 16));
    }

    @Override
    public String requestId() {
        return requestId;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void setAlgorithm(String algorithm) {
        try {
            if (algorithm != null && !algorithm.isBlank()) {
                this.algorithm = algorithm;
            }
        } catch (Exception e) {
            log.debug("Trace isolation: failed to set algorithm for request {}: {}", requestId, e.getMessage());
        }
    }

    @Override
    public void setPickupLocation(double latitude, double longitude) {
        try {
            this.pickupLatitude = latitude;
            this.pickupLongitude = longitude;
        } catch (Exception e) {
            log.debug("Trace isolation: failed to set pickup location for request {}: {}", requestId, e.getMessage());
        }
    }

    @Override
    public void setTotalFleetSize(int totalFleetSize) {
        try {
            this.totalFleetSize = Math.max(0, totalFleetSize);
        } catch (Exception e) {
            log.debug("Trace isolation: failed to set total fleet size for request {}: {}", requestId, e.getMessage());
        }
    }

    @Override
    public void addStage(SpatialStage stage) {
        try {
            if (stage != null && stages.size() < config.maxStagesPerTrace()) {
                stages.add(stage);
            }
        } catch (Exception e) {
            log.debug("Trace isolation: failed to add stage for request {}: {}", requestId, e.getMessage());
        }
    }

    @Override
    public void addCandidate(TraceCandidate candidate) {
        try {
            if (candidate != null && candidates.size() < config.maxCandidatesPerTrace()) {
                candidates.add(candidate);
            }
        } catch (Exception e) {
            log.debug("Trace isolation: failed to add candidate for request {}: {}", requestId, e.getMessage());
        }
    }

    @Override
    public void recordMatch(String winnerDriverId, String decisionSummary) {
        try {
            this.matched = true;
            this.winnerDriverId = winnerDriverId;
            if (decisionSummary != null) {
                this.decisionSummary = decisionSummary;
            }
        } catch (Exception e) {
            log.debug("Trace isolation: failed to record match for request {}: {}", requestId, e.getMessage());
        }
    }

    @Override
    public void recordNoDrivers(String decisionSummary) {
        try {
            this.matched = false;
            this.winnerDriverId = null;
            if (decisionSummary != null) {
                this.decisionSummary = decisionSummary;
            }
        } catch (Exception e) {
            log.debug("Trace isolation: failed to record no-drivers for request {}: {}", requestId, e.getMessage());
        }
    }

    @Override
    public void finish(long durationNanos) {
        try {
            this.durationNanos = Math.max(0L, durationNanos);
        } catch (Exception e) {
            log.debug("Trace isolation: failed to finish trace for request {}: {}", requestId, e.getMessage());
        }
    }

    @Override
    public DispatchTrace build() {
        try {
            return new DispatchTrace(
                    requestId,
                    algorithm,
                    pickupLatitude,
                    pickupLongitude,
                    startedAt,
                    durationNanos,
                    stages,
                    candidates,
                    winnerDriverId,
                    matched,
                    totalFleetSize,
                    decisionSummary
            );
        } catch (Exception e) {
            log.warn("Trace isolation: failed to build DispatchTrace for request {}: {}", requestId, e.getMessage());
            return null;
        }
    }
}
