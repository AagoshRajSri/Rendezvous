package com.aagosh.dispatch.tracing;

import java.util.Objects;

/**
 * Immutable value object representing a driver candidate evaluated during the dispatch matching process.
 *
 * <p>Captures the candidate's spatial distance, decomposed cost function components (when available),
 * evaluation rank, and terminal outcome (e.g. matched, outcompeted, unavailable, or claim race lost).
 */
public record TraceCandidate(
        String driverId,
        double latitude,
        double longitude,
        double distanceKm,
        Double etaCost,
        Double densityCost,
        Double opportunityCost,
        Double totalCost,
        int rank,
        CandidateOutcome outcome,
        String rejectionReason
) {
    public TraceCandidate {
        Objects.requireNonNull(driverId, "driverId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");

        if (driverId.isBlank()) {
            throw new IllegalArgumentException("driverId must not be blank");
        }
        if (distanceKm < 0 || Double.isNaN(distanceKm) || Double.isInfinite(distanceKm)) {
            throw new IllegalArgumentException("distanceKm must be finite and non-negative, got: " + distanceKm);
        }
        if (rank < 0) {
            throw new IllegalArgumentException("rank must be non-negative, got: " + rank);
        }
    }
}
