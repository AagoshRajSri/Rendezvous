package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests exercising the real dispatch components together:
 * domain entities, {@link RequestDensityIndex}, {@link DispatchCostFunction},
 * {@link AuctionMatcher}, {@link GreedyMatcher}, and atomic {@link DriverState} claims.
 */
class DispatchIntegrationTest {

    private final AuctionMatcher auctionMatcher = new AuctionMatcher();
    private final GreedyMatcher greedyMatcher = new GreedyMatcher();

    // -------------------------------------------------------------------------
    // AuctionMatcher integration tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Auction pipeline assigns balanced fleet, minimizes cost, and claims drivers atomically")
    void auctionPipelineBalancedFleet() {
        // 3 requests across Hyderabad
        RideRequest r1 = new RideRequest("R1", new GeoPoint(17.3850, 78.4867));
        RideRequest r2 = new RideRequest("R2", new GeoPoint(17.4400, 78.3489));
        RideRequest r3 = new RideRequest("R3", new GeoPoint(17.4000, 78.4500));
        List<RideRequest> requests = List.of(r1, r2, r3);

        // 3 available drivers
        Driver d1 = new Driver("D1", new GeoPoint(17.3860, 78.4870), new DriverState());
        Driver d2 = new Driver("D2", new GeoPoint(17.4410, 78.3490), new DriverState());
        Driver d3 = new Driver("D3", new GeoPoint(17.4010, 78.4510), new DriverState());
        List<Driver> drivers = List.of(d1, d2, d3);

        // Real request-density snapshot and cost function
        RequestDensityIndex densityIndex = new RequestDensityIndex(requests);
        DispatchCostFunction costFn = new DispatchCostFunction(DispatchCostConfig.DEFAULT, densityIndex);

        List<Assignment> assignments = auctionMatcher.match(requests, drivers, costFn);

        // 1. Feasibility: exactly 3 assignments
        assertEquals(3, assignments.size(), "All 3 requests must be matched");

        Set<String> matchedRequests = new HashSet<>();
        Set<String> matchedDrivers = new HashSet<>();
        double totalCost = 0.0;

        for (Assignment a : assignments) {
            matchedRequests.add(a.request().id());
            matchedDrivers.add(a.driver().id());
            totalCost += a.cost();

            // 2. Cost correspondence: evaluated cost equals assignment cost
            double expectedCost = costFn.calculate(a.driver(), a.request());
            assertEquals(expectedCost, a.cost(), 1e-12, "Reported cost must match CostFunction output");

            // 3. Driver atomic state check
            assertEquals(MatchStatus.CLAIMED, a.driver().state().getStatus(), "Assigned driver must be CLAIMED");
            assertEquals(a.request().id(), a.driver().state().getClaimedByRequestId(),
                    "Driver must be claimed by the assigned request");
        }

        // 4. Uniqueness
        assertEquals(3, matchedRequests.size(), "Each request must appear at most once");
        assertEquals(3, matchedDrivers.size(), "Each driver must appear at most once");
        assertTrue(totalCost > 0.0, "Total dispatch cost must be positive and finite");
    }

    @Test
    @DisplayName("2. Auction pipeline excludes pre-claimed drivers from assignment")
    void auctionPipelineExcludesUnavailableDrivers() {
        RideRequest r1 = new RideRequest("R1", new GeoPoint(17.3850, 78.4867));
        RideRequest r2 = new RideRequest("R2", new GeoPoint(17.4400, 78.3489));
        List<RideRequest> requests = List.of(r1, r2);

        Driver unavailable = new Driver("D_UNAVAIL", new GeoPoint(17.3850, 78.4867), new DriverState());
        assertTrue(unavailable.state().tryClaim("PRIOR_JOB"), "Pre-claim driver");

        Driver available = new Driver("D_AVAIL", new GeoPoint(17.4400, 78.3489), new DriverState());
        List<Driver> drivers = List.of(unavailable, available);

        DispatchCostFunction costFn = new DispatchCostFunction(DispatchCostConfig.DEFAULT, List.of(r1, r2));

        List<Assignment> assignments = auctionMatcher.match(requests, drivers, costFn);

        assertEquals(1, assignments.size(), "Only 1 available driver can be assigned");
        assertEquals("D_AVAIL", assignments.get(0).driver().id());
        assertEquals("PRIOR_JOB", unavailable.state().getClaimedByRequestId(), "Unavailable driver must remain untouched");
    }

