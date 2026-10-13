package com.aagosh.dispatch.matching;

/**
 * Immutable, validated configuration for {@link DispatchCostFunction}.
 *
 * <h2>Formula</h2>
 * <pre>
 * totalCost = wEta        * normalizedEta
 *           + wDensity    * normalizedDensity
 *           + wOpportunity * normalizedOpportunity
 * </pre>
 *
 * <h2>Component units and normalization</h2>
 * <ul>
 *   <li><strong>ETA</strong>: Haversine distance ÷ {@code avgSpeedKmh} = hours.
 *       Normalized to {@code [0, 1]} by dividing by {@code maxEtaHours} and
 *       clamping. This is a straight-line travel-time estimate; it does not
 *       account for traffic, road networks, or turn penalties.</li>
 *   <li><strong>Density</strong>: count of unmatched ride-request pickup
 *       locations within {@code densityRadiusKm} of the candidate driver.
 *       Normalized to {@code [0, 1]} by dividing by {@code maxDensityCount}
 *       and clamping.</li>
 *   <li><strong>Opportunity</strong>: a neutral placeholder ({@code 0.0}).
 *       The current domain model ({@link com.aagosh.dispatch.domain.Driver} and
 *       {@link com.aagosh.dispatch.domain.RideRequest}) does not carry
 *       information sufficient to compute a meaningful opportunity value
 *       (e.g., surge multipliers, destination profitability). Using a neutral
 *       value rather than fabricating business data preserves correctness.
 *       When the domain is extended, this component can be replaced without
 *       changing the formula structure or configuration interface.</li>
 * </ul>
 *
 * <h2>Default weights and configuration</h2>
 * <p>The {@link #DEFAULT} constant uses equal weights ({@code 1.0} each),
 * a 60 km/h average speed, a 1 km density radius, a 0.5-hour max-ETA
 * reference, and a max-density reference of 10 requests.
 *
 * <h2>Validation rules</h2>
 * All weights, speed, radius, and reference-max values must be finite and
 * strictly positive. At least one weight must be positive (all-zero weights
 * would produce a constant-zero cost that prevents meaningful ordering).
 */
public record DispatchCostConfig(
        /** Weight applied to the normalized ETA component. Must be > 0 or
         *  the other weights must be > 0. Finite positive. */
        double wEta,

        /** Weight applied to the normalized competition-density component. */
        double wDensity,

        /** Weight applied to the normalized opportunity component.
         *  The opportunity component is explicitly deferred and currently inactive (always 0.0)
         *  because the current domain model (Driver and RideRequest) does not carry
         *  destination or fare data. Setting wOpportunity = 0.0 is permitted and recommended.
         *  Setting wOpportunity > 0 is retained for forward compatibility and produces no effect. */
        double wOpportunity,

        /**
         * Assumed average travel speed in km/h used to convert Haversine
         * distance into an estimated pickup time. Must be strictly positive
         * and finite. This is a placeholder; it does not model traffic.
         */
        double avgSpeedKmh,

        /**
         * Reference ETA in hours used as the denominator for normalizing the
         * raw ETA to {@code [0, 1]}. A raw ETA ≥ {@code maxEtaHours} maps to
         * a normalized value of 1.0.
         */
        double maxEtaHours,

        /**
         * Radius in km used to count competing unmatched requests around a
         * candidate driver. Must be finite and non-negative.
         */
        double densityRadiusKm,

        /**
         * Reference density count used as the denominator for normalizing the
         * raw density count to {@code [0, 1]}. A raw count ≥
         * {@code maxDensityCount} maps to a normalized value of 1.0.
         */
        double maxDensityCount
) {

    /** Equal-weight default: 60 km/h, 1 km radius, 0.5 h max-ETA, 10 max-density. */
    public static final DispatchCostConfig DEFAULT = new DispatchCostConfig(
            1.0,   // wEta
            1.0,   // wDensity
            1.0,   // wOpportunity
            60.0,  // avgSpeedKmh
            0.5,   // maxEtaHours  (30 minutes)
            1.0,   // densityRadiusKm
            10.0   // maxDensityCount
    );

    public DispatchCostConfig {
        requireFinitePositive(wEta,           "wEta");
        requireFinitePositive(wDensity,        "wDensity");
        requireFiniteNonNegative(wOpportunity, "wOpportunity");
        requireFinitePositive(avgSpeedKmh,     "avgSpeedKmh");
        requireFinitePositive(maxEtaHours,     "maxEtaHours");
        requireFiniteNonNegative(densityRadiusKm, "densityRadiusKm");
        requireFinitePositive(maxDensityCount, "maxDensityCount");
    }

    private static void requireFinitePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(
                    name + " must be finite and strictly positive, got: " + value
            );
        }
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-negative, got: " + value
            );
        }
    }
}
