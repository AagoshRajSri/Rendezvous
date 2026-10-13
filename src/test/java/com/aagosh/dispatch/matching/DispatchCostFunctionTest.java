package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.RideRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DispatchCostFunction}, {@link DispatchCostConfig}, and
 * {@link RequestDensityIndex}.
 *
 * <h2>Coverage objectives</h2>
 * <ul>
 *   <li>ETA component: correct formula, normalization, clamping.</li>
 *   <li>Density component: correct counting, normalization, clamping, radius
 *       semantics (inclusive boundary, zero radius).</li>
 *   <li>Opportunity component: always zero in the current domain model.</li>
 *   <li>Weight application and additivity.</li>
 *   <li>Boundary cases: co-located driver and pickup, maximum clamping.</li>
 *   <li>Config validation: all invalid parameter combinations rejected.</li>
 *   <li>RequestDensityIndex: null inputs, empty list, request multiplicity.</li>
 *   <li>DispatchCostFunction: integration of all three components via the
 *       convenience list constructor.</li>
 *   <li>GreedyMatcher integration: {@link DispatchCostFunction} used as the
 *       cost function produces sensible assignments.</li>
 * </ul>
 */
class DispatchCostFunctionTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Driver driverAt(double lat, double lon) {
        return new Driver("D", new GeoPoint(lat, lon), new DriverState());
    }

    private static RideRequest requestAt(double lat, double lon) {
        return new RideRequest("R", new GeoPoint(lat, lon));
    }

    private static RequestDensityIndex emptyIndex() {
        return new RequestDensityIndex(List.of());
    }

    // =========================================================================
    // DispatchCostConfig — validation
    // =========================================================================

    @Test
    void configDefaultIsValid() {
        assertDoesNotThrow(() -> DispatchCostConfig.DEFAULT);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void configRejectsInvalidWEta(double bad) {
        assertThrows(IllegalArgumentException.class, () ->
                new DispatchCostConfig(bad, 1.0, 1.0, 60.0, 0.5, 1.0, 10.0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void configRejectsInvalidWDensity(double bad) {
        assertThrows(IllegalArgumentException.class, () ->
                new DispatchCostConfig(1.0, bad, 1.0, 60.0, 0.5, 1.0, 10.0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void configRejectsInvalidWOpportunity(double bad) {
        assertThrows(IllegalArgumentException.class, () ->
                new DispatchCostConfig(1.0, 1.0, bad, 60.0, 0.5, 1.0, 10.0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void configRejectsInvalidAvgSpeed(double bad) {
        assertThrows(IllegalArgumentException.class, () ->
                new DispatchCostConfig(1.0, 1.0, 1.0, bad, 0.5, 1.0, 10.0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void configRejectsInvalidMaxEtaHours(double bad) {
        assertThrows(IllegalArgumentException.class, () ->
                new DispatchCostConfig(1.0, 1.0, 1.0, 60.0, bad, 1.0, 10.0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void configRejectsInvalidDensityRadius(double bad) {
        assertThrows(IllegalArgumentException.class, () ->
                new DispatchCostConfig(1.0, 1.0, 1.0, 60.0, 0.5, bad, 10.0));
    }

    @Test
    void configAcceptsZeroDensityRadius() {
        // Zero radius is valid — no nearby requests will ever be found.
        assertDoesNotThrow(() ->
                new DispatchCostConfig(1.0, 1.0, 1.0, 60.0, 0.5, 0.0, 10.0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY})
    void configRejectsInvalidMaxDensityCount(double bad) {
        assertThrows(IllegalArgumentException.class, () ->
                new DispatchCostConfig(1.0, 1.0, 1.0, 60.0, 0.5, 1.0, bad));
    }

    // =========================================================================
    // ETA component
    // =========================================================================

    @Test
    void coLocatedDriverAndPickupProducesZeroEtaCost() {
        // distance = 0 → ETA = 0 → normalized ETA = 0 → total cost = 0
        // (density also 0 since empty index, opportunity = 0)
        DispatchCostFunction fn = new DispatchCostFunction(
                DispatchCostConfig.DEFAULT, emptyIndex()
        );

        double cost = fn.calculate(
                driverAt(17.0, 78.0),
                requestAt(17.0, 78.0)
        );

        assertEquals(0.0, cost, 1e-12, "co-located pair must produce zero cost");
    }

    @Test
    void etaCostIncreasesWithDistance() {
        DispatchCostFunction fn = new DispatchCostFunction(
                DispatchCostConfig.DEFAULT, emptyIndex()
        );

        double nearCost = fn.calculate(driverAt(17.0, 78.0), requestAt(17.01, 78.0));
        double farCost  = fn.calculate(driverAt(17.0, 78.0), requestAt(17.10, 78.0));

        assertTrue(farCost > nearCost,
                "farther driver must produce higher ETA cost");
    }

    @Test
    void etaNormalizationClampedAtOne() {
        // Distance approx 111 km per degree latitude; 60 km/h → ETA ≈ 1.85 h.
        // maxEtaHours = 0.5 → normalized > 1.0 → clamped to 1.0.
        // Only ETA weight active (wDensity = wOpportunity irrelevant with empty index
        // and no opportunity). wEta = 1 → cost = 1.0.
        DispatchCostConfig config = new DispatchCostConfig(
                1.0, 1.0, 1.0, 60.0, 0.5, 1.0, 10.0
        );
        DispatchCostFunction fn = new DispatchCostFunction(config, emptyIndex());

        // ~111 km apart → ETA ~1.85 h >> maxEtaHours 0.5 → normalized clamps to 1.
        double cost = fn.calculate(
                driverAt(0.0, 0.0),
                requestAt(1.0, 0.0)  // ~111 km
        );

        // wEta*1.0 + wDensity*0.0 + wOpportunity*0.0 = 1.0
        assertEquals(1.0, cost, 1e-9, "ETA above reference must clamp to 1.0 contribution");
    }

    @Test
    void etaFormulaIsDistanceOverSpeedOverMaxEta() {
        // Construct a config where we can derive the expected value manually.
        // avgSpeedKmh = 100, maxEtaHours = 2.0, wEta = 1, wDensity = 0 (not
        // possible — must be > 0), so use tiny density radius = 0 to zero out
        // the density contribution. wOpportunity irrelevant (opportunity = 0).
        DispatchCostConfig config = new DispatchCostConfig(
                2.0,   // wEta
                1.0,   // wDensity
                1.0,   // wOpportunity
                100.0, // avgSpeedKmh
                2.0,   // maxEtaHours
                0.0,   // densityRadiusKm = 0 → density count always 0
                1.0    // maxDensityCount
        );
        DispatchCostFunction fn = new DispatchCostFunction(config, emptyIndex());

        // Two points approximately 100 km apart (1° lat ≈ 111 km, so use 0.9°):
        // Let's use known distance: GeoPoint(0,0) to GeoPoint(0, 1) ≈ 111.195 km.
        // ETA = 111.195 / 100 = 1.11195 h. Normalized = 1.11195 / 2.0 = 0.556.
        // Cost = wEta * 0.556 + 0 + 0 = 2.0 * 0.556 = 1.112 (approx).
        GeoPoint from = new GeoPoint(0.0, 0.0);
        GeoPoint to   = new GeoPoint(0.0, 1.0);
        double dist   = from.distanceToKm(to);
        double expectedNorm = Math.min(1.0, (dist / 100.0) / 2.0);
        double expectedCost = 2.0 * expectedNorm;

        double cost = fn.calculate(
                new Driver("D1", from, new DriverState()),
                new RideRequest("R1", to)
        );

        assertEquals(expectedCost, cost, 1e-9,
                "ETA formula: wEta * clamp(dist/speed/maxEta)");
    }

    // =========================================================================
    // Density component
    // =========================================================================

    @Test
    void densityIsZeroWhenNoRequests() {
        // Empty density index → density count = 0 → density cost = 0.
        DispatchCostConfig config = new DispatchCostConfig(
                1.0, 1.0, 1.0, 60.0, 0.5, 1.0, 10.0
        );
        DispatchCostFunction fn = new DispatchCostFunction(config, emptyIndex());

        double cost = fn.calculate(driverAt(17.0, 78.0), requestAt(17.0, 78.0));
        // co-located → etaCost = 0, density cost = 0, opportunity = 0
        assertEquals(0.0, cost, 1e-12);
    }

    @Test
    void densityCountsRequestsWithinRadius() {
        // One request 0.5 km from driver, one request 2.0 km from driver.
        // With density radius = 1.0 km, only the nearby one should count.
        GeoPoint driverLoc = new GeoPoint(0.0, 0.0);

        // ~55 m per 0.0005° longitude at equator → 0.5° ≈ 55 km; use very small step
        // 0.005° ≈ ~556 m → close enough to be inside 1 km radius.
        // 0.02° ≈ ~2224 m → outside 1 km radius.
        GeoPoint nearby = new GeoPoint(0.0, 0.005); // ~556 m
        GeoPoint far    = new GeoPoint(0.0, 0.02);  // ~2224 m

        RequestDensityIndex index = new RequestDensityIndex(List.of(
                new RideRequest("R1", nearby),
                new RideRequest("R2", far)
        ));

        DispatchCostConfig config = new DispatchCostConfig(
                1.0, 1.0, 1.0, 60.0, 0.5,
                1.0,   // densityRadiusKm = 1.0 km
                10.0
        );
        DispatchCostFunction fn = new DispatchCostFunction(config, index);

        // Distance from driverLoc to nearby should be < 1 km.
        double distNearby = driverLoc.distanceToKm(nearby);
        double distFar    = driverLoc.distanceToKm(far);
        assertTrue(distNearby < 1.0, "nearby should be < 1 km: " + distNearby);
        assertTrue(distFar    > 1.0, "far should be > 1 km: " + distFar);

        // Density count expected to be 1 (only the nearby request).
        // density component = 1 / 10 = 0.1
        RequestDensityIndex indexCheck = new RequestDensityIndex(List.of(
                new RideRequest("R1", nearby),
                new RideRequest("R2", far)
        ));
        int count = indexCheck.countNearby(driverLoc, 1.0);
        assertEquals(1, count, "only the nearby request should be counted");
    }

    @Test
    void densityCountsAllRequestsWithinRadius() {
        GeoPoint center = new GeoPoint(0.0, 0.0);

        // 5 requests within 0.5 km (≈ 0.0045° at equator).
        List<RideRequest> nearRequests = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> new RideRequest("R" + i,
                        new GeoPoint(0.0, i * 0.001))) // ~111 m apart
                .toList();

        RequestDensityIndex index = new RequestDensityIndex(nearRequests);
        int count = index.countNearby(center, 0.5);

        // All 5 points are < 0.5 km from center (farthest is 5 * ~111 m = 555 m
        // which exceeds 500 m). Let's verify actual distances:
        long withinHalf = nearRequests.stream()
                .filter(r -> center.distanceToKm(r.pickupLocation()) <= 0.5)
                .count();
        assertEquals(withinHalf, count,
                "count must match manual distance filter");
    }

    @Test
    void densityNormalizationClampedAtOne() {
        GeoPoint driverLoc = new GeoPoint(0.0, 0.0);

        // 20 requests within a 10 km radius.
        List<RideRequest> requests = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> new RideRequest("R" + i,
                        new GeoPoint(0.0, i * 0.005))) // ~556 m apart, all < 10 km
                .toList();

        DispatchCostConfig config = new DispatchCostConfig(
                1.0, 1.0, 1.0, 60.0, 0.5,
                50.0,  // densityRadiusKm = 50 km → all 20 within radius
                10.0   // maxDensityCount = 10 → 20 requests → clamped to 1.0
        );

        DispatchCostFunction fn = new DispatchCostFunction(config, requests);
        // driver is co-located at pickup → etaCost = 0
        double cost = fn.calculate(
                new Driver("D1", driverLoc, new DriverState()),
                new RideRequest("R0", driverLoc)
        );

        // wDensity * 1.0 = 1.0 (density clamped), wEta * 0 = 0, wOpportunity * 0 = 0
        assertEquals(1.0, cost, 1e-9,
                "density > maxDensityCount must clamp to 1.0 contribution");
    }

    @Test
    void densityRadiusInclusiveBoundary() {
        // Place a request at exactly the radius boundary.
        GeoPoint center = new GeoPoint(0.0, 0.0);

        // Compute the exact distance from center to a target point.
        GeoPoint target = new GeoPoint(0.0, 0.1); // some known distance
        double exactRadius = center.distanceToKm(target);

        RequestDensityIndex index = new RequestDensityIndex(
                List.of(new RideRequest("R1", target))
        );

        // The point is at exactly the radius → should be counted (inclusive).
        int count = index.countNearby(center, exactRadius);
        assertEquals(1, count,
                "point at exactly the radius boundary must be counted (inclusive)");
    }

    @Test
    void densityWithZeroRadiusCountsOnlyCoLocatedRequests() {
        GeoPoint center = new GeoPoint(0.0, 0.0);
        GeoPoint slightly_away = new GeoPoint(0.0, 0.001);

        RequestDensityIndex index = new RequestDensityIndex(List.of(
                new RideRequest("R1", center),         // exactly at query point
                new RideRequest("R2", slightly_away)   // slightly away
        ));

        // The distance from center to center is 0.0, which is ≤ 0.0.
        // The distance from center to slightly_away is > 0.0.
        int count = index.countNearby(center, 0.0);
        assertEquals(1, count,
                "zero radius should count only the point at exact distance 0");
    }

    @Test
    void densityIndexCountsMultipleRequestsAtSameLocation() {
        GeoPoint loc = new GeoPoint(0.0, 0.0);

        // 3 requests at the same location (same GeoPoint, different IDs).
        RequestDensityIndex index = new RequestDensityIndex(List.of(
                new RideRequest("R1", loc),
                new RideRequest("R2", loc),
                new RideRequest("R3", loc)
        ));

        // All 3 should be counted — multiplicity is preserved.
        int count = index.countNearby(loc, 0.0);
        assertEquals(3, count,
                "multiple requests at the same location must all be counted");
    }

    @Test
    void densityComponentReflectsCountInCost() {
        GeoPoint driverLoc = new GeoPoint(0.0, 0.0);
        GeoPoint pickup    = new GeoPoint(0.0, 0.0); // co-located → ETA = 0

        // Place exactly 5 requests within the density radius.
        List<RideRequest> requests = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> new RideRequest("Req" + i,
                        new GeoPoint(0.0, i * 0.001)))
                .toList();

        // Verify all 5 are actually within 2 km.
        long actualWithin2km = requests.stream()
                .filter(r -> driverLoc.distanceToKm(r.pickupLocation()) <= 2.0)
                .count();

        DispatchCostConfig config = new DispatchCostConfig(
                1.0, 1.0, 1.0, 60.0, 0.5,
                2.0,    // densityRadiusKm
                10.0    // maxDensityCount
        );
        DispatchCostFunction fn = new DispatchCostFunction(config, requests);

        double cost = fn.calculate(
                new Driver("D1", driverLoc, new DriverState()),
                new RideRequest("R0", pickup)
        );

        // ETA = 0, opportunity = 0 → cost = wDensity * (actualWithin2km / 10.0)
        double expectedCost = 1.0 * (actualWithin2km / 10.0);
        assertEquals(expectedCost, cost, 1e-9,
                "density cost must equal wDensity * (count / maxDensityCount)");
    }

    // =========================================================================
    // Opportunity component
    // =========================================================================

    @Test
    void opportunityComponentIsZero() {
        // With co-located driver (ETA=0, density=0), cost must be exactly 0
        // even with wOpportunity = 100, because the opportunity value is 0.0.
        DispatchCostConfig config = new DispatchCostConfig(
                1.0, 1.0, 100.0, 60.0, 0.5, 0.0, 1.0
        );
        DispatchCostFunction fn = new DispatchCostFunction(config, emptyIndex());

        double cost = fn.calculate(driverAt(0.0, 0.0), requestAt(0.0, 0.0));
        assertEquals(0.0, cost, 1e-12,
                "opportunity component must be 0.0 regardless of wOpportunity");
    }

    @Test
    void opportunityWeightCanBeZeroAndProducesIdenticalCost() {
        // Zero opportunity weight is explicitly supported for the deferred term
        DispatchCostConfig zeroOppConfig = new DispatchCostConfig(
                1.0, 1.0, 0.0, 60.0, 0.5, 0.0, 1.0
        );
        DispatchCostConfig nonZeroOppConfig = new DispatchCostConfig(
                1.0, 1.0, 1.0, 60.0, 0.5, 0.0, 1.0
        );

        DispatchCostFunction fnZero = new DispatchCostFunction(zeroOppConfig, emptyIndex());
        DispatchCostFunction fnNonZero = new DispatchCostFunction(nonZeroOppConfig, emptyIndex());

        Driver driver = driverAt(0.1, 0.1);
        RideRequest req = requestAt(0.0, 0.0);

        assertEquals(fnZero.calculate(driver, req), fnNonZero.calculate(driver, req), 1e-12,
                "zero and non-zero wOpportunity must produce identical cost while opportunity term is deferred");
    }

    @Test
    void opportunityWeightRejectsNegativeAndNaN() {
        assertThrows(IllegalArgumentException.class, () ->
                new DispatchCostConfig(1.0, 1.0, -0.01, 60.0, 0.5, 0.0, 1.0));
        assertThrows(IllegalArgumentException.class, () ->
                new DispatchCostConfig(1.0, 1.0, Double.NaN, 60.0, 0.5, 0.0, 1.0));
    }

    // =========================================================================
    // Weight addivity and ordering
    // =========================================================================

    @Test
    void higherEtaWeightProducesHigherCostForFarDriver() {
        // Two configs: same except different wEta. Farther driver has non-zero ETA.
        DispatchCostConfig lowWeight  = new DispatchCostConfig(
                1.0, 1.0, 1.0, 60.0, 0.5, 0.0, 1.0
        );
        DispatchCostConfig highWeight = new DispatchCostConfig(
                5.0, 1.0, 1.0, 60.0, 0.5, 0.0, 1.0
        );

        DispatchCostFunction fnLow  = new DispatchCostFunction(lowWeight,  emptyIndex());
        DispatchCostFunction fnHigh = new DispatchCostFunction(highWeight, emptyIndex());

        Driver  driver  = driverAt(0.0, 0.0);
        RideRequest req = requestAt(0.5, 0.0); // ~55 km away

        double costLow  = fnLow.calculate(driver, req);
        double costHigh = fnHigh.calculate(driver, req);

        assertTrue(costHigh > costLow,
                "higher wEta must produce higher cost when ETA is non-zero");
    }

    @Test
    void higherDensityWeightProducesHigherCostUnderContention() {
        GeoPoint driverLoc = new GeoPoint(0.0, 0.0);
        List<RideRequest> requests = List.of(
                new RideRequest("R1", new GeoPoint(0.0, 0.001))
        );

        DispatchCostConfig lowWeight  = new DispatchCostConfig(
                1.0, 1.0, 1.0, 60.0, 0.5, 1.0, 1.0
        );
        DispatchCostConfig highWeight = new DispatchCostConfig(
                1.0, 5.0, 1.0, 60.0, 0.5, 1.0, 1.0
        );

        // co-located pickup so ETA = 0, only density varies.
        DispatchCostFunction fnLow  = new DispatchCostFunction(lowWeight,  requests);
        DispatchCostFunction fnHigh = new DispatchCostFunction(highWeight, requests);

        Driver  driver  = new Driver("D1", driverLoc, new DriverState());
        RideRequest req = new RideRequest("R0", driverLoc);

        double costLow  = fnLow.calculate(driver, req);
        double costHigh = fnHigh.calculate(driver, req);

        assertTrue(costHigh >= costLow,
                "higher wDensity must produce higher or equal cost when density > 0");
    }

    @Test
    void nearerDriverHasLowerCostThanFarDriver() {
        DispatchCostFunction fn = new DispatchCostFunction(
                DispatchCostConfig.DEFAULT, emptyIndex()
        );
        RideRequest req = requestAt(0.0, 0.0);

        double nearCost = fn.calculate(driverAt(0.0, 0.01), req); // ~1.1 km
        double farCost  = fn.calculate(driverAt(0.0, 1.0), req);  // ~111 km

        assertTrue(nearCost < farCost,
                "nearer driver must have strictly lower cost");
    }

    @Test
    void costsAreFiniteAndNonNegative() {
        DispatchCostFunction fn = new DispatchCostFunction(
                DispatchCostConfig.DEFAULT, emptyIndex()
        );

        for (double latOff : new double[]{0.0, 0.1, 1.0, 5.0}) {
            double cost = fn.calculate(
                    driverAt(0.0, 0.0),
                    requestAt(latOff, 0.0)
            );
            assertTrue(Double.isFinite(cost) && cost >= 0,
                    "cost must be finite and non-negative for latOff=" + latOff);
        }
    }

    // =========================================================================
    // RequestDensityIndex — contract validation
    // =========================================================================

    @Test
    void densityIndexRejectsNullRequestList() {
        assertThrows(NullPointerException.class,
                () -> new RequestDensityIndex(null));
    }

    @Test
    void densityIndexRejectsNullElementInList() {
        assertThrows(NullPointerException.class,
                () -> new RequestDensityIndex(
                        java.util.Arrays.asList(null, null)
                ));
    }

    @Test
    void densityIndexCountNearbyRejectsNullCenter() {
        RequestDensityIndex index = emptyIndex();
        assertThrows(NullPointerException.class,
                () -> index.countNearby(null, 1.0));
    }

    @Test
    void densityIndexCountNearbyRejectsNegativeRadius() {
        RequestDensityIndex index = emptyIndex();
        assertThrows(IllegalArgumentException.class,
                () -> index.countNearby(new GeoPoint(0.0, 0.0), -1.0));
    }

    @Test
    void densityIndexCountNearbyRejectsNaNRadius() {
        RequestDensityIndex index = emptyIndex();
        assertThrows(IllegalArgumentException.class,
                () -> index.countNearby(new GeoPoint(0.0, 0.0), Double.NaN));
    }

    @Test
    void densityIndexEmptyListReturnsZeroCount() {
        RequestDensityIndex index = new RequestDensityIndex(List.of());
        int count = index.countNearby(new GeoPoint(0.0, 0.0), 100.0);
        assertEquals(0, count);
    }

    // =========================================================================
    // DispatchCostFunction — null input rejection
    // =========================================================================

    @Test
    void calculateRejectsNullDriver() {
        DispatchCostFunction fn = new DispatchCostFunction(
                DispatchCostConfig.DEFAULT, emptyIndex()
        );
        assertThrows(NullPointerException.class,
                () -> fn.calculate(null, requestAt(0.0, 0.0)));
    }

    @Test
    void calculateRejectsNullRequest() {
        DispatchCostFunction fn = new DispatchCostFunction(
                DispatchCostConfig.DEFAULT, emptyIndex()
        );
        assertThrows(NullPointerException.class,
                () -> fn.calculate(driverAt(0.0, 0.0), null));
    }

    @Test
    void constructorRejectsNullConfig() {
        assertThrows(NullPointerException.class,
                () -> new DispatchCostFunction(null, emptyIndex()));
    }

    @Test
    void constructorRejectsNullDensityIndex() {
        assertThrows(NullPointerException.class,
                () -> new DispatchCostFunction(DispatchCostConfig.DEFAULT, (RequestDensityIndex) null));
    }

    // =========================================================================
    // Integration with GreedyMatcher
    // =========================================================================

    @Test
    void greedyMatcherUsesDispatchCostFunctionCorrectly() {
        // Two drivers: near and far. DispatchCostFunction should prefer the near one.
        Driver near = new Driver("NEAR", new GeoPoint(0.0, 0.01), new DriverState());
        Driver far  = new Driver("FAR",  new GeoPoint(0.0, 1.00), new DriverState());
        RideRequest request = new RideRequest("R1", new GeoPoint(0.0, 0.0));

        DispatchCostFunction costFn = new DispatchCostFunction(
                DispatchCostConfig.DEFAULT,
                List.of(request)
        );

        GreedyMatcher matcher = new GreedyMatcher();
        List<com.aagosh.dispatch.domain.Assignment> assignments =
                matcher.match(List.of(request), List.of(near, far), costFn);

        assertEquals(1, assignments.size());
        assertEquals("NEAR", assignments.get(0).driver().id(),
                "GreedyMatcher must assign the nearer driver");
    }

    @Test
    void greedyMatcherWithDispatchCostFunctionFallsBackWhenNearestClaimed() {
        Driver near = new Driver("NEAR", new GeoPoint(0.0, 0.01), new DriverState());
        Driver far  = new Driver("FAR",  new GeoPoint(0.0, 1.00), new DriverState());
        assertTrue(near.state().tryClaim("OTHER"), "pre-claim near driver");

        RideRequest request = new RideRequest("R1", new GeoPoint(0.0, 0.0));

        DispatchCostFunction costFn = new DispatchCostFunction(
                DispatchCostConfig.DEFAULT,
                List.of(request)
        );

        GreedyMatcher matcher = new GreedyMatcher();
        List<com.aagosh.dispatch.domain.Assignment> assignments =
                matcher.match(List.of(request), List.of(near, far), costFn);

        assertEquals(1, assignments.size());
        assertEquals("FAR", assignments.get(0).driver().id(),
                "GreedyMatcher must fall back to the next available driver");
    }

    @Test
    void assignmentCostIsFiniteAndNonNegative() {
        Driver driver   = driverAt(17.0, 78.0);
        RideRequest req = requestAt(17.5, 78.5);

        DispatchCostFunction costFn = new DispatchCostFunction(
                DispatchCostConfig.DEFAULT,
                List.of(req)
        );

        GreedyMatcher matcher = new GreedyMatcher();
        List<com.aagosh.dispatch.domain.Assignment> assignments =
                matcher.match(List.of(req), List.of(driver), costFn);

        assertEquals(1, assignments.size());
        double cost = assignments.get(0).cost();
        assertTrue(Double.isFinite(cost) && cost >= 0,
                "assignment cost must be finite and non-negative, got: " + cost);
    }
}