    @Test
    @DisplayName("3. Auction pipeline handles more requests than drivers (R > D)")
    void auctionPipelineMoreRequestsThanDrivers() {
        RideRequest r1 = new RideRequest("R1", new GeoPoint(17.3850, 78.4867));
        RideRequest r2 = new RideRequest("R2", new GeoPoint(17.4400, 78.3489));
        RideRequest r3 = new RideRequest("R3", new GeoPoint(17.5000, 78.4000));
        List<RideRequest> requests = List.of(r1, r2, r3);

        Driver d1 = new Driver("D1", new GeoPoint(17.3860, 78.4870), new DriverState());
        Driver d2 = new Driver("D2", new GeoPoint(17.4410, 78.3490), new DriverState());
        List<Driver> drivers = List.of(d1, d2);

        DispatchCostFunction costFn = new DispatchCostFunction(DispatchCostConfig.DEFAULT, requests);

        List<Assignment> assignments = auctionMatcher.match(requests, drivers, costFn);

        assertEquals(2, assignments.size(), "Bounded by driver count min(R, D) = 2");
        assertEquals(MatchStatus.CLAIMED, d1.state().getStatus());
        assertEquals(MatchStatus.CLAIMED, d2.state().getStatus());
    }

    @Test
    @DisplayName("4. Auction pipeline handles more drivers than requests (D > R)")
    void auctionPipelineMoreDriversThanRequests() {
        RideRequest r1 = new RideRequest("R1", new GeoPoint(17.3850, 78.4867));
        List<RideRequest> requests = List.of(r1);

        Driver d1 = new Driver("D1", new GeoPoint(17.3860, 78.4870), new DriverState());
        Driver d2 = new Driver("D2", new GeoPoint(17.5000, 78.5000), new DriverState());
        Driver d3 = new Driver("D3", new GeoPoint(18.0000, 79.0000), new DriverState());
        List<Driver> drivers = List.of(d1, d2, d3);

        DispatchCostFunction costFn = new DispatchCostFunction(DispatchCostConfig.DEFAULT, requests);

        List<Assignment> assignments = auctionMatcher.match(requests, drivers, costFn);

        assertEquals(1, assignments.size());
        assertEquals("R1", assignments.get(0).request().id());
        assertEquals("D1", assignments.get(0).driver().id(), "Should assign nearest/lowest-cost driver D1");

        // Exactly one driver claimed; others remain available
        assertEquals(MatchStatus.CLAIMED, d1.state().getStatus());
        assertEquals(MatchStatus.AVAILABLE, d2.state().getStatus());
        assertEquals(MatchStatus.AVAILABLE, d3.state().getStatus());
    }

    @Test
    @DisplayName("5. Auction pipeline drops tentative match if external actor claims driver before commit")
    void auctionPipelineHandlesConcurrentClaimInterception() {
        RideRequest r1 = new RideRequest("R1", new GeoPoint(17.3850, 78.4867));
        Driver d1 = new Driver("D1", new GeoPoint(17.3850, 78.4867), new DriverState());

        // Custom cost function that intercepts driver state right after calculating cost
        CostFunction interceptingCostFn = (driver, req) -> {
            // Simulate external concurrent thread claiming the driver before commitAssignments runs
            driver.state().tryClaim("EXTERNAL_INTERCEPTOR");
            return 1.0;
        };

        List<Assignment> assignments = auctionMatcher.match(List.of(r1), List.of(d1), interceptingCostFn);

        assertTrue(assignments.isEmpty(),
                "If atomic tryClaim fails at commit time, assignment must be dropped safely");
        assertEquals("EXTERNAL_INTERCEPTOR", d1.state().getClaimedByRequestId());
    }

    @Test
    @DisplayName("5b. Auction pipeline commits remaining non-contended matches when one claim fails")
    void auctionPipelineDoesNotAbortBatchWhenOneClaimFails() {
        RideRequest r1 = new RideRequest("R1", new GeoPoint(17.3850, 78.4867));
        RideRequest r2 = new RideRequest("R2", new GeoPoint(17.4400, 78.3489));
        List<RideRequest> requests = List.of(r1, r2);

        Driver d1 = new Driver("D1", new GeoPoint(17.3850, 78.4867), new DriverState());
        Driver d2 = new Driver("D2", new GeoPoint(17.4400, 78.3489), new DriverState());
        List<Driver> drivers = List.of(d1, d2);

        // Custom cost function where only D1 is intercepted by an external thread before commit
        CostFunction interceptingCostFn = (driver, req) -> {
            if ("D1".equals(driver.id())) {
                driver.state().tryClaim("EXTERNAL_INTERCEPTOR");
            }
            return 1.0;
        };

        List<Assignment> assignments = auctionMatcher.match(requests, drivers, interceptingCostFn);

        // Crucial verification: The batch is NOT aborted!
        assertEquals(1, assignments.size(), "Non-contended assignment must still succeed");
        assertEquals("D2", assignments.get(0).driver().id(), "Driver D2 must be successfully committed");
        assertEquals("EXTERNAL_INTERCEPTOR", d1.state().getClaimedByRequestId(), "D1 was intercepted");
        assertEquals(MatchStatus.CLAIMED, d2.state().getStatus(), "D2 status must be CLAIMED");
    }

