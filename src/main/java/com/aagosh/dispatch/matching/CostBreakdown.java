package com.aagosh.dispatch.matching;

/**
 * Immutable value object representing decomposed cost function components.
 *
 * <p>Captures the weighted cost contributions from ETA, competition density,
 * and opportunity cost that sum to {@link #totalCost()}.
 */
public record CostBreakdown(
        double etaCost,
        double densityCost,
        double opportunityCost,
        double totalCost
) {}
