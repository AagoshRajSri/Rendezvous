package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AuctionMatcher}.
 *
 * <h2>Independent Hungarian baseline</h2>
 * <p>The inner class {@link Hungarian} implements the O(n³) Hungarian
 * algorithm (Kuhn-Munkres) independently of the Auction Algorithm. It solves
 * the same cost-minimization problem on the same cost matrix and is used as
 * the ground-truth reference for randomized differential tests.
 *
 * <h2>Tolerance rationale</h2>
 * <p>The Auction Algorithm with epsilon {@code ε} produces a total assignment
 * cost within {@code n × ε} of the optimal, where {@code n = min(R, D)}.
 * With the default {@code ε = 1e-6} and test batches up to 20 requests,
 * the worst-case gap is {@code 20 × 1e-6 = 2e-5}. Tests use a tolerance of
 * {@code 1e-4} to provide a comfortable margin above this theoretical bound.
 */
class AuctionMatcherTest {

    // =========================================================================
    // Hungarian algorithm - independent reference implementation
    // =========================================================================

    /**
     * Pure O(n³) Hungarian algorithm for cost minimization.
     *
     * <p>This implementation is completely independent of {@link AuctionMatcher}.
     * It does not reuse any bidding, pricing, or displacement logic.
     *
     * <p>For rectangular matrices (R ≠ D):
     * <ul>
     *   <li>If {@code R > D}: some requests will be unmatched. The algorithm
     *       finds the best assignment for {@code D} requests.</li>
     *   <li>If {@code D > R}: the algorithm assigns all {@code R} requests,
     *       one driver each.</li>
     * </ul>
     * Unmatched slots are represented by padding the shorter dimension with
     * zero-cost dummy entries, then discarding those assignments at the end.
     */
    static final class Hungarian {

        /**
         * Solves the cost-minimization assignment problem for the given cost
         * matrix.
         *
         * @param cost cost[i][j] = cost of assigning request {@code i} to
         *             driver {@code j}; must be finite and non-negative
         * @return assignment[i] = j means request i is assigned to driver j;
         *         assignment[i] = -1 means request i is unmatched (when R > D)
         */
        static int[] solve(double[][] cost) {
            int R = cost.length;
            if (R == 0) return new int[0];
            int D = cost[0].length;
            if (D == 0) {
                int[] none = new int[R];
                Arrays.fill(none, -1);
                return none;
            }

            int n = Math.max(R, D);

            // Pad to a square n×n matrix. Rows beyond R or columns beyond D
            // are dummy slots with cost 0.
            double[][] square = new double[n][n];
            for (int i = 0; i < R; i++) {
                System.arraycopy(cost[i], 0, square[i], 0, D);
                // dummy driver columns get cost 0 (unmatched penalty = 0)
                for (int j = D; j < n; j++) {
                    square[i][j] = 0;
                }
            }
            // dummy request rows get cost 0
            for (int i = R; i < n; i++) {
                Arrays.fill(square[i], 0.0);
            }

            int[] assignment = hungarianSquare(square, n);

            // Extract only real request→driver pairs, mark unmatched.
            int[] result = new int[R];
            for (int i = 0; i < R; i++) {
                int j = assignment[i];
                result[i] = (j < D) ? j : -1; // j >= D means unmatched dummy
            }
            return result;
        }

        /**
         * Solves the n×n assignment problem using the Hungarian algorithm.
         * Returns assignment[i] = j for row i.
         */
        private static int[] hungarianSquare(double[][] a, int n) {
            // Row and column potential (dual variables).
            double[] u = new double[n + 1];
            double[] v = new double[n + 1];
            // p[j] = row assigned to column j (1-based; 0 = unassigned).
            int[] p = new int[n + 1];
            // way[j] = the column that led to column j in the augmenting path.
            int[] way = new int[n + 1];

            for (int i = 1; i <= n; i++) {
                p[0] = i;
                int j0 = 0;
                double[] minVal = new double[n + 1];
                boolean[] used = new boolean[n + 1];
                Arrays.fill(minVal, Double.MAX_VALUE);

                do {
                    used[j0] = true;
                    int i0 = p[j0];
                    double delta = Double.MAX_VALUE;
                    int j1 = -1;

                    for (int j = 1; j <= n; j++) {
                        if (!used[j]) {
                            // a is 0-indexed; potentials are 1-indexed.
                            double cur = a[i0 - 1][j - 1] - u[i0] - v[j];
                            if (cur < minVal[j]) {
                                minVal[j] = cur;
                                way[j] = j0;
                            }
                            if (minVal[j] < delta) {
                                delta = minVal[j];
                                j1 = j;
                            }
                        }
                    }

                    for (int j = 0; j <= n; j++) {
                        if (used[j]) {
                            u[p[j]] += delta;
                            v[j] -= delta;
                        } else {
                            minVal[j] -= delta;
                        }
                    }

                    j0 = j1;
                } while (p[j0] != 0);

                do {
                    int j1 = way[j0];
                    p[j0] = p[j1];
                    j0 = j1;
                } while (j0 != 0);
            }

            // Convert to 0-indexed assignment array.
            int[] ans = new int[n];
            for (int j = 1; j <= n; j++) {
                if (p[j] != 0) {
                    ans[p[j] - 1] = j - 1;
                }
            }
            return ans;
        }

