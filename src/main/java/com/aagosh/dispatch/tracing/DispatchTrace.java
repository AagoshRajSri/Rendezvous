package com.aagosh.dispatch.tracing;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable value object representing a complete, authentic dispatch execution trace for a single ride request.
 *
 * <p>Captures the algorithm used, spatial stages explored, candidate drivers evaluated, winner identification,
 * timing, and a concise decision summary. All collections are defensively copied and unmodifiable.
 */
public record DispatchTrace(
        String requestId,
        String algorithm,
        double pickupLatitude,
        double pickupLongitude,
        Instant startedAt,
        long durationNanos,
        List<SpatialStage> stages,
        List<TraceCandidate> candidates,
        String winnerDriverId,
        boolean matched,
        int totalFleetSize,
        String decisionSummary
) {
    public DispatchTrace {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");

        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (durationNanos < 0) {
            throw new IllegalArgumentException("durationNanos must be non-negative, got: " + durationNanos);
        }
        if (totalFleetSize < 0) {
            throw new IllegalArgumentException("totalFleetSize must be non-negative, got: " + totalFleetSize);
        }

        // Defensive copy ensuring immutability
        stages = stages != null ? List.copyOf(stages) : List.of();
        candidates = candidates != null ? List.copyOf(candidates) : List.of();
    }
}
