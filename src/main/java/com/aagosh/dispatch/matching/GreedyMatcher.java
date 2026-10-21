package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.spatial.BoundingBox;
import com.aagosh.dispatch.spatial.QuadTree;
import com.aagosh.dispatch.spatial.QuadTreeSearchStats;
import com.aagosh.dispatch.tracing.CandidateOutcome;
import com.aagosh.dispatch.tracing.DispatchTraceCollector;
import com.aagosh.dispatch.tracing.SpatialStage;
import com.aagosh.dispatch.tracing.TraceCandidate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

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
        return match(requests, drivers, costFunction, (Function<String, DispatchTraceCollector>) null);
    }

    @Override
    public List<Assignment> match(
            List<RideRequest> requests,
            List<Driver> drivers,
            CostFunction costFunction,
            DispatchTraceCollector collector
    ) {
        return match(requests, drivers, costFunction,
                collector != null ? (reqId -> reqId.equals(collector.requestId()) ? collector : null) : null);
    }

    @Override
    public List<Assignment> match(
            List<RideRequest> requests,
            List<Driver> drivers,
            CostFunction costFunction,
            Function<String, DispatchTraceCollector> collectorProvider
    ) {
        Objects.requireNonNull(requests, "requests");
        Objects.requireNonNull(drivers, "drivers");
        Objects.requireNonNull(costFunction, "costFunction");

        // Build a spatial index and associate each unique location
        // with every driver at that location.
        QuadTree quadTree = new QuadTree(WORLD_BOUNDS);

        Map<GeoPoint, List<Driver>> driversByLocation =
                new LinkedHashMap<>();

        int totalFleetSize = drivers.size();

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

            DispatchTraceCollector collector = resolveCollector(collectorProvider, request.id());

            int totalLocations = driversByLocation.size();

            if (totalLocations == 0) {
                if (collector != null && collector.isEnabled()) {
                    try {
                        long start = System.nanoTime();
                        collector.setAlgorithm("GreedyMatcher (Spatial QuadTree)");
                        collector.setPickupLocation(request.pickupLocation().latitude(), request.pickupLocation().longitude());
                        collector.setTotalFleetSize(0);
                        collector.recordNoDrivers("Fleet is empty; no candidate drivers available");
                        collector.finish(System.nanoTime() - start);
                    } catch (Exception ignored) {
                        // Suppress tracing errors to protect dispatch matching
                    }
                }
                continue;
            }

            tryAssign(
                    request,
                    quadTree,
                    driversByLocation,
                    totalLocations,
                    totalFleetSize,
                    costFunction,
                    assignments,
                    collector
            );
        }

        return List.copyOf(assignments);
    }

    private record DriverCandidate(
            Driver driver,
            double cost,
            double distanceKm,
            CostBreakdown breakdown
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
     *   <li>Attempt atomic claims via {@link com.aagosh.dispatch.domain.DriverState#tryClaim(String)} in
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
            int totalFleetSize,
            CostFunction costFunction,
            List<Assignment> assignments,
            DispatchTraceCollector collector
    ) {
        long startNanos = System.nanoTime();
        boolean tracing = collector != null && collector.isEnabled();

        GeoPoint pickup = request.pickupLocation();

        if (tracing) {
            try {
                collector.setAlgorithm("GreedyMatcher (Spatial QuadTree)");
                collector.setPickupLocation(pickup.latitude(), pickup.longitude());
                collector.setTotalFleetSize(totalFleetSize);
            } catch (Exception ignored) {
                // Suppress tracing errors to protect dispatch matching
            }
        }

        int currentWindow = Math.min(INITIAL_WINDOW, totalLocations);
        Set<GeoPoint> visitedLocations = new HashSet<>();
        int stageIndex = 0;
        int evaluatedRank = 1;

        QuadTreeSearchStats stats = tracing ? new QuadTreeSearchStats() : null;

        while (true) {
            if (stats != null) {
                stats.reset();
            }

            // Fetch currentWindow nearest locations, nearest-first with optional traversal stats
            List<GeoPoint> candidates = quadTree.kNearest(pickup, currentWindow, stats);

            String stageType = (stageIndex == 0)
                    ? "INITIAL_WINDOW"
                    : (currentWindow < totalLocations ? "EXPANDED_WINDOW" : "EXHAUSTIVE_PASS");

            // Calculate explored bounding box for this stage enclosing pickup and candidate locations only when tracing
            Double minLat = null, maxLat = null, minLon = null, maxLon = null;
            if (tracing && !candidates.isEmpty()) {
                double sMinLat = pickup.latitude();
                double sMaxLat = pickup.latitude();
                double sMinLon = pickup.longitude();
                double sMaxLon = pickup.longitude();
                for (GeoPoint p : candidates) {
                    if (p.latitude() < sMinLat) sMinLat = p.latitude();
                    if (p.latitude() > sMaxLat) sMaxLat = p.latitude();
                    if (p.longitude() < sMinLon) sMinLon = p.longitude();
                    if (p.longitude() > sMaxLon) sMaxLon = p.longitude();
                }
                minLat = sMinLat;
                maxLat = sMaxLat;
                minLon = sMinLon;
                maxLon = sMaxLon;
            }

            List<DriverCandidate> evaluatedCandidates = new ArrayList<>();
            int newCandidatesDiscovered = 0;

            for (GeoPoint location : candidates) {
                if (visitedLocations.contains(location)) {
                    continue; // already visited in a prior window
                }

                List<Driver> driversAtLocation =
                        driversByLocation.get(location);

                if (driversAtLocation != null) {
                    for (Driver driver : driversAtLocation) {
                        newCandidatesDiscovered++;

                        // Skip drivers already claimed in prior assignments
                        if (driver.state().getStatus() != com.aagosh.dispatch.domain.MatchStatus.AVAILABLE) {
                            if (tracing) {
                                try {
                                    double distKm = driver.location().distanceToKm(pickup);
                                    collector.addCandidate(new TraceCandidate(
                                            driver.id(),
                                            driver.location().latitude(),
                                            driver.location().longitude(),
                                            distKm,
                                            null, null, null, null,
                                            evaluatedRank++,
                                            CandidateOutcome.UNAVAILABLE,
                                            "Driver is not available (status: " + driver.state().getStatus() + ")"
                                    ));
                                } catch (Exception ignored) {
                                    // Suppress tracing errors to protect dispatch matching
                                }
                            }
                            continue;
                        }

                        double cost = costFunction.calculate(driver, request);

                        if (!Double.isFinite(cost) || cost < 0) {
                            throw new IllegalArgumentException(
                                    "Cost must be finite and non-negative, got: " + cost
                            );
                        }

                        double distKm = driver.location().distanceToKm(pickup);
                        CostBreakdown breakdown = (tracing && costFunction instanceof DispatchCostFunction dcf)
                                ? dcf.decompose(driver, request)
                                : null;

                        evaluatedCandidates.add(new DriverCandidate(driver, cost, distKm, breakdown));
                    }
                }
            }

            if (tracing) {
                try {
                    collector.addStage(new SpatialStage(
                            stageIndex++,
                            stageType,
                            currentWindow,
                            minLat,
                            minLon,
                            maxLat,
                            maxLon,
                            stats != null ? stats.visitedNodes() : 0,
                            stats != null ? stats.prunedNodes() : 0,
                            newCandidatesDiscovered
                    ));
                } catch (Exception ignored) {
                    // Suppress tracing errors to protect dispatch matching
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
            for (int i = 0; i < evaluatedCandidates.size(); i++) {
                DriverCandidate candidate = evaluatedCandidates.get(i);
                int rank = evaluatedRank++;

                if (candidate.driver().state().tryClaim(request.id())) {
                    assignments.add(
                            new Assignment(request, candidate.driver(), candidate.cost())
                    );

                    if (tracing) {
                        try {
                            CostBreakdown b = candidate.breakdown();
                            collector.addCandidate(new TraceCandidate(
                                    candidate.driver().id(),
                                    candidate.driver().location().latitude(),
                                    candidate.driver().location().longitude(),
                                    candidate.distanceKm(),
                                    b != null ? b.etaCost() : null,
                                    b != null ? b.densityCost() : null,
                                    b != null ? b.opportunityCost() : null,
                                    candidate.cost(),
                                    rank,
                                    CandidateOutcome.MATCHED,
                                    null
                            ));

                            // Record remaining evaluated candidates in this window as OUTCOMPETED
                            for (int j = i + 1; j < evaluatedCandidates.size(); j++) {
                                DriverCandidate outcompeted = evaluatedCandidates.get(j);
                                CostBreakdown ob = outcompeted.breakdown();
                                collector.addCandidate(new TraceCandidate(
                                        outcompeted.driver().id(),
                                        outcompeted.driver().location().latitude(),
                                        outcompeted.driver().location().longitude(),
                                        outcompeted.distanceKm(),
                                        ob != null ? ob.etaCost() : null,
                                        ob != null ? ob.densityCost() : null,
                                        ob != null ? ob.opportunityCost() : null,
                                        outcompeted.cost(),
                                        evaluatedRank++,
                                        CandidateOutcome.OUTCOMPETED,
                                        "Outcompeted by lower-cost candidate " + candidate.driver().id()
                                ));
                            }

                            collector.recordMatch(
                                    candidate.driver().id(),
                                    "Matched driver " + candidate.driver().id() + " (cost: " + String.format(java.util.Locale.US, "%.4f", candidate.cost()) + ")"
                            );
                            collector.finish(System.nanoTime() - startNanos);
                        } catch (Exception ignored) {
                            // Suppress tracing errors to protect dispatch matching
                        }
                    }

                    return; // successfully claimed
                } else {
                    if (tracing) {
                        try {
                            CostBreakdown b = candidate.breakdown();
                            collector.addCandidate(new TraceCandidate(
                                    candidate.driver().id(),
                                    candidate.driver().location().latitude(),
                                    candidate.driver().location().longitude(),
                                    candidate.distanceKm(),
                                    b != null ? b.etaCost() : null,
                                    b != null ? b.densityCost() : null,
                                    b != null ? b.opportunityCost() : null,
                                    candidate.cost(),
                                    rank,
                                    CandidateOutcome.CLAIM_LOST,
                                    "Atomic claim failed (CAS conflict; driver claimed concurrently)"
                            ));
                        } catch (Exception ignored) {
                            // Suppress tracing errors to protect dispatch matching
                        }
                    }
                }
            }

            // All candidates in this window were already claimed or unavailable.
            if (currentWindow >= totalLocations) {
                // Exhaustive pass completed with no success - request unmatched.
                if (tracing) {
                    try {
                        collector.recordNoDrivers(
                                "Exhausted all " + totalLocations + " locations in fleet without a successful match"
                        );
                        collector.finish(System.nanoTime() - startNanos);
                    } catch (Exception ignored) {
                        // Suppress tracing errors to protect dispatch matching
                    }
                }
                return;
            }

            // Expand the window for the next round, up to totalLocations.
            currentWindow = Math.min(
                    currentWindow * WINDOW_GROWTH_FACTOR,
                    totalLocations
            );
        }
    }

    private static DispatchTraceCollector resolveCollector(
            Function<String, DispatchTraceCollector> provider,
            String requestId
    ) {
        if (provider == null || requestId == null) {
            return null;
        }
        try {
            return provider.apply(requestId);
        } catch (Exception e) {
            return null; // tracing errors must not disrupt matching
        }
    }
}