        /** Computes total cost for an assignment vector. */
        static double totalCost(double[][] cost, int[] assignment) {
            double total = 0;
            for (int i = 0; i < assignment.length; i++) {
                int j = assignment[i];
                if (j >= 0) {
                    total += cost[i][j];
                }
            }
            return total;
        }
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    private static final double TOLERANCE = 1e-4;

    private static Driver availableDriver(String id, double lat, double lon) {
        return new Driver(id, new GeoPoint(lat, lon), new DriverState());
    }

    private static RideRequest request(String id, double lat, double lon) {
        return new RideRequest(id, new GeoPoint(lat, lon));
    }

    /** CostFunction backed by a pre-built matrix (row = request, col = driver). */
    private static CostFunction matrixCostFn(
            double[][] cost,
            List<RideRequest> requests,
            List<Driver> drivers
    ) {
        return (driver, req) -> {
            int i = requests.indexOf(req);
            int j = drivers.indexOf(driver);
            if (i < 0 || j < 0) {
                throw new IllegalStateException(
                        "Unknown driver or request in cost lookup");
            }
            return cost[i][j];
        };
    }

    /** Verifies basic feasibility invariants on the returned assignment list. */
    private static void assertFeasible(
            List<Assignment> assignments,
            List<RideRequest> requests,
            List<Driver> drivers
    ) {
        Set<String> usedRequests = new HashSet<>();
        Set<String> usedDrivers  = new HashSet<>();

        for (Assignment a : assignments) {
            // Each request at most once.
            assertTrue(
                usedRequests.add(a.request().id()),
                "Request " + a.request().id() + " assigned more than once"
            );
            // Each driver at most once.
            assertTrue(
                usedDrivers.add(a.driver().id()),
                "Driver " + a.driver().id() + " assigned more than once"
            );
            // Driver is CLAIMED (tryClaim was called successfully).
            assertEquals(
                MatchStatus.CLAIMED, a.driver().state().getStatus(),
                "Assigned driver must be CLAIMED"
            );
            // Driver was claimed for this specific request.
            assertEquals(
                a.request().id(), a.driver().state().getClaimedByRequestId(),
                "Claimed request ID mismatch"
            );
            // Cost is finite and non-negative (also enforced by Assignment constructor).
            assertTrue(
                Double.isFinite(a.cost()) && a.cost() >= 0,
                "Assignment cost must be finite and non-negative"
            );
            // Request and driver are from the supplied lists.
            assertTrue(requests.contains(a.request()),  "Request not in input list");
            assertTrue(drivers.contains(a.driver()),    "Driver not in input list");
        }
    }

    private static double totalAssignmentCost(List<Assignment> assignments) {
        return assignments.stream().mapToDouble(Assignment::cost).sum();
    }

    // =========================================================================
    // AuctionConfig - validation tests
    // =========================================================================