    // -------------------------------------------------------------------------
    // GreedyMatcher integration tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. Greedy pipeline matches requests using DispatchCostFunction")
    void greedyPipelineWithDispatchCostFunction() {
        RideRequest r1 = new RideRequest("R1", new GeoPoint(17.3850, 78.4867));
        RideRequest r2 = new RideRequest("R2", new GeoPoint(17.4400, 78.3489));
        List<RideRequest> requests = List.of(r1, r2);

        Driver d1 = new Driver("D1", new GeoPoint(17.3860, 78.4870), new DriverState());
        Driver d2 = new Driver("D2", new GeoPoint(17.4410, 78.3490), new DriverState());
        List<Driver> drivers = List.of(d1, d2);

        DispatchCostFunction costFn = new DispatchCostFunction(DispatchCostConfig.DEFAULT, requests);

        List<Assignment> assignments = greedyMatcher.match(requests, drivers, costFn);

        assertEquals(2, assignments.size());
        for (Assignment a : assignments) {
            assertEquals(MatchStatus.CLAIMED, a.driver().state().getStatus());
            double expected = costFn.calculate(a.driver(), a.request());
            assertEquals(expected, a.cost(), 1e-12);
        }
    }

    @Test
    @DisplayName("7. Greedy pipeline respects business cost over pure proximity within spatial window")
    void greedyPipelineRespectsBusinessCostOverProximity() {
        RideRequest req = new RideRequest("R1", new GeoPoint(0.0, 0.0));

        // Two drivers in the initial spatial window (both within 0.05 deg)
        Driver nearHighCost = new Driver("D_NEAR", new GeoPoint(0.0, 0.01), new DriverState());
        Driver farLowCost   = new Driver("D_FAR",  new GeoPoint(0.0, 0.04), new DriverState());

        // Custom cost model where D_FAR has lower business cost
        CostFunction costFn = (d, r) -> "D_FAR".equals(d.id()) ? 1.0 : 100.0;

        List<Assignment> assignments = greedyMatcher.match(List.of(req), List.of(nearHighCost, farLowCost), costFn);

        assertEquals(1, assignments.size());
        assertEquals("D_FAR", assignments.get(0).driver().id(),
                "GreedyMatcher must select lowest-cost candidate within the spatial candidate window");
        assertEquals(1.0, assignments.get(0).cost(), 1e-12);
        assertEquals(MatchStatus.CLAIMED, farLowCost.state().getStatus());
        assertEquals(MatchStatus.AVAILABLE, nearHighCost.state().getStatus());
    }

    @Test
    @DisplayName("8. Empty fleets and unavailable fleets return empty results cleanly for both matchers")
    void emptyAndUnavailableFleetsProduceNoAssignments() {
        RideRequest req = new RideRequest("R1", new GeoPoint(17.0, 78.0));
        Driver driver = new Driver("D1", new GeoPoint(17.0, 78.0), new DriverState());
        assertTrue(driver.state().tryClaim("BUSY"));

        DispatchCostFunction costFn = new DispatchCostFunction(DispatchCostConfig.DEFAULT, List.of(req));

        // Empty requests
        assertTrue(auctionMatcher.match(List.of(), List.of(driver), costFn).isEmpty());
        assertTrue(greedyMatcher.match(List.of(), List.of(driver), costFn).isEmpty());

        // Empty drivers
        assertTrue(auctionMatcher.match(List.of(req), List.of(), costFn).isEmpty());
        assertTrue(greedyMatcher.match(List.of(req), List.of(), costFn).isEmpty());

        // All drivers unavailable
        assertTrue(auctionMatcher.match(List.of(req), List.of(driver), costFn).isEmpty());
        assertTrue(greedyMatcher.match(List.of(req), List.of(driver), costFn).isEmpty());
    }
}
