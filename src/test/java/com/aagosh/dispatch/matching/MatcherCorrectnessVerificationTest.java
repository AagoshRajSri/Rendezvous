package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test to confirm assignment quality, feasibility invariants,
 * and convergence for {@link AuctionMatcher} and {@link GreedyMatcher}
 * across all benchmark batch sizes (R in {1, 5, 20, 50}, D in {50, 200, 1000}).
 */
public class MatcherCorrectnessVerificationTest {

    @Test
    @DisplayName("Verify assignment feasibility, claim invariants, and cost quality across benchmark matrix")
    void verifyAssignmentCorrectnessAndQuality() {
        int[] requestCounts = {1, 5, 20, 50};
        int[] driverCounts = {50, 200, 1000};

        AuctionMatcher auctionMatcher = new AuctionMatcher();
        GreedyMatcher greedyMatcher = new GreedyMatcher();

        for (int R : requestCounts) {
            for (int D : driverCounts) {
                Random random = new Random(42 + R * 31 + D);
                double centerLat = 40.7128;
                double centerLon = -74.0060;

                // 1. Generate fresh drivers
                List<Driver> drivers = new ArrayList<>(D);
                for (int i = 0; i < D; i++) {
                    double lat = centerLat + (random.nextGaussian() * 0.05);
                    double lon = centerLon + (random.nextGaussian() * 0.05);
                    drivers.add(new Driver("d-" + i, new GeoPoint(lat, lon), new DriverState()));
                }

                // 2. Generate fresh requests
                List<RideRequest> requests = new ArrayList<>(R);
                for (int i = 0; i < R; i++) {
                    double lat = centerLat + (random.nextGaussian() * 0.05);
                    double lon = centerLon + (random.nextGaussian() * 0.05);
                    requests.add(new RideRequest("req-" + i, new GeoPoint(lat, lon)));
                }

                DispatchCostFunction costFn = new DispatchCostFunction(DispatchCostConfig.DEFAULT, requests);

                // --- Test AuctionMatcher ---
                List<Assignment> auctionAssignments = auctionMatcher.match(requests, drivers, costFn);
                assertValidAssignments("AuctionMatcher", auctionAssignments, requests, drivers);

                double auctionTotalCost = auctionAssignments.stream().mapToDouble(Assignment::cost).sum();

                // Release claims for next matcher
                for (Assignment a : auctionAssignments) {
                    assertTrue(a.driver().state().releaseClaim(a.request().id()),
                            "Failed to release claim for " + a.driver().id());
                }

                // --- Test GreedyMatcher ---
                List<Assignment> greedyAssignments = greedyMatcher.match(requests, drivers, costFn);
                assertValidAssignments("GreedyMatcher", greedyAssignments, requests, drivers);

                double greedyTotalCost = greedyAssignments.stream().mapToDouble(Assignment::cost).sum();

                // Clean up claims
                for (Assignment a : greedyAssignments) {
                    assertTrue(a.driver().state().releaseClaim(a.request().id()),
                            "Failed to release claim for " + a.driver().id());
                }

                // Optimality check: AuctionMatcher should achieve <= total cost compared to GreedyMatcher
                // (allowing a small numerical epsilon of 1e-4)
                assertTrue(auctionTotalCost <= greedyTotalCost + 1e-4,
                        String.format("AuctionMatcher total cost (%.4f) should be <= GreedyMatcher (%.4f) for R=%d, D=%d",
                                auctionTotalCost, greedyTotalCost, R, D));

                System.out.printf("[VERIFIED R=%2d, D=%4d] Matched=%2d | AuctionCost=%.4f | GreedyCost=%.4f (delta=%.4f)%n",
                        R, D, auctionAssignments.size(), auctionTotalCost, greedyTotalCost, (greedyTotalCost - auctionTotalCost));
            }
        }
    }

    private void assertValidAssignments(
            String algorithm,
            List<Assignment> assignments,
            List<RideRequest> requests,
            List<Driver> drivers
    ) {
        int expectedSize = Math.min(requests.size(), drivers.size());
        assertEquals(expectedSize, assignments.size(),
                algorithm + " must match min(R, D)");

        Set<String> assignedRequests = new HashSet<>();
        Set<String> assignedDrivers = new HashSet<>();

        for (Assignment a : assignments) {
            // Uniqueness
            assertTrue(assignedRequests.add(a.request().id()),
                    algorithm + " assigned request twice: " + a.request().id());
            assertTrue(assignedDrivers.add(a.driver().id()),
                    algorithm + " assigned driver twice: " + a.driver().id());

            // Claim invariant
            assertEquals(MatchStatus.CLAIMED, a.driver().state().getStatus(),
                    algorithm + " driver must be in CLAIMED status");
            assertEquals(a.request().id(), a.driver().state().getClaimedByRequestId(),
                    algorithm + " driver must be claimed by matching request ID");

            // Cost validity
            assertTrue(Double.isFinite(a.cost()), "Cost must be finite");
            assertTrue(a.cost() >= 0.0, "Cost must be non-negative");
        }
    }
}
