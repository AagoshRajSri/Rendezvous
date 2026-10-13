package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.spatial.BoundingBox;
import com.aagosh.dispatch.spatial.QuadTree;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GreedyMatcher implements Matcher {

    private static final BoundingBox WORLD_BOUNDS =
            new BoundingBox(-90, -180, 90, 180);

    /**
     * Initial number of nearest unique locations to retrieve per request before
     * expanding the search window. A value of 16 is enough to absorb normal
     * claim contention without paying the full O(N) QuadTree traversal cost on
     * every query. Must be a positive power-of-two or any positive integer; the
     * value is clamped to the actual number of unique locations.
     */
    private static final int INITIAL_WINDOW = 16;

    /**
     * Multiplicative growth factor applied each time the current candidate
     * window is exhausted without a successful claim. At 4× the algorithm
     * reaches the full fleet in at most ⌈log₄(N/16)⌉ additional rounds:
     * e.g. N=10 000 → 3 extra rounds (16 → 64 → 256 → 1 024 → 4 096 → N).
     */
    private static final int WINDOW_GROWTH_FACTOR = 4;

    @Override
    public List<Assignment> match(
            List<RideRequest> requests,
            List<Driver> drivers,
            CostFunction costFunction
    ) {
        Objects.requireNonNull(requests, "requests");
        Objects.requireNonNull(drivers, "drivers");
        Objects.requireNonNull(costFunction, "costFunction");

        // Build a spatial index and associate each unique location
        // with every driver at that location.
        QuadTree quadTree = new QuadTree(WORLD_BOUNDS);

        Map<GeoPoint, List<Driver>> driversByLocation =
                new LinkedHashMap<>();

        for (Driver driver : drivers) {
            Objects.requireNonNull(driver, "driver");

            GeoPoint location = driver.location();

            List<Driver> driversAtLocation =
                    driversByLocation.get(location);

            if (driversAtLocation == null) {
                driversAtLocation = new ArrayList<>();
                driversByLocation.put(location, driversAtLocation);

                // Insert each unique location only once.
                quadTree.insert(location);
            }

            driversAtLocation.add(driver);
        }

        List<Assignment> assignments = new ArrayList<>();

        for (RideRequest request : requests) {
            Objects.requireNonNull(request, "request");

            int totalLocations = driversByLocation.size();

            if (totalLocations == 0) {
                break;
            }

            tryAssign(
                    request,
                    quadTree,
                    driversByLocation,
                    totalLocations,
                    costFunction,
                    assignments
            );
        }

        return List.copyOf(assignments);
    }

    private record DriverCandidate(
            Driver driver,
            double cost,
            double distanceKm
    ) {}

    /**
     * Attempts to assign {@code request} to the best available driver evaluated
     * by {@code costFunction}, using progressive candidate window expansion.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Query the QuadTree for the nearest {@code currentWindow} unique
     *       locations (starting at {@code INITIAL_WINDOW}, clamped to
     *       {@code totalLocations}).
     *   <li>Collect all available drivers at locations newly retrieved in this
     *       window, evaluating their cost via {@code costFunction}.
     *   <li>Validate that costs are finite and non-negative.
     *   <li>Sort candidate drivers in ascending cost order, breaking ties
     *       deterministically by distance then driver ID.
     *   <li>Attempt atomic claims via {@link DriverState#tryClaim(String)} in
     *       cost order. If a claim succeeds, record the assignment and return.
     *   <li>If all candidates in the current window fail to claim or are
     *       unavailable, expand the window by {@code WINDOW_GROWTH_FACTOR}
     *       and repeat.
     *   <li>The loop terminates either on a successful claim or after the
     *       exhaustive pass ({@code currentWindow == totalLocations}).
     * </ol>
     */
    private void tryAssign(
            RideRequest request,
            QuadTree quadTree,
            Map<GeoPoint, List<Driver>> driversByLocation,
            int totalLocations,
            CostFunction costFunction,
            List<Assignment> assignments
    ) {
        GeoPoint pickup = request.pickupLocation();
        int currentWindow = Math.min(INITIAL_WINDOW, totalLocations);
        Set<GeoPoint> visitedLocations = new HashSet<>();

        while (true) {
            // Fetch currentWindow nearest locations, nearest-first.
            List<GeoPoint> candidates =
                    quadTree.kNearest(pickup, currentWindow);

            List<DriverCandidate> evaluatedCandidates = new ArrayList<>();

            for (GeoPoint location : candidates) {
                if (visitedLocations.contains(location)) {
                    continue; // already visited in a prior window
                }

                List<Driver> driversAtLocation =
                        driversByLocation.get(location);

                if (driversAtLocation != null) {
                    for (Driver driver : driversAtLocation) {
                        // Skip drivers already claimed in prior assignments
                        if (driver.state().getStatus() != com.aagosh.dispatch.domain.MatchStatus.AVAILABLE) {
                            continue;
                        }

                        double cost = costFunction.calculate(driver, request);

                        if (!Double.isFinite(cost) || cost < 0) {
                            throw new IllegalArgumentException(
                                    "Cost must be finite and non-negative, got: " + cost
                            );
                        }

                        double distKm = driver.location().distanceToKm(pickup);
                        evaluatedCandidates.add(new DriverCandidate(driver, cost, distKm));
                    }
                }
            }

            // Mark all locations examined in this window as visited
            visitedLocations.addAll(candidates);

            // Sort candidates by cost ascending, then distance ascending, then driver id ascending
            evaluatedCandidates.sort((c1, c2) -> {
                int costCmp = Double.compare(c1.cost(), c2.cost());
                if (costCmp != 0) {
                    return costCmp;
                }
                int distCmp = Double.compare(c1.distanceKm(), c2.distanceKm());
                if (distCmp != 0) {
                    return distCmp;
                }
                return c1.driver().id().compareTo(c2.driver().id());
            });

            // Attempt atomic claims in cost order
            for (DriverCandidate candidate : evaluatedCandidates) {
                if (candidate.driver().state().tryClaim(request.id())) {
                    assignments.add(
                            new Assignment(request, candidate.driver(), candidate.cost())
                    );
                    return; // successfully claimed
                }
            }

            // All candidates in this window were already claimed or unavailable.
            if (currentWindow >= totalLocations) {
                // Exhaustive pass completed with no success — request unmatched.
                return;
            }

            // Expand the window for the next round, up to totalLocations.
            currentWindow = Math.min(
                    currentWindow * WINDOW_GROWTH_FACTOR,
                    totalLocations
            );
        }
    }
}
