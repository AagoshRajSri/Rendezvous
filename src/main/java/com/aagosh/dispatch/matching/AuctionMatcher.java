package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.spatial.BoundingBox;
import com.aagosh.dispatch.spatial.QuadTree;
import com.aagosh.dispatch.tracing.CandidateOutcome;
import com.aagosh.dispatch.tracing.DispatchTraceCollector;
import com.aagosh.dispatch.tracing.TraceCandidate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Assignment optimizer implementing Bertsekas's Auction Algorithm on candidate-pruned
 * sparse bipartite graphs for the request-driver cost-minimization problem.
 *
 * <h2>Objective</h2>
 * <p>Given a batch of requests and available drivers, find a feasible
 * one-to-one assignment that minimizes total cost as defined by the caller's
 * {@link CostFunction}. The number of assignments is bounded by
 * {@code min(requests, eligibleDrivers)}.
 *
 * <h2>Sparse Bipartite Representation & Spatial Pruning</h2>
 * <p>To prevent O(R × D) compute and memory explosion under city-scale fleet sizes,
 * candidate drivers for each request are pruned spatially using a 2D {@link QuadTree}
 * bounding-box search (or Haversine distance limit) to {@code maxDispatchRadiusKm}.
 * Cost evaluations are computed strictly on feasible candidate pairs.
 *
 * <h2>Algorithm & Epsilon-Scaling</h2>
 * <p>The implementation follows the forward auction variant with &epsilon;-scaling
 * (Bertsekas, 1988) on the sparse graph. Feasible edges have their exact utility
 * {@code -cost[i][j]}, while non-existent edges (outside dispatch radius) have infinite
 * cost and are ignored in the bidding loop. Rectangular dimensions are padded to
 * virtual slots without allocating a dense square matrix:
 * <ol>
 *   <li>Pick an unassigned request from the queue.</li>
 *   <li>Scan only its candidate drivers (plus virtual dummy slots if R &gt; D) to find the object
 *       maximizing adjusted utility {@code -cost[i][j] - price[j]}.</li>
 *   <li>Compute bid increment {@code (v1 - v2) + currentEps}.</li>
 *   <li>Raise the winning driver's price.</li>
 *   <li>Displace the previous request (if any) and re-enqueue it.</li>
 * </ol>
 *
 * <h2>Commitment and Concurrency</h2>
 * <p>The entire bidding phase operates on in-memory index structures.
 * Tentative assignments are committed atomically via {@link DriverState#tryClaim(String)},
 * which is the system's sole authoritative atomic claim gate.
 */
public final class AuctionMatcher implements Matcher {

    private static final int UNASSIGNED = -1;
    private static final BoundingBox WORLD_BOUNDS = new BoundingBox(-90, -180, 90, 180);

    private final AuctionConfig config;

    /** Creates an {@code AuctionMatcher} with default configuration. */
    public AuctionMatcher() {
        this(AuctionConfig.DEFAULT);
    }

    /**
     * Creates an {@code AuctionMatcher} with the supplied configuration.
     *
     * @param config auction configuration; must not be {@code null}
     */
    public AuctionMatcher(AuctionConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    // -------------------------------------------------------------------------
    // Matcher contract
    // -------------------------------------------------------------------------

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
        Objects.requireNonNull(requests,     "requests");
        Objects.requireNonNull(drivers,      "drivers");
        Objects.requireNonNull(costFunction, "costFunction");

        long startNanos = System.nanoTime();

        if (requests.isEmpty()) {
            return List.of();
        }

        if (drivers.isEmpty()) {
            for (RideRequest req : requests) {
                DispatchTraceCollector col = resolveCollector(collectorProvider, req.id());
                if (col != null && col.isEnabled()) {
                    try {
                        col.setAlgorithm("AuctionMatcher (Bertsekas)");
                        col.setPickupLocation(req.pickupLocation().latitude(), req.pickupLocation().longitude());
                        col.setTotalFleetSize(0);
                        col.recordNoDrivers("Fleet is empty; no candidate drivers available");
                        col.finish(System.nanoTime() - startNanos);
                    } catch (Exception ignored) {
                        // Suppress tracing errors to protect dispatch matching
                    }
                }
            }
            return List.of();
        }

        // ------------------------------------------------------------------
        // 1. Filter to only AVAILABLE drivers (snapshot at auction start).
        // ------------------------------------------------------------------
        List<Driver> eligible = eligibleDrivers(drivers);

        if (eligible.isEmpty()) {
            for (RideRequest req : requests) {
                DispatchTraceCollector col = resolveCollector(collectorProvider, req.id());
                if (col != null && col.isEnabled()) {
                    try {
                        col.setAlgorithm("AuctionMatcher (Bertsekas)");
                        col.setPickupLocation(req.pickupLocation().latitude(), req.pickupLocation().longitude());
                        col.setTotalFleetSize(drivers.size());
                        int rank = 1;
                        for (Driver d : drivers) {
                            double distKm = d.location().distanceToKm(req.pickupLocation());
                            col.addCandidate(new TraceCandidate(
                                    d.id(),
                                    d.location().latitude(),
                                    d.location().longitude(),
                                    distKm,
                                    null, null, null, null,
                                    rank++,
                                    CandidateOutcome.UNAVAILABLE,
                                    "Driver is not available (status: " + d.state().getStatus() + ")"
                            ));
                        }
                        col.recordNoDrivers("No available drivers in fleet");
                        col.finish(System.nanoTime() - startNanos);
                    } catch (Exception ignored) {
                        // Suppress tracing errors to protect dispatch matching
                    }
                }
            }
            return List.of();
        }

        int R = requests.size();
        int D = eligible.size();

        // ------------------------------------------------------------------
        // 2. Spatial candidate pruning: filter feasible drivers per request.
        // ------------------------------------------------------------------
        int[][] candidateDrivers = selectCandidates(requests, eligible, config.maxDispatchRadiusKm(), config.maxCandidatesPerRequest());

        // ------------------------------------------------------------------
        // 3. Build sparse cost matrix strictly for candidate pairs.
        // ------------------------------------------------------------------
        double[][] candidateCosts = new double[R][];
        double maxC = 0.0;
        int totalFeasibleEdges = 0;

        for (int i = 0; i < R; i++) {
            RideRequest req = requests.get(i);
            int[] cands = candidateDrivers[i];
            double[] costs = new double[cands.length];
            for (int k = 0; k < cands.length; k++) {
                int driverIdx = cands[k];
                Driver d = eligible.get(driverIdx);
                double c = costFunction.calculate(d, req);
                if (!Double.isFinite(c) || c < 0) {
                    throw new IllegalArgumentException(
                            "CostFunction returned an invalid value for request "
                                    + req.id() + " and driver " + d.id() + ": " + c
                    );
                }
                costs[k] = c;
                if (c > maxC) {
                    maxC = c;
                }
            }
            candidateCosts[i] = costs;
            totalFeasibleEdges += cands.length;
        }

        // If no feasible request-driver pairs exist within spatial bounds
        if (totalFeasibleEdges == 0) {
            for (RideRequest req : requests) {
                DispatchTraceCollector col = resolveCollector(collectorProvider, req.id());
                if (col != null && col.isEnabled()) {
                    try {
                        col.setAlgorithm("AuctionMatcher (Bertsekas)");
                        col.setPickupLocation(req.pickupLocation().latitude(), req.pickupLocation().longitude());
                        col.setTotalFleetSize(drivers.size());
                        col.recordNoDrivers("No feasible drivers within maximum dispatch radius of " + config.maxDispatchRadiusKm() + " km");
                        col.finish(System.nanoTime() - startNanos);
                    } catch (Exception ignored) {
                        // Suppress tracing errors to protect dispatch matching
                    }
                }
            }
            return List.of();
        }

        // ------------------------------------------------------------------
        // 4. Run the forward auction on sparse candidate lists.
        // ------------------------------------------------------------------
        int[] requestToDriver = new int[R];
        runSparseAuction(candidateDrivers, candidateCosts, R, D, maxC, requestToDriver);

        // ------------------------------------------------------------------
        // 5. Commit tentative assignments via tryClaim().
        // ------------------------------------------------------------------
        return commitAssignments(
                requests,
                eligible,
                drivers,
                costFunction,
                requestToDriver,
                R,
                collectorProvider,
                startNanos
        );
    }

    // -------------------------------------------------------------------------
    // Phase 1 - Eligibility filter
    // -------------------------------------------------------------------------

    private static List<Driver> eligibleDrivers(List<Driver> drivers) {
        List<Driver> result = new ArrayList<>();
        for (Driver d : drivers) {
            Objects.requireNonNull(d, "driver element");
            if (d.state().getStatus() == MatchStatus.AVAILABLE) {
                result.add(d);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Phase 2 - Spatial candidate selection
    // -------------------------------------------------------------------------

    private static int[][] selectCandidates(
            List<RideRequest> requests,
            List<Driver> eligible,
            double maxRadiusKm,
            int maxCandidatesPerRequest
    ) {
        int R = requests.size();
        int D = eligible.size();
        int[][] candidateDrivers = new int[R][];

        if (!Double.isFinite(maxRadiusKm) || maxRadiusKm <= 0) {
            int k = Math.min(D, maxCandidatesPerRequest);
            int[] all = new int[k];
            for (int j = 0; j < k; j++) {
                all[j] = j;
            }
            for (int i = 0; i < R; i++) {
                candidateDrivers[i] = all.clone();
            }
            return candidateDrivers;
        }

        // Use QuadTree indexing when D is large enough to benefit from spatial partitioning
        if (D >= 32) {
            QuadTree quadTree = new QuadTree(WORLD_BOUNDS);
            Map<GeoPoint, List<Integer>> driversByLoc = new LinkedHashMap<>();
            for (int j = 0; j < D; j++) {
                GeoPoint loc = eligible.get(j).location();
                List<Integer> list = driversByLoc.get(loc);
                if (list == null) {
                    list = new ArrayList<>();
                    driversByLoc.put(loc, list);
                    quadTree.insert(loc);
                }
                list.add(j);
            }

            for (int i = 0; i < R; i++) {
                GeoPoint pickup = requests.get(i).pickupLocation();
                List<GeoPoint> nearbyPoints = quadTree.findWithinRadius(pickup, maxRadiusKm);
                List<Integer> cands = new ArrayList<>();
                for (GeoPoint p : nearbyPoints) {
                    List<Integer> atLoc = driversByLoc.get(p);
                    if (atLoc != null) {
                        cands.addAll(atLoc);
                    }
                }
                if (cands.size() > maxCandidatesPerRequest) {
                    cands.sort(Comparator.comparingDouble(
                            idx -> eligible.get(idx).location().distanceToKm(pickup)
                    ));
                    cands = cands.subList(0, maxCandidatesPerRequest);
                }
                int[] arr = new int[cands.size()];
                for (int k = 0; k < cands.size(); k++) {
                    arr[k] = cands.get(k);
                }
                candidateDrivers[i] = arr;
            }
        } else {
            for (int i = 0; i < R; i++) {
                GeoPoint pickup = requests.get(i).pickupLocation();
                List<Integer> cands = new ArrayList<>();
                for (int j = 0; j < D; j++) {
                    if (eligible.get(j).location().distanceToKm(pickup) <= maxRadiusKm) {
                        cands.add(j);
                    }
                }
                if (cands.size() > maxCandidatesPerRequest) {
                    cands.sort(Comparator.comparingDouble(
                            idx -> eligible.get(idx).location().distanceToKm(pickup)
                    ));
                    cands = cands.subList(0, maxCandidatesPerRequest);
                }
                int[] arr = new int[cands.size()];
                for (int k = 0; k < cands.size(); k++) {
                    arr[k] = cands.get(k);
                }
                candidateDrivers[i] = arr;
            }
        }

        return candidateDrivers;
    }

    // -------------------------------------------------------------------------
    // Phase 3 - Sparse forward auction with epsilon-scaling
    // -------------------------------------------------------------------------

    private void runSparseAuction(
            int[][] candidateDrivers,
            double[][] candidateCosts,
            int R,
            int D,
            double maxC,
            int[] requestToDriver
    ) {
        Arrays.fill(requestToDriver, UNASSIGNED);

        int n = Math.max(R, D);

        double[] price = new double[n];
        int[] reqToObj = new int[n];
        int[] objToReq = new int[n];

        int maxRounds = config.maxRounds();
        double targetEps = config.epsilon();

        // Epsilon-scaling: start at coarse epsilon, refine down to target
        double theta = 5.0;
        double currentEps = Math.max(targetEps, maxC > 0 ? maxC / 2.0 : targetEps);

        int totalRounds = 0;
        Deque<Integer> unassigned = new ArrayDeque<>(n);

        while (true) {
            Arrays.fill(reqToObj, UNASSIGNED);
            Arrays.fill(objToReq, UNASSIGNED);
            unassigned.clear();
            for (int i = 0; i < n; i++) {
                unassigned.add(i);
            }

            while (!unassigned.isEmpty() && totalRounds < maxRounds) {
                int i = unassigned.poll();
                totalRounds++;

                int bestJ = UNASSIGNED;
                double v1 = -Double.MAX_VALUE;
                double v2 = -Double.MAX_VALUE;

                if (i < R) {
                    // Real request: evaluate feasible candidate drivers
                    int[] cands = candidateDrivers[i];
                    double[] costs = candidateCosts[i];

                    for (int k = 0; k < cands.length; k++) {
                        int j = cands[k];
                        double adj = -costs[k] - price[j];
                        if (adj > v1) {
                            v2 = v1;
                            v1 = adj;
                            bestJ = j;
                        } else if (adj > v2) {
                            v2 = adj;
                        }
                    }

                    // Dummy driver slots (when R > D) with cost 0.0
                    for (int j = D; j < n; j++) {
                        double adj = -price[j];
                        if (adj > v1) {
                            v2 = v1;
                            v1 = adj;
                            bestJ = j;
                        } else if (adj > v2) {
                            v2 = adj;
                        }
                    }
                } else {
                    // Dummy request (when D > R): cost is 0.0 for all drivers
                    for (int j = 0; j < D; j++) {
                        double adj = -price[j];
                        if (adj > v1) {
                            v2 = v1;
                            v1 = adj;
                            bestJ = j;
                        } else if (adj > v2) {
                            v2 = adj;
                        }
                    }
                    for (int j = D; j < n; j++) {
                        double adj = -price[j];
                        if (adj > v1) {
                            v2 = v1;
                            v1 = adj;
                            bestJ = j;
                        } else if (adj > v2) {
                            v2 = adj;
                        }
                    }
                }

                if (bestJ == UNASSIGNED) {
                    // Request has no feasible edges at all
                    continue;
                }

                double gap = (n > 1) ? (v1 - v2) : 0.0;
                double safeGap = (Double.isInfinite(gap) || gap < 0) ? 0.0 : gap;
                double bidIncrement = safeGap + currentEps;

                price[bestJ] += bidIncrement;

                int displaced = objToReq[bestJ];
                if (displaced != UNASSIGNED) {
                    reqToObj[displaced] = UNASSIGNED;
                    unassigned.add(displaced);
                }

                reqToObj[i] = bestJ;
                objToReq[bestJ] = i;
            }

            if (currentEps <= targetEps || totalRounds >= maxRounds) {
                break;
            }
            currentEps = Math.max(targetEps, currentEps / theta);
        }

        // Map back to requestToDriver
        for (int i = 0; i < R; i++) {
            int j = reqToObj[i];
            requestToDriver[i] = (j >= 0 && j < D) ? j : UNASSIGNED;
        }
    }

    // -------------------------------------------------------------------------
    // Phase 4 - Commitment via tryClaim
    // -------------------------------------------------------------------------

    private static List<Assignment> commitAssignments(
            List<RideRequest> requests,
            List<Driver> eligible,
            List<Driver> allDrivers,
            CostFunction costFunction,
            int[] requestToDriver,
            int R,
            Function<String, DispatchTraceCollector> collectorProvider,
            long startNanos
    ) {
        List<Assignment> assignments = new ArrayList<>();

        for (int i = 0; i < R; i++) {
            RideRequest req = requests.get(i);
            int assignedDriverIdx = requestToDriver[i];

            DispatchTraceCollector col = resolveCollector(collectorProvider, req.id());
            boolean tracing = col != null && col.isEnabled();

            if (tracing) {
                try {
                    col.setAlgorithm("AuctionMatcher (Bertsekas)");
                    col.setPickupLocation(req.pickupLocation().latitude(), req.pickupLocation().longitude());
                    col.setTotalFleetSize(allDrivers.size());
                } catch (Exception ignored) {
                    // Suppress tracing errors to protect dispatch matching
                }
            }

            boolean claimSucceeded = false;
            Driver winnerDriver = null;
            double winnerCost = 0.0;

            if (assignedDriverIdx != UNASSIGNED) {
                winnerDriver = eligible.get(assignedDriverIdx);
                winnerCost = costFunction.calculate(winnerDriver, req);

                // Atomic claim - the sole authoritative gate for concurrent safety.
                claimSucceeded = winnerDriver.state().tryClaim(req.id());
                if (claimSucceeded) {
                    assignments.add(new Assignment(req, winnerDriver, winnerCost));
                }
            }

            if (tracing) {
                try {
                    recordAuctionCandidates(req, eligible, allDrivers, costFunction, assignedDriverIdx, claimSucceeded, col);

                    if (claimSucceeded) {
                        col.recordMatch(
                                winnerDriver.id(),
                                "Assigned via Bertsekas Auction (cost: " + String.format(Locale.US, "%.4f", winnerCost) + ")"
                        );
                    } else if (assignedDriverIdx != UNASSIGNED) {
                        col.recordNoDrivers(
                                "Auction selected driver " + winnerDriver.id() + " but atomic claim failed (CAS conflict)"
                        );
                    } else {
                        col.recordNoDrivers("No feasible driver assignment found in global auction");
                    }
                    col.finish(System.nanoTime() - startNanos);
                } catch (Exception ignored) {
                    // Suppress tracing errors to protect dispatch matching
                }
            }
        }

        return List.copyOf(assignments);
    }

    private static void recordAuctionCandidates(
            RideRequest req,
            List<Driver> eligible,
            List<Driver> allDrivers,
            CostFunction costFunction,
            int assignedDriverIdx,
            boolean claimSucceeded,
            DispatchTraceCollector col
    ) {
        record EvaluatedDriver(Driver driver, int eligibleIdx, double cost, double distKm, CostBreakdown breakdown) {}

        List<EvaluatedDriver> evaluated = new ArrayList<>(eligible.size());
        for (int j = 0; j < eligible.size(); j++) {
            Driver d = eligible.get(j);
            double distKm = d.location().distanceToKm(req.pickupLocation());
            double c = costFunction.calculate(d, req);
            CostBreakdown b = (costFunction instanceof DispatchCostFunction dcf)
                    ? dcf.decompose(d, req)
                    : null;
            evaluated.add(new EvaluatedDriver(d, j, c, distKm, b));
        }

        // Sort candidates by cost ascending, then distance ascending, then id ascending
        evaluated.sort((e1, e2) -> {
            int cmp = Double.compare(e1.cost(), e2.cost());
            if (cmp != 0) return cmp;
            cmp = Double.compare(e1.distKm(), e2.distKm());
            if (cmp != 0) return cmp;
            return e1.driver().id().compareTo(e2.driver().id());
        });

        int rank = 1;
        for (EvaluatedDriver ed : evaluated) {
            CandidateOutcome outcome;
            String rejectionReason;

            if (ed.eligibleIdx() == assignedDriverIdx) {
                if (claimSucceeded) {
                    outcome = CandidateOutcome.MATCHED;
                    rejectionReason = null;
                } else {
                    outcome = CandidateOutcome.CLAIM_LOST;
                    rejectionReason = "Atomic claim failed (CAS conflict; driver claimed concurrently)";
                }
            } else {
                outcome = CandidateOutcome.OUTCOMPETED;
                rejectionReason = "Outcompeted in global bipartite utility assignment";
            }

            col.addCandidate(new TraceCandidate(
                    ed.driver().id(),
                    ed.driver().location().latitude(),
                    ed.driver().location().longitude(),
                    ed.distKm(),
                    ed.breakdown() != null ? ed.breakdown().etaCost() : null,
                    ed.breakdown() != null ? ed.breakdown().densityCost() : null,
                    ed.breakdown() != null ? ed.breakdown().opportunityCost() : null,
                    ed.cost(),
                    rank++,
                    outcome,
                    rejectionReason
            ));
        }

        Set<String> eligibleIds = new HashSet<>();
        for (Driver d : eligible) {
            eligibleIds.add(d.id());
        }

        for (Driver d : allDrivers) {
            if (!eligibleIds.contains(d.id())) {
                double distKm = d.location().distanceToKm(req.pickupLocation());
                col.addCandidate(new TraceCandidate(
                        d.id(),
                        d.location().latitude(),
                        d.location().longitude(),
                        distKm,
                        null, null, null, null,
                        rank++,
                        CandidateOutcome.UNAVAILABLE,
                        "Driver is not available (status: " + d.state().getStatus() + ")"
                ));
            }
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
            return null;
        }
    }
}
