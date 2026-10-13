package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.domain.MatchStatus;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GreedyMatcherTest {

    private final GreedyMatcher matcher = new GreedyMatcher();


    @Test
    void concurrentRequestsCannotClaimTheSameDriver() throws Exception {
        Driver driver = new Driver(
                "D1",
                new GeoPoint(17.0, 78.0),
                new DriverState()
        );

        RideRequest request1 = new RideRequest(
                "R1", new GeoPoint(17.001, 78.0)
        );
        RideRequest request2 = new RideRequest(
                "R2", new GeoPoint(17.002, 78.0)
        );

        CostFunction costFunction = (d, r) ->
                d.location().distanceToKm(r.pickupLocation());

        GreedyMatcher matcher1 = new GreedyMatcher();
        GreedyMatcher matcher2 = new GreedyMatcher();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<List<Assignment>> future1 = executor.submit(() -> {
                ready.countDown();
                start.await();
                return matcher1.match(
                        List.of(request1), List.of(driver), costFunction
                );
            });

            Future<List<Assignment>> future2 = executor.submit(() -> {
                ready.countDown();
                start.await();
                return matcher2.match(
                        List.of(request2), List.of(driver), costFunction
                );
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<Assignment> result1 = future1.get(5, TimeUnit.SECONDS);
            List<Assignment> result2 = future2.get(5, TimeUnit.SECONDS);

            assertEquals(
                    1,
                    result1.size() + result2.size(),
                    "Exactly one request should claim the driver"
            );

            assertEquals(
                    MatchStatus.CLAIMED,
                    driver.state().getStatus()
            );

            String claimedBy = driver.state().getClaimedByRequestId();
            assertTrue(
                    claimedBy.equals("R1") || claimedBy.equals("R2")
            );
        } finally {
            executor.shutdownNow();
        }
    }


    @Test
    void fallsBackWhenNearestDriverIsAlreadyClaimed() {
        GeoPoint pickup = new GeoPoint(17.0, 78.0);

        Driver nearest = new Driver(
                "D1", new GeoPoint(17.001, 78.0), new DriverState()
        );
        Driver secondNearest = new Driver(
                "D2", new GeoPoint(17.002, 78.0), new DriverState()
        );

        assertTrue(nearest.state().tryClaim("OTHER_REQUEST"));

        RideRequest request = new RideRequest("R1", pickup);

        List<Assignment> result = matcher.match(
                List.of(request),
                List.of(nearest, secondNearest),
                (driver, ride) ->
                        driver.location().distanceToKm(ride.pickupLocation())
        );

        assertEquals(1, result.size());
        assertEquals("D2", result.get(0).driver().id());
        assertEquals("R1", secondNearest.state().getClaimedByRequestId());
    }

    @Test
    void leavesRequestUnmatchedWhenEveryDriverIsClaimed() {
        Driver driver = new Driver(
                "D1", new GeoPoint(17.0, 78.0), new DriverState()
        );

        assertTrue(driver.state().tryClaim("OTHER_REQUEST"));

        RideRequest request = new RideRequest(
                "R1", new GeoPoint(17.001, 78.0)
        );

        List<Assignment> result = matcher.match(
                List.of(request),
                List.of(driver),
                (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void doesNotAssignOneDriverToTwoRequests() {
        Driver driver = new Driver(
                "D1", new GeoPoint(17.0, 78.0), new DriverState()
        );

        List<RideRequest> requests = List.of(
                new RideRequest("R1", new GeoPoint(17.001, 78.0)),
                new RideRequest("R2", new GeoPoint(17.002, 78.0))
        );

        List<Assignment> result = matcher.match(
                requests,
                List.of(driver),
                (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        assertEquals(1, result.size());
        assertEquals("R1", result.get(0).request().id());
    }

    /**
     * Forces the progressive window to expand: the 17 nearest locations are
     * all pre-claimed, so the matcher must go past the initial INITIAL_WINDOW
     * (16) to find the 18th driver.
     */
    @Test
    void expandsWindowWhenInitialCandidatesAreAllClaimed() {
        GeoPoint pickup = new GeoPoint(17.0, 78.0);

        // 17 drivers at incrementally farther locations, all pre-claimed.
        List<Driver> preClaimed = new ArrayList<>();
        for (int i = 1; i <= 17; i++) {
            Driver d = new Driver(
                    "D" + i,
                    new GeoPoint(17.0 + i * 0.001, 78.0),
                    new DriverState()
            );
            assertTrue(d.state().tryClaim("OTHER"), "pre-claim D" + i);
            preClaimed.add(d);
        }

        // 18th driver — just beyond the initial window — is available.
        Driver available = new Driver(
                "D18",
                new GeoPoint(17.018, 78.0),
                new DriverState()
        );

        List<Driver> allDrivers = new ArrayList<>(preClaimed);
        allDrivers.add(available);

        RideRequest request = new RideRequest("R1", pickup);

        List<Assignment> result = matcher.match(
                List.of(request),
                allDrivers,
                (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        assertEquals(1, result.size(), "should find the 18th driver");
        assertEquals("D18", result.get(0).driver().id());
    }

    /**
     * Large fleet (100 unique locations), only the 50th-nearest is available.
     * The progressive window must expand far enough to reach it without missing it.
     */
    @Test
    void findsDistantAvailableDriverInLargeFleet() {
        GeoPoint pickup = new GeoPoint(0.0, 0.0);

        List<Driver> drivers = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            Driver d = new Driver(
                    "D" + i,
                    new GeoPoint(0.0, i * 0.01),
                    new DriverState()
            );
            // Pre-claim all except the 50th.
            if (i != 50) {
                assertTrue(d.state().tryClaim("OTHER"), "pre-claim D" + i);
            }
            drivers.add(d);
        }

        RideRequest request = new RideRequest("R1", pickup);

        List<Assignment> result = matcher.match(
                List.of(request),
                drivers,
                (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        assertEquals(1, result.size());
        assertEquals("D50", result.get(0).driver().id());
    }

    /**
     * Multiple requests served in nearest-first order: each request claims the
     * nearest still-available driver, exhausting the fleet one by one.
     */
    @Test
    void servesMultipleRequestsNearestFirstUnderContention() {
        // 5 drivers at increasing distances from the pickup.
        List<Driver> drivers = List.of(
                new Driver("D1", new GeoPoint(0.0, 0.01), new DriverState()),
                new Driver("D2", new GeoPoint(0.0, 0.02), new DriverState()),
                new Driver("D3", new GeoPoint(0.0, 0.03), new DriverState()),
                new Driver("D4", new GeoPoint(0.0, 0.04), new DriverState()),
                new Driver("D5", new GeoPoint(0.0, 0.05), new DriverState())
        );

        // 5 requests from the same pickup location.
        List<RideRequest> requests = List.of(
                new RideRequest("R1", new GeoPoint(0.0, 0.0)),
                new RideRequest("R2", new GeoPoint(0.0, 0.0)),
                new RideRequest("R3", new GeoPoint(0.0, 0.0)),
                new RideRequest("R4", new GeoPoint(0.0, 0.0)),
                new RideRequest("R5", new GeoPoint(0.0, 0.0))
        );

        List<Assignment> result = matcher.match(
                requests,
                drivers,
                (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        assertEquals(5, result.size(), "all 5 requests must be matched");

        // Verify nearest-first assignment order.
        List<String> driverOrder = result.stream()
                .map(a -> a.driver().id())
                .toList();
        assertEquals(List.of("D1", "D2", "D3", "D4", "D5"), driverOrder);
    }

    @Test
    void supportsMultipleDriversAtTheSameLocation() {
        GeoPoint location = new GeoPoint(17.0, 78.0);

        Driver first = new Driver("D1", location, new DriverState());
        Driver second = new Driver("D2", location, new DriverState());

        assertTrue(first.state().tryClaim("OTHER_REQUEST"));

        RideRequest request = new RideRequest("R1", location);

        List<Assignment> result = matcher.match(
                List.of(request),
                List.of(first, second),
                (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        assertEquals(1, result.size());
        assertEquals("D2", result.get(0).driver().id());
    }

    @Test
    void customCostFunctionChangesWhichDriverIsSelected() {
        GeoPoint pickup = new GeoPoint(17.0, 78.0);
        // Near driver geographically (0.001 deg away)
        Driver nearDriver = new Driver("NEAR", new GeoPoint(17.001, 78.0), new DriverState());
        // Farther driver geographically (0.005 deg away)
        Driver farDriver = new Driver("FAR", new GeoPoint(17.005, 78.0), new DriverState());

        RideRequest request = new RideRequest("R1", pickup);

        // CostFunction assigns NEAR a high cost (e.g. traffic/rating) and FAR a low cost
        CostFunction businessCost = (driver, req) -> {
            if ("NEAR".equals(driver.id())) {
                return 50.0;
            } else {
                return 2.0;
            }
        };

        List<Assignment> result = matcher.match(
                List.of(request),
                List.of(nearDriver, farDriver),
                businessCost
        );

        assertEquals(1, result.size());
        assertEquals("FAR", result.get(0).driver().id(),
                "Matcher must select the driver with lowest business cost within candidate window");
        assertEquals(2.0, result.get(0).cost(), 1e-9);
    }

    @Test
    void geographicProximityDoesNotOverrideLowerValidBusinessCost() {
        GeoPoint pickup = new GeoPoint(0.0, 0.0);
        Driver d1 = new Driver("D1", new GeoPoint(0.0, 0.01), new DriverState());
        Driver d2 = new Driver("D2", new GeoPoint(0.0, 0.02), new DriverState());
        Driver d3 = new Driver("D3", new GeoPoint(0.0, 0.03), new DriverState());

        RideRequest req = new RideRequest("R1", pickup);

        // D2 has lowest business cost despite being farther than D1
        CostFunction costFn = (d, r) -> switch (d.id()) {
            case "D1" -> 15.0;
            case "D2" -> 3.0;
            case "D3" -> 25.0;
            default -> 100.0;
        };

        List<Assignment> result = matcher.match(List.of(req), List.of(d1, d2, d3), costFn);

        assertEquals(1, result.size());
        assertEquals("D2", result.get(0).driver().id(), "Must choose D2 with lowest cost");
        assertEquals(3.0, result.get(0).cost(), 1e-9);
    }

    @Test
    void failedClaimCausesMatcherToTryNextCandidateInCostOrder() {
        GeoPoint pickup = new GeoPoint(0.0, 0.0);
        Driver bestCost = new Driver("BEST", new GeoPoint(0.0, 0.01), new DriverState());
        Driver secondBest = new Driver("SECOND", new GeoPoint(0.0, 0.02), new DriverState());

        // Pre-claim the best cost driver
        assertTrue(bestCost.state().tryClaim("CLAIMED_EXTERNALLY"));

        RideRequest req = new RideRequest("R1", pickup);

        CostFunction costFn = (d, r) -> "BEST".equals(d.id()) ? 1.0 : 5.0;

        List<Assignment> result = matcher.match(List.of(req), List.of(bestCost, secondBest), costFn);

        assertEquals(1, result.size());
        assertEquals("SECOND", result.get(0).driver().id(),
                "Matcher must proceed to next best candidate when best cannot be claimed");
        assertEquals(5.0, result.get(0).cost(), 1e-9);
    }

    @Test
    void equalCostResultsAreDeterministicRegardlessOfInputOrder() {
        GeoPoint pickup = new GeoPoint(0.0, 0.0);
        GeoPoint sameLoc = new GeoPoint(0.0, 0.01);
        Driver dA = new Driver("A_DRIVER", sameLoc, new DriverState());
        Driver dB = new Driver("B_DRIVER", sameLoc, new DriverState());

        RideRequest req = new RideRequest("R1", pickup);
        CostFunction constantCost = (d, r) -> 10.0;

        List<Assignment> res1 = matcher.match(List.of(req), List.of(dA, dB), constantCost);
        assertEquals("A_DRIVER", res1.get(0).driver().id());

        // Reset drivers
        Driver dA2 = new Driver("A_DRIVER", sameLoc, new DriverState());
        Driver dB2 = new Driver("B_DRIVER", sameLoc, new DriverState());

        // Pass in reverse order: [dB2, dA2]
        List<Assignment> res2 = matcher.match(List.of(req), List.of(dB2, dA2), constantCost);
        assertEquals("A_DRIVER", res2.get(0).driver().id(),
                "Tie-breaking must deterministically select A_DRIVER based on stable ID tie-breaker");
    }

    @Test
    void rejectsInvalidCostsAccordingToContract() {
        Driver driver = new Driver("D1", new GeoPoint(0.0, 0.01), new DriverState());
        RideRequest req = new RideRequest("R1", new GeoPoint(0.0, 0.0));

        // NaN cost
        assertThrows(IllegalArgumentException.class, () ->
                matcher.match(List.of(req), List.of(driver), (d, r) -> Double.NaN));

        // Positive infinity cost
        assertThrows(IllegalArgumentException.class, () ->
                matcher.match(List.of(req), List.of(driver), (d, r) -> Double.POSITIVE_INFINITY));

        // Negative infinity cost
        assertThrows(IllegalArgumentException.class, () ->
                matcher.match(List.of(req), List.of(driver), (d, r) -> Double.NEGATIVE_INFINITY));

        // Negative cost
        assertThrows(IllegalArgumentException.class, () ->
                matcher.match(List.of(req), List.of(driver), (d, r) -> -0.01));
    }

    @Test
    void handlesEmptyAndNullInputsAccordingToContract() {
        Driver driver = new Driver("D1", new GeoPoint(0.0, 0.01), new DriverState());
        RideRequest req = new RideRequest("R1", new GeoPoint(0.0, 0.0));
        CostFunction costFn = (d, r) -> 1.0;

        // Empty requests
        assertTrue(matcher.match(List.of(), List.of(driver), costFn).isEmpty());

        // Empty drivers
        assertTrue(matcher.match(List.of(req), List.of(), costFn).isEmpty());

        // Null checks
        assertThrows(NullPointerException.class, () -> matcher.match(null, List.of(driver), costFn));
        assertThrows(NullPointerException.class, () -> matcher.match(List.of(req), null, costFn));
        assertThrows(NullPointerException.class, () -> matcher.match(List.of(req), List.of(driver), null));
    }

    @Test
    void progressiveWindowTradeOffPrioritizesLocalCandidateSetOverFartherLowerCostDriver() {
        GeoPoint pickup = new GeoPoint(0.0, 0.0);
        RideRequest req = new RideRequest("R1", pickup);

        // Create 16 local drivers at distinct locations (filling the initial window of 16)
        List<Driver> localDrivers = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            localDrivers.add(new Driver("LOCAL_" + i, new GeoPoint(0.0, 0.001 * i), new DriverState()));
        }

        // Create a 17th driver positioned farther away (in window 2: 17..64)
        Driver farDriver = new Driver("FAR_LOW_COST", new GeoPoint(0.0, 0.5), new DriverState());

        List<Driver> allDrivers = new ArrayList<>(localDrivers);
        allDrivers.add(farDriver);

        // Business cost function where the far driver has cost 1.0, but all local drivers have cost 10.0
        CostFunction costFn = (d, r) -> "FAR_LOW_COST".equals(d.id()) ? 1.0 : 10.0;

        // Trade-off Part A: Because local drivers are available in window 1, the matcher evaluates only window 1
        // and assigns a local driver (cost 10.0), avoiding a global fleet scan.
        List<Assignment> resultA = matcher.match(List.of(req), allDrivers, costFn);
        assertEquals(1, resultA.size());
        assertEquals(10.0, resultA.get(0).cost(), 1e-9);
        assertTrue(resultA.get(0).driver().id().startsWith("LOCAL_"),
                "Intentional trade-off: GreedyMatcher spatial pre-filter bounds candidate search to local window");

        // Reset drivers
        List<Driver> localDriversB = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            Driver d = new Driver("LOCAL_" + i, new GeoPoint(0.0, 0.001 * i), new DriverState());
            assertTrue(d.state().tryClaim("CLAIMED_OTHER")); // make unavailable
            localDriversB.add(d);
        }
        Driver farDriverB = new Driver("FAR_LOW_COST", new GeoPoint(0.0, 0.5), new DriverState());
        List<Driver> allDriversB = new ArrayList<>(localDriversB);
        allDriversB.add(farDriverB);

        // Trade-off Part B: When all 16 local drivers are claimed/unavailable, the matcher expands window to 64
        // and successfully claims the farther driver.
        List<Assignment> resultB = matcher.match(List.of(req), allDriversB, costFn);
        assertEquals(1, resultB.size());
        assertEquals("FAR_LOW_COST", resultB.get(0).driver().id(),
                "When local window is exhausted, window expansion finds the farther candidate");
        assertEquals(1.0, resultB.get(0).cost(), 1e-9);
    }
}
