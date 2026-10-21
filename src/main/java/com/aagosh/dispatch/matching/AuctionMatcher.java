package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;

import com.aagosh.dispatch.tracing.CandidateOutcome;
import com.aagosh.dispatch.tracing.DispatchTraceCollector;
import com.aagosh.dispatch.tracing.TraceCandidate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Assignment optimizer implementing Bertsekas's Auction Algorithm for the
 * request - driver cost-minimization problem.
 *
 * <h2>Objective</h2>
 * <p>Given a batch of requests and available drivers, find a feasible
 * one-to-one assignment that minimizes total cost as defined by the caller's
 * {@link CostFunction}. The number of assignments is bounded by
 * {@code min(requests, eligibleDrivers)}.
 *
 * <h2>Algorithm</h2>
 * <p>The implementation follows the <em>forward auction</em> variant with
 * &epsilon;-scaling (Bertsekas, 1988). Rectangular matrices (R &ne; D) are
 * padded to square n &times; n matrices (where n = max(R, D)) with zero-cost
 * dummy entries, ensuring exact equivalence to the optimal assignment problem.
 *
 * <p>Costs are converted to utilities by negation:
 * <pre>
 *   utility[i][j] = -cost[i][j]
 * </pre>
 * so that cost minimization becomes utility maximization. Each bidding round:
 * <ol>
 *   <li>Pick the next unassigned bidder from the queue.</li>
 *   <li>Find the object maximizing {@code utility[i][j] - price[j]}.</li>
 *   <li>Find the second-best adjusted utility {@code v2}.</li>
 *   <li>Compute bid increment {@code (v1 - v2) + currentEps}.</li>
 *   <li>Raise the winning object's price.</li>
 *   <li>Displace the previous holder (if any) and re-enqueue it.</li>
 * </ol>
 *
 * <h2>Epsilon-Scaling</h2>
 * <p>To prevent price wars and ensure rapid polynomial-time convergence on
 * symmetric and ill-conditioned cost profiles, the algorithm employs
 * &epsilon;-scaling: solving a sequence of auction phases with geometrically
 * decreasing &epsilon; while carrying over object prices across phases.
 *
 * <h2>Commitment and concurrency</h2>
 * <p>The entire bidding phase operates on an in-memory index table - no
 * actual claims are placed during the auction. Only after the auction
 * converges are tentative assignments committed via
 * {@link DriverState#tryClaim(String)}, which is the project's sole
 * authoritative atomic claim gate.
 *
 * <p>If a {@code tryClaim} call fails (because an external actor claimed the
 * driver concurrently), the affected request is left unmatched. No assignment
 * is emitted for it, and the driver is not double-counted.
 */
public final class AuctionMatcher implements Matcher {

    private static final int UNASSIGNED = -1;

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

    /**
     * Runs the Auction Algorithm to minimize total assignment cost for the
     * supplied batch, then commits each tentative assignment via
     * {@link DriverState#tryClaim(String)}.
     *
     * <p>Guarantees:
     * <ul>
     *   <li>Each request appears at most once in the returned list.</li>
     *   <li>Each driver appears at most once in the returned list.</li>
     *   <li>Every returned driver was successfully claimed atomically.</li>
     *   <li>No unavailable (pre-claimed) driver is assigned.</li>
     *   <li>Requests that cannot be matched (too few eligible drivers, or all
     *       drivers already claimed) are not force-matched and do not appear
     *       in the result.</li>
     * </ul>
     *
     * @param requests          the batch of ride requests; must not be {@code null}
     *                          and must not contain {@code null} elements
     * @param drivers           the fleet snapshot; must not be {@code null} and
     *                          must not contain {@code null} elements
     * @param costFunction      the pairing cost evaluator; must not be {@code null}
     * @param collectorProvider optional function resolving a trace collector by request ID
     * @return an immutable list of committed assignments; may be empty
     */
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
        // 2. Build cost matrix once. cost[i][j] = CostFunction(eligible[j], requests[i]).
        //    Validate finiteness and non-negativity (required by Assignment).
        // ------------------------------------------------------------------
        double[][] cost = buildCostMatrix(requests, eligible, costFunction, R, D);

        // ------------------------------------------------------------------
        // 3. Run the forward auction on index arrays (no real claims).
        // ------------------------------------------------------------------
        int[] requestToDriver = new int[R];

        runAuction(cost, R, D, requestToDriver);

        // ------------------------------------------------------------------
        // 4. Commit tentative assignments via tryClaim().
        //    Failures are silently dropped - the request remains unmatched.
        // ------------------------------------------------------------------
        return commitAssignments(requests, eligible, drivers, cost, costFunction, requestToDriver, R, collectorProvider, startNanos);
    }

    // -------------------------------------------------------------------------
    // Phase 1 - eligibility filter
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
    // Phase 2 - cost matrix construction
    // -------------------------------------------------------------------------

    private static double[][] buildCostMatrix(
            List<RideRequest> requests,
            List<Driver> eligible,
            CostFunction costFunction,
            int R,
            int D
    ) {
        double[][] cost = new double[R][D];

        for (int i = 0; i < R; i++) {
            RideRequest req = Objects.requireNonNull(requests.get(i), "request element");
            for (int j = 0; j < D; j++) {
                double c = costFunction.calculate(eligible.get(j), req);
                if (!Double.isFinite(c) || c < 0) {
                    throw new IllegalArgumentException(
                            "CostFunction returned an invalid value for request "
                            + req.id() + " and driver " + eligible.get(j).id()
                            + ": " + c
                    );
                }
                cost[i][j] = c;
            }
        }

        return cost;
    }

    // -------------------------------------------------------------------------
    // Phase 3 - forward auction with epsilon-scaling
    // -------------------------------------------------------------------------

    private void runAuction(
            double[][] cost,
            int R,
            int D,
            int[] requestToDriver
    ) {
        Arrays.fill(requestToDriver, UNASSIGNED);

        int n = Math.max(R, D);

        // Pad to a square n×n matrix. Rows beyond R or columns beyond D
        // are dummy slots with cost 0 (unmatched penalty = 0).
        double[][] square = new double[n][n];
        double maxC = 0.0;
        for (int i = 0; i < R; i++) {
            System.arraycopy(cost[i], 0, square[i], 0, D);
            for (int j = 0; j < D; j++) {
                if (cost[i][j] > maxC) {
                    maxC = cost[i][j];
                }
            }
            for (int j = D; j < n; j++) {
                square[i][j] = 0.0;
            }
        }
        for (int i = R; i < n; i++) {
            Arrays.fill(square[i], 0.0);
        }

        double[] price = new double[n];
        int[] reqToObj = new int[n];
        int[] objToReq = new int[n];

        int maxRounds = config.maxRounds();
        double targetEps = config.epsilon();

        // Epsilon-scaling: start at coarse epsilon to avoid price wars,
        // refine down to target epsilon.
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

                for (int j = 0; j < n; j++) {
                    double adj = -square[i][j] - price[j];
                    if (adj > v1) {
                        v2 = v1;
                        v1 = adj;
                        bestJ = j;
                    } else if (adj > v2) {
                        v2 = adj;
                    }
                }

                double gap = (n > 1) ? (v1 - v2) : 0.0;
                double safeGap = Double.isInfinite(gap) ? Double.MAX_VALUE / 2 : gap;
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
    // Phase 4 - commitment via tryClaim
    // -------------------------------------------------------------------------

    private static List<Assignment> commitAssignments(
            List<RideRequest> requests,
            List<Driver> eligible,
            List<Driver> allDrivers,
            double[][] cost,
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
                winnerCost = cost[i][assignedDriverIdx];

                // Atomic claim - the sole authoritative gate for concurrent safety.
                claimSucceeded = winnerDriver.state().tryClaim(req.id());
                if (claimSucceeded) {
                    assignments.add(new Assignment(req, winnerDriver, winnerCost));
                }
            }

            if (tracing) {
                try {
                    recordAuctionCandidates(req, eligible, allDrivers, cost[i], costFunction, assignedDriverIdx, claimSucceeded, col);

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
                    // Suppress tracing errors to ensure committed assignments and subsequent requests are never disrupted
                }
            }
        }

        return List.copyOf(assignments);
    }

    private static void recordAuctionCandidates(
            RideRequest req,
            List<Driver> eligible,
            List<Driver> allDrivers,
            double[] costForReq,
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
            double c = costForReq[j];
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

        // Record unavailable drivers (drivers that were not eligible at auction start)
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
            return null; // tracing errors must not disrupt matching
        }
    }
}
