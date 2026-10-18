package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.RideRequest;

import java.util.Objects;

/**
 * Weighted, normalized cost function for driver–ride-request pairings.
 *
 * <h2>Formula</h2>
 * <pre>
 * totalCost = wEta        * normalizedEta
 *           + wDensity    * normalizedDensity
 *           + wOpportunity * normalizedOpportunity
 * </pre>
 *
 * <h2>Components</h2>
 * <ul>
 *   <li><strong>ETA (pickup time estimate)</strong>: Haversine distance
 *       between the driver's current location and the request's pickup point,
 *       divided by {@code config.avgSpeedKmh()}, giving an estimated travel
 *       time in hours. Normalized to {@code [0, 1]} by dividing by
 *       {@code config.maxEtaHours()} and clamping. A shorter ETA → lower
 *       normalized value → lower total cost → preferred assignment.</li>
 *   <li><strong>Competition density</strong>: the number of unmatched request
 *       pickup locations within {@code config.densityRadiusKm()} of the
 *       candidate driver, normalized to {@code [0, 1]} by dividing by
 *       {@code config.maxDensityCount()} and clamping. A driver surrounded by
 *       many unmatched requests represents higher competition for that
 *       driver's capacity, so higher density increases cost and discourages
 *       selection of over-contested drivers.</li>
 *   <li><strong>Opportunity</strong>: currently {@code 0.0}. The
 *       {@link com.aagosh.dispatch.domain.Driver} and
 *       {@link com.aagosh.dispatch.domain.RideRequest} domain objects carry no
 *       information about surge pricing, destination profitability, or trip
 *       value. Fabricating data would produce a misleading cost signal.
 *       This component is structurally reserved: the weight is validated,
 *       the formula includes it, and the {@link DispatchCostConfig} exposes
 *       it, so it can be activated without changing any interfaces once the
 *       domain is extended.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>Instances are effectively immutable after construction: the
 * {@link DispatchCostConfig} is a record (fully immutable), and the
 * {@link RequestDensityIndex} is built once and never mutated. Multiple
 * threads may call {@link #calculate} concurrently without external
 * synchronization.
 *
 * <h2>Cost range</h2>
 * <p>The returned cost is always finite and in the range
 * {@code [0, wEta + wDensity + wOpportunity]}. Both properties are
 * verified by the {@link Assignment} record's compact constructor.
 */
public final class DispatchCostFunction implements CostFunction {

    private final DispatchCostConfig config;
    private final RequestDensityIndex densityIndex;

    /**
     * Creates a cost function with the given configuration and request density
     * snapshot.
     *
     * @param config       validated cost configuration; must not be
     *                     {@code null}
     * @param densityIndex pre-built snapshot of unmatched request locations;
     *                     must not be {@code null}
     */
    public DispatchCostFunction(
            DispatchCostConfig config,
            RequestDensityIndex densityIndex
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.densityIndex = Objects.requireNonNull(densityIndex, "densityIndex");
    }

    /**
     * Convenience constructor that builds the {@link RequestDensityIndex}
     * automatically from the supplied list.
     *
     * @param config             validated cost configuration
     * @param unmatchedRequests  snapshot of unmatched ride requests used to
     *                           measure competition density; may be empty but
     *                           must not be {@code null}
     */
    public DispatchCostFunction(
            DispatchCostConfig config,
            java.util.List<RideRequest> unmatchedRequests
    ) {
        this(config, new RequestDensityIndex(unmatchedRequests));
    }

    /**
     * Calculates the weighted, normalized dispatch cost for assigning
     * {@code driver} to {@code request}.
     *
     * @param driver  the candidate driver; must not be {@code null}
     * @param request the ride request; must not be {@code null}
     * @return a finite, non-negative cost value
     */
    @Override
    public double calculate(Driver driver, RideRequest request) {
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(request, "request");

        double etaCost      = etaComponent(driver, request);
        double densityCost  = densityComponent(driver);
        double opportunityCost = 0.0; // see class-level javadoc

        return config.wEta()         * etaCost
             + config.wDensity()     * densityCost
             + config.wOpportunity() * opportunityCost;
    }

    /**
     * Computes the decomposed cost breakdown for assigning {@code driver} to {@code request}.
     *
     * @param driver  the candidate driver; must not be {@code null}
     * @param request the ride request; must not be {@code null}
     * @return the decomposed weighted cost components and total cost
     */
    public CostBreakdown decompose(Driver driver, RideRequest request) {
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(request, "request");

        double normEta = etaComponent(driver, request);
        double normDensity = densityComponent(driver);
        double normOpportunity = 0.0;

        double wEta = config.wEta() * normEta;
        double wDensity = config.wDensity() * normDensity;
        double wOpportunity = config.wOpportunity() * normOpportunity;
        double total = wEta + wDensity + wOpportunity;

        return new CostBreakdown(wEta, wDensity, wOpportunity, total);
    }

    // -------------------------------------------------------------------------
    // Private component calculations
    // -------------------------------------------------------------------------

    /**
     * Computes and normalizes the ETA component.
     *
     * @return normalized ETA in {@code [0, 1]}
     */
    private double etaComponent(Driver driver, RideRequest request) {
        GeoPoint driverLoc  = driver.location();
        GeoPoint pickupLoc  = request.pickupLocation();

        double distanceKm = driverLoc.distanceToKm(pickupLoc);
        double etaHours   = distanceKm / config.avgSpeedKmh();

        return clamp(etaHours / config.maxEtaHours());
    }

    /**
     * Computes and normalizes the competition-density component.
     *
     * @return normalized density in {@code [0, 1]}
     */
    private double densityComponent(Driver driver) {
        int nearbyRequests = densityIndex.countNearby(
                driver.location(),
                config.densityRadiusKm()
        );

        return clamp((double) nearbyRequests / config.maxDensityCount());
    }

    /**
     * Clamps a value to {@code [0, 1]}.
     */
    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