    @Test
    void configDefaultIsValid() {
        assertDoesNotThrow(() -> AuctionConfig.DEFAULT);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY,
                            Double.NEGATIVE_INFINITY})
    void configRejectsInvalidEpsilon(double bad) {
        assertThrows(IllegalArgumentException.class,
                () -> new AuctionConfig(bad, 1000));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void configRejectsNonPositiveMaxRounds(int bad) {
        assertThrows(IllegalArgumentException.class,
                () -> new AuctionConfig(1e-6, bad));
    }

    // =========================================================================
    // AuctionMatcher - null input rejection
    // =========================================================================

    @Test
    void matchRejectsNullRequests() {
        assertThrows(NullPointerException.class,
                () -> new AuctionMatcher().match(null, List.of(), (d, r) -> 0));
    }

    @Test
    void matchRejectsNullDrivers() {
        assertThrows(NullPointerException.class,
                () -> new AuctionMatcher().match(List.of(), null, (d, r) -> 0));
    }

    @Test
    void matchRejectsNullCostFunction() {
        assertThrows(NullPointerException.class,
                () -> new AuctionMatcher().match(List.of(), List.of(), null));
    }

    @Test
    void matchRejectsNullDriverElement() {
        List<Driver> drivers = new ArrayList<>();
        drivers.add(null);
        assertThrows(NullPointerException.class,
                () -> new AuctionMatcher().match(
                        List.of(request("R1", 0, 0)),
                        drivers,
                        (d, r) -> 0
                ));
    }

    @Test
    void matchRejectsNullRequestElement() {
        List<RideRequest> requests = new ArrayList<>();
        requests.add(null);
        assertThrows(NullPointerException.class,
                () -> new AuctionMatcher().match(
                        requests,
                        List.of(availableDriver("D1", 0, 0)),
                        (d, r) -> 0
                ));
    }

    @Test
    void matchRejectsInvalidCostFromFunction() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuctionMatcher().match(
                        List.of(request("R1", 0, 0)),
                        List.of(availableDriver("D1", 0, 0)),
                        (d, r) -> Double.NaN
                ));
    }

    @Test
    void matchRejectsNegativeCostFromFunction() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuctionMatcher().match(
                        List.of(request("R1", 0, 0)),
                        List.of(availableDriver("D1", 0, 0)),
                        (d, r) -> -1.0
                ));
    }

    // =========================================================================
    // Deterministic edge cases
    // =========================================================================

    @Test
    void emptyRequestsReturnsEmptyList() {
        List<Assignment> result = new AuctionMatcher().match(
                List.of(),
                List.of(availableDriver("D1", 0, 0)),
                (d, r) -> 1.0
        );
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyDriversReturnsEmptyList() {
        List<Assignment> result = new AuctionMatcher().match(
                List.of(request("R1", 0, 0)),
                List.of(),
                (d, r) -> 1.0
        );
        assertTrue(result.isEmpty());
    }

    @Test
    void allDriversUnavailableReturnsEmpty() {
        Driver d1 = availableDriver("D1", 0, 0);
        assertTrue(d1.state().tryClaim("OTHER"));

        List<Assignment> result = new AuctionMatcher().match(
                List.of(request("R1", 0, 0)),
                List.of(d1),
                (d, r) -> 1.0
        );
        assertTrue(result.isEmpty());
    }

    @Test
    void singleRequestSingleEligibleDriver() {
        Driver d1 = availableDriver("D1", 0, 0);
        RideRequest r1 = request("R1", 0, 0);

        List<Assignment> result = new AuctionMatcher().match(
                List.of(r1), List.of(d1), (d, r) -> 1.0
        );

        assertEquals(1, result.size());
        assertEquals("R1", result.get(0).request().id());
        assertEquals("D1", result.get(0).driver().id());
        assertEquals(MatchStatus.CLAIMED, d1.state().getStatus());
        assertFeasible(result, List.of(r1), List.of(d1));
    }

    @Test
    void multipleRequestsOneDriver_onlyOneAssigned() {
        Driver d1 = availableDriver("D1", 0, 0);
        List<RideRequest> requests = List.of(
                request("R1", 0.1, 0),
                request("R2", 0.2, 0),
                request("R3", 0.3, 0)
        );

        List<Assignment> result = new AuctionMatcher().match(
                requests, List.of(d1), (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        assertEquals(1, result.size(),
                "Only one request can be assigned when there is one driver");
        assertEquals(MatchStatus.CLAIMED, d1.state().getStatus());
        assertFeasible(result, requests, List.of(d1));
    }

    @Test
    void oneRequestMultipleDrivers_nearestPreferred() {
        // With ETA-only cost, the algorithm should assign the nearest driver.
        RideRequest r1 = request("R1", 0, 0);
        List<Driver> drivers = List.of(
                availableDriver("NEAR", 0.0, 0.01),  // ~1.1 km
                availableDriver("FAR",  0.0, 0.50)   // ~55 km
        );

        List<Assignment> result = new AuctionMatcher().match(
                List.of(r1),
                drivers,
                (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        assertEquals(1, result.size());
        assertEquals("NEAR", result.get(0).driver().id(),
                "Nearest driver must win when costs differ significantly");
        assertFeasible(result, List.of(r1), drivers);
    }

    @Test
    void knownUniqueOptimum_auctionMatchesHungarian() {
        // Cost matrix with a clear unique optimal assignment:
        //   R0→D0: cost 1.0   R0→D1: cost 9.0
        //   R1→D0: cost 8.0   R1→D1: cost 2.0
        // Optimal: R0→D0 (cost 1) + R1→D1 (cost 2) = total 3.
        // Any other: R0→D1 (cost 9) + R1→D0 (cost 8) = total 17.

        List<RideRequest> requests = List.of(
                request("R0", 0, 0),
                request("R1", 0, 0)
        );
        List<Driver> drivers = List.of(
                availableDriver("D0", 0, 0),
                availableDriver("D1", 0, 0)
        );
        double[][] cost = {{1.0, 9.0}, {8.0, 2.0}};

        List<Assignment> result = new AuctionMatcher().match(
                requests, drivers, matrixCostFn(cost, requests, drivers)
        );

        assertEquals(2, result.size());
        assertFeasible(result, requests, drivers);

        // Verify it found the optimal 3.0.
        assertEquals(3.0, totalAssignmentCost(result), TOLERANCE);

        // Verify the actual pairing.
        Assignment forR0 = result.stream()
                .filter(a -> a.request().id().equals("R0")).findFirst().orElseThrow();
        assertEquals("D0", forR0.driver().id());
    }

    @Test
    void driversOutnumberRequests_allRequestsAssigned() {
        List<RideRequest> requests = List.of(request("R1", 0, 0));
        List<Driver> drivers = List.of(
                availableDriver("D1", 0, 0.01),
                availableDriver("D2", 0, 0.02),
                availableDriver("D3", 0, 0.03)
        );

        List<Assignment> result = new AuctionMatcher().match(
                requests, drivers,
                (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        assertEquals(1, result.size(), "All requests must be assigned");
        assertFeasible(result, requests, drivers);
    }

    @Test
    void requestsOutnumberDrivers_allEligibleDriversAssigned() {
        List<RideRequest> requests = List.of(
                request("R1", 0.1, 0),
                request("R2", 0.2, 0),
                request("R3", 0.3, 0)
        );
        List<Driver> drivers = List.of(
                availableDriver("D1", 0, 0.01),
                availableDriver("D2", 0, 0.02)
        );

        List<Assignment> result = new AuctionMatcher().match(
                requests, drivers,
                (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        assertEquals(2, result.size(), "All eligible drivers must be assigned");
        assertFeasible(result, requests, drivers);
    }

    @Test
    void multipleOptimalAssignments_costIsOptimal() {
        // Symmetric cost matrix - 2 optimal assignments exist.
        //   R0→D0: 1, R0→D1: 1
        //   R1→D0: 1, R1→D1: 1
        // Both assignments have total cost 2.
        List<RideRequest> requests = List.of(request("R0", 0, 0), request("R1", 0, 0));
        List<Driver> drivers = List.of(
                availableDriver("D0", 0, 0),
                availableDriver("D1", 0, 0)
        );
        double[][] cost = {{1.0, 1.0}, {1.0, 1.0}};

        List<Assignment> result = new AuctionMatcher().match(
                requests, drivers, matrixCostFn(cost, requests, drivers)
        );

        assertEquals(2, result.size());
        assertFeasible(result, requests, drivers);
        assertEquals(2.0, totalAssignmentCost(result), TOLERANCE);
    }

    @Test
    void unavailableDriversAreExcluded() {
        Driver unavailable = availableDriver("D_UNAVAIL", 0, 0.001);
        Driver available   = availableDriver("D_AVAIL",   0, 1.0);
        assertTrue(unavailable.state().tryClaim("OTHER"));

        RideRequest r1 = request("R1", 0, 0);

        List<Assignment> result = new AuctionMatcher().match(
                List.of(r1),
                List.of(unavailable, available),
                (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        assertEquals(1, result.size());
        assertEquals("D_AVAIL", result.get(0).driver().id(),
                "Unavailable driver must be excluded even if cheaper");
        assertFeasible(result, List.of(r1), List.of(unavailable, available));
    }

    @Test
    void smallRoundLimit_unassignedRequestsLeftUnmatched() {
        // With maxRounds=1 and 5 requests competing for 5 drivers,
        // only at most 1 round of bidding completes; some requests will remain
        // unmatched.
        AuctionConfig tightConfig = new AuctionConfig(1e-6, 1);
        AuctionMatcher matcher = new AuctionMatcher(tightConfig);

        List<RideRequest> requests = List.of(
                request("R1", 0, 0), request("R2", 0, 0),
                request("R3", 0, 0), request("R4", 0, 0),
                request("R5", 0, 0)
        );
        List<Driver> drivers = List.of(
                availableDriver("D1", 0, 0.1), availableDriver("D2", 0, 0.2),
                availableDriver("D3", 0, 0.3), availableDriver("D4", 0, 0.4),
                availableDriver("D5", 0, 0.5)
        );

        List<Assignment> result = matcher.match(
                requests, drivers, (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        // At most 1 assignment (1 round), and it must be feasible.
        assertTrue(result.size() <= 1,
                "With maxRounds=1, at most 1 assignment expected");
        assertFeasible(result, requests, drivers);
    }

    @Test
    void resultIsImmutable() {
        List<Assignment> result = new AuctionMatcher().match(
                List.of(request("R1", 0, 0)),
                List.of(availableDriver("D1", 0, 0)),
                (d, r) -> 1.0
        );
        assertThrows(UnsupportedOperationException.class, () -> result.add(null));
    }

    // =========================================================================
    // No-duplicate guarantee
    // =========================================================================

    @Test
    void noDuplicateDriverAssignments() {
        List<RideRequest> requests = List.of(
                request("R1", 0, 0), request("R2", 0, 0), request("R3", 0, 0)
        );
        List<Driver> drivers = List.of(
                availableDriver("D1", 0, 0.01),
                availableDriver("D2", 0, 0.02),
                availableDriver("D3", 0, 0.03)
        );

        List<Assignment> result = new AuctionMatcher().match(
                requests, drivers, (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        Set<String> driverIds = result.stream()
                .map(a -> a.driver().id())
                .collect(Collectors.toSet());
        assertEquals(result.size(), driverIds.size(), "Each driver must appear at most once");
    }

    @Test
    void noDuplicateRequestAssignments() {
        List<RideRequest> requests = List.of(
                request("R1", 0, 0), request("R2", 0, 0)
        );
        List<Driver> drivers = List.of(
                availableDriver("D1", 0, 0.01), availableDriver("D2", 0, 0.02)
        );

        List<Assignment> result = new AuctionMatcher().match(
                requests, drivers, (d, r) -> d.location().distanceToKm(r.pickupLocation())
        );

        Set<String> requestIds = result.stream()
                .map(a -> a.request().id())
                .collect(Collectors.toSet());
        assertEquals(result.size(), requestIds.size(), "Each request must appear at most once");
    }

    // =========================================================================
    // Concurrent claim conflict
    // =========================================================================

    @Test
    void driverClaimedExternally_requestDroppedGracefully() {
        // The driver is available when the auction starts but gets claimed
        // externally between the start of the auction and commitment.
        Driver driver = availableDriver("D1", 0, 0);
        RideRequest r1 = request("R1", 0, 0);

        // We simulate a concurrent claim by using a CostFunction that claims
        // the driver as a side effect during cost matrix construction.
        // This is the closest we can get without bytecode manipulation.
        CostFunction claimDuringSideEffect = (d, req) -> {
            // Claim the driver on behalf of an external actor.
            d.state().tryClaim("EXTERNAL");
            return 1.0;
        };

        List<Assignment> result = new AuctionMatcher().match(
                List.of(r1), List.of(driver), claimDuringSideEffect
        );

        // The driver was already claimed before commitment - result must be empty
        // or if the driver passed eligibility check before the side effect ran,
        // the tryClaim in commitment will fail and drop the assignment.
        // Either way: no assignment for D1 should survive.
        for (Assignment a : result) {
            assertNotEquals("D1", a.driver().id(),
                    "Externally-claimed driver must not appear in result");
        }
    }

    @Test
    void concurrentMatchersDoNotDoubleAssign() throws Exception {
        Driver shared = availableDriver("D1", 0, 0);
        RideRequest r1 = request("R1", 0, 0);
        RideRequest r2 = request("R2", 0, 0);

        AuctionMatcher m1 = new AuctionMatcher();
        AuctionMatcher m2 = new AuctionMatcher();
        CostFunction fn = (d, r) -> d.location().distanceToKm(r.pickupLocation());

        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<List<Assignment>> f1 = exec.submit(() -> {
                ready.countDown(); start.await();
                return m1.match(List.of(r1), List.of(shared), fn);
            });
            Future<List<Assignment>> f2 = exec.submit(() -> {
                ready.countDown(); start.await();
                return m2.match(List.of(r2), List.of(shared), fn);
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            List<Assignment> res1 = f1.get(5, TimeUnit.SECONDS);
            List<Assignment> res2 = f2.get(5, TimeUnit.SECONDS);

            int totalAssigned = res1.size() + res2.size();
            assertTrue(totalAssigned <= 1,
                    "Shared driver must be assigned at most once, got: " + totalAssigned);
            assertEquals(MatchStatus.CLAIMED, shared.state().getStatus());
        } finally {
            exec.shutdownNow();
            exec.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // Randomized differential tests vs. Hungarian baseline
    // =========================================================================

    /**
     * For each of 30 randomly generated scenarios (fixed seed = scenario index):
     * <ol>
     *   <li>Generate a random cost matrix.</li>
     *   <li>Compute the optimal assignment and cost using the Hungarian solver.</li>
     *   <li>Run {@link AuctionMatcher} with the same cost matrix.</li>
     *   <li>Verify that the auction's total cost is within {@code TOLERANCE}
     *       of the optimal, and that the result satisfies all feasibility
     *       invariants.</li>
     * </ol>
     *
     * <p>Tolerance: {@code 1e-4}. Theoretical bound:
     * {@code n × epsilon = 20 × 1e-6 = 2e-5 << 1e-4}.
     */
    @Test
    void randomizedHungarianComparison_squareMatrices() {
        for (int trial = 0; trial < 30; trial++) {
            Random rng = new Random(trial);
            int n = rng.nextInt(10) + 2; // 2..11 requests and drivers

            // Build a random cost matrix in [0, 3] (realistic for DispatchCostFunction).
            double[][] cost = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    cost[i][j] = rng.nextDouble() * 3.0;
                }
            }

            List<RideRequest> requests = new ArrayList<>();
            List<Driver>      drivers  = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                requests.add(request("R" + i + "_t" + trial, 0, 0));
                drivers.add(availableDriver("D" + i + "_t" + trial, 0, 0));
            }

            // Hungarian optimal.
            int[]  hungAssignment = Hungarian.solve(cost);
            double hungCost       = Hungarian.totalCost(cost, hungAssignment);

            // Auction result.
            List<Assignment> auctionResult = new AuctionMatcher().match(
                    requests, drivers, matrixCostFn(cost, requests, drivers)
            );
            assertFeasible(auctionResult, requests, drivers);

            double auctionCost = totalAssignmentCost(auctionResult);

            // The auction assigns all n requests (square matrix, all eligible).
            assertEquals(n, auctionResult.size(),
                    "Trial " + trial + ": all requests must be assigned in square case");

            // Auction cost must be within tolerance of optimal.
            assertTrue(
                auctionCost <= hungCost + TOLERANCE,
                String.format("Trial %d (n=%d): auction cost %.6f > optimal %.6f + tol %.1e",
                        trial, n, auctionCost, hungCost, TOLERANCE)
            );
        }
    }

    @Test
    void randomizedHungarianComparison_moreDriversThanRequests() {
        for (int trial = 0; trial < 20; trial++) {
            Random rng = new Random(1000 + trial);
            int R = rng.nextInt(8) + 1;          // 1..8 requests
            int D = R + rng.nextInt(5) + 1;      // R+1..R+5 drivers

            double[][] cost = new double[R][D];
            for (int i = 0; i < R; i++) {
                for (int j = 0; j < D; j++) {
                    cost[i][j] = rng.nextDouble() * 3.0;
                }
            }

            List<RideRequest> requests = new ArrayList<>();
            List<Driver>      drivers  = new ArrayList<>();
            for (int i = 0; i < R; i++) requests.add(request("R" + i + "_t" + trial, 0, 0));
            for (int j = 0; j < D; j++) drivers.add(availableDriver("D" + j + "_t" + trial, 0, 0));

            int[]  hungAssignment = Hungarian.solve(cost);
            double hungCost       = Hungarian.totalCost(cost, hungAssignment);

            List<Assignment> auctionResult = new AuctionMatcher().match(
                    requests, drivers, matrixCostFn(cost, requests, drivers)
            );
            assertFeasible(auctionResult, requests, drivers);

            assertEquals(R, auctionResult.size(),
                    "Trial " + trial + ": all " + R + " requests must be assigned");

            double auctionCost = totalAssignmentCost(auctionResult);
            assertTrue(
                auctionCost <= hungCost + TOLERANCE,
                String.format("Trial %d (R=%d, D=%d): auction %.6f > optimal %.6f + tol",
                        trial, R, D, auctionCost, hungCost)
            );
        }
    }

    @Test
    void randomizedHungarianComparison_moreRequestsThanDrivers() {
        for (int trial = 0; trial < 20; trial++) {
            Random rng = new Random(2000 + trial);
            int D = rng.nextInt(8) + 1;          // 1..8 drivers
            int R = D + rng.nextInt(5) + 1;      // D+1..D+5 requests

            double[][] cost = new double[R][D];
            for (int i = 0; i < R; i++) {
                for (int j = 0; j < D; j++) {
                    cost[i][j] = rng.nextDouble() * 3.0;
                }
            }

            List<RideRequest> requests = new ArrayList<>();
            List<Driver>      drivers  = new ArrayList<>();
            for (int i = 0; i < R; i++) requests.add(request("R" + i + "_t" + trial, 0, 0));
            for (int j = 0; j < D; j++) drivers.add(availableDriver("D" + j + "_t" + trial, 0, 0));

            // Hungarian: assigns exactly D requests (min(R,D)).
            int[]  hungAssignment = Hungarian.solve(cost);
            double hungCost       = Hungarian.totalCost(cost, hungAssignment);

            List<Assignment> auctionResult = new AuctionMatcher().match(
                    requests, drivers, matrixCostFn(cost, requests, drivers)
            );
            assertFeasible(auctionResult, requests, drivers);

            // Auction assigns exactly D requests (one per eligible driver).
            assertEquals(D, auctionResult.size(),
                    "Trial " + trial + ": all " + D + " drivers must be assigned");

            double auctionCost = totalAssignmentCost(auctionResult);
            assertTrue(
                auctionCost <= hungCost + TOLERANCE,
                String.format("Trial %d (R=%d, D=%d): auction %.6f > optimal %.6f + tol",
                        trial, R, D, auctionCost, hungCost)
            );
        }
    }

    @Test
    void randomized_someDriversPreClaimed() {
        for (int trial = 0; trial < 15; trial++) {
            Random rng = new Random(3000 + trial);
            int totalDrivers = rng.nextInt(8) + 4;    // 4..11 total drivers
            int claimedCount = rng.nextInt(totalDrivers - 1) + 1; // at least 1 claimed
            int R = rng.nextInt(5) + 1;

            List<Driver> allDrivers = new ArrayList<>();
            for (int j = 0; j < totalDrivers; j++) {
                allDrivers.add(availableDriver("D" + j + "_t" + trial, 0, 0));
            }
            // Pre-claim some drivers.
            for (int j = 0; j < claimedCount; j++) {
                allDrivers.get(j).state().tryClaim("OTHER");
            }

            int eligibleCount = totalDrivers - claimedCount;
            List<RideRequest> requests = new ArrayList<>();
            for (int i = 0; i < R; i++) requests.add(request("R" + i + "_t" + trial, 0, 0));

            List<Assignment> result = new AuctionMatcher().match(
                    requests, allDrivers,
                    (d, req) -> rng.nextDouble() * 3.0  // cost is irrelevant here
            );

            assertFeasible(result, requests, allDrivers);

            // No pre-claimed driver should appear in result.
            for (int j = 0; j < claimedCount; j++) {
                final String preclaimedId = allDrivers.get(j).id();
                result.forEach(a -> assertNotEquals(preclaimedId, a.driver().id(),
                        "Pre-claimed driver must not appear in result"));
            }

            // At most min(R, eligibleCount) assignments.
            assertTrue(result.size() <= Math.min(R, eligibleCount),
                    "Cannot have more assignments than eligible drivers");
        }
    }

    // =========================================================================
    // Integration with DispatchCostFunction
    // =========================================================================

    @Test
    void integrationWithDispatchCostFunction_nearerDriverAssigned() {
        Driver near = availableDriver("NEAR", 0.0, 0.01);
        Driver far  = availableDriver("FAR",  0.0, 1.00);
        RideRequest r1 = request("R1", 0.0, 0.0);

        DispatchCostFunction costFn = new DispatchCostFunction(
                DispatchCostConfig.DEFAULT, List.of(r1)
        );

        List<Assignment> result = new AuctionMatcher().match(
                List.of(r1), List.of(near, far), costFn
        );

        assertEquals(1, result.size());
        assertEquals("NEAR", result.get(0).driver().id(),
                "Auction must prefer nearer driver under ETA-dominant cost");
        assertFeasible(result, List.of(r1), List.of(near, far));
    }

    @Test
    void integrationWithDispatchCostFunction_assignmentCostNonNegative() {
        List<RideRequest> requests = List.of(
                request("R1", 17.0, 78.0),
                request("R2", 17.5, 78.5)
        );
        List<Driver> drivers = List.of(
                availableDriver("D1", 17.1, 78.1),
                availableDriver("D2", 17.4, 78.4)
        );

        DispatchCostFunction costFn = new DispatchCostFunction(
                DispatchCostConfig.DEFAULT, requests
        );

        List<Assignment> result = new AuctionMatcher().match(requests, drivers, costFn);

        assertEquals(2, result.size());
        assertFeasible(result, requests, drivers);
        result.forEach(a -> assertTrue(a.cost() >= 0 && Double.isFinite(a.cost())));
    }

    // =========================================================================
    // Auction vs. Greedy quality comparison
    // =========================================================================

    /**
     * Verifies that AuctionMatcher and GreedyMatcher produce feasible results
     * on the same batch, and that the Auction's total cost is no worse than
     * the Greedy total by more than the approximation tolerance.
     *
     * <p>This is not a claim that Auction is always better; it is a sanity
     * check that both matchers are internally consistent.
     */
    @Test
    void auctionNotWorseThanhungarian_onSmallBatch() {
        // 4 requests, 4 drivers at positions that make the greedy nearest-first
        // assignment suboptimal vs. batch-optimal.
        //
        // Pickup locations: all at (0, 0).
        // Driver positions:
        //   D0 at (0, 0.01) - ~1.1 km
        //   D1 at (0, 0.02) - ~2.2 km
        //   D2 at (0, 0.03) - ~3.3 km
        //   D3 at (0, 0.04) - ~4.4 km
        //
        // All requests at (0,0), so cost = distance to pickup.
        // Both greedy and auction should produce the same assignment since the
        // cost is perfectly symmetric here. This tests that both agree.

        List<RideRequest> requests = List.of(
                request("R1", 0, 0), request("R2", 0, 0),
                request("R3", 0, 0), request("R4", 0, 0)
        );
        List<Driver> auctionDrivers = List.of(
                availableDriver("AD1", 0, 0.01), availableDriver("AD2", 0, 0.02),
                availableDriver("AD3", 0, 0.03), availableDriver("AD4", 0, 0.04)
        );
        List<Driver> greedyDrivers = List.of(
                availableDriver("GD1", 0, 0.01), availableDriver("GD2", 0, 0.02),
                availableDriver("GD3", 0, 0.03), availableDriver("GD4", 0, 0.04)
        );

        CostFunction fn = (d, r) -> d.location().distanceToKm(r.pickupLocation());

        List<Assignment> auctionResult = new AuctionMatcher().match(requests, auctionDrivers, fn);
        List<Assignment> greedyResult  = new GreedyMatcher().match(requests, greedyDrivers, fn);

        assertFeasible(auctionResult, requests, auctionDrivers);
        assertEquals(4, auctionResult.size());
        assertEquals(4, greedyResult.size());

        // Both must produce the same total cost for this symmetric problem.
        double auctionTotal = totalAssignmentCost(auctionResult);
        double greedyTotal  = greedyResult.stream().mapToDouble(Assignment::cost).sum();
        assertEquals(greedyTotal, auctionTotal, TOLERANCE,
                "On a symmetric batch, auction and greedy totals must match");
    }
}
