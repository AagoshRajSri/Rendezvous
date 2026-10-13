package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Assignment optimizer implementing Bertsekas's Auction Algorithm for the
 * request–driver cost-minimization problem.
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
 * <p>The entire bidding phase operates on an in-memory index table — no
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
     * @param requests     the batch of ride requests; must not be {@code null}
     *                     and must not contain {@code null} elements
     * @param drivers      the fleet snapshot; must not be {@code null} and
     *                     must not contain {@code null} elements
     * @param costFunction the pairing cost evaluator; must not be
     *                     {@code null}
     * @return an immutable list of committed assignments; may be empty
     */
    @Override
    public List<Assignment> match(
            List<RideRequest> requests,
            List<Driver> drivers,
            CostFunction costFunction
    ) {
        Objects.requireNonNull(requests,     "requests");
        Objects.requireNonNull(drivers,      "drivers");
        Objects.requireNonNull(costFunction, "costFunction");

        if (requests.isEmpty() || drivers.isEmpty()) {
            return List.of();
        }

        // ------------------------------------------------------------------
        // 1. Filter to only AVAILABLE drivers (snapshot at auction start).
        // ------------------------------------------------------------------
        List<Driver> eligible = eligibleDrivers(drivers);

        if (eligible.isEmpty()) {
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
        //    Failures are silently dropped — the request remains unmatched.
        // ------------------------------------------------------------------
        return commitAssignments(requests, eligible, cost, requestToDriver, R);
    }

    // -------------------------------------------------------------------------
    // Phase 1 — eligibility filter
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
    // Phase 2 — cost matrix construction
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
    // Phase 3 — forward auction with epsilon-scaling
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
    // Phase 4 — commitment via tryClaim
    // -------------------------------------------------------------------------

    private static List<Assignment> commitAssignments(
            List<RideRequest> requests,
            List<Driver> eligible,
            double[][] cost,
            int[] requestToDriver,
            int R
    ) {
        List<Assignment> assignments = new ArrayList<>();

        for (int i = 0; i < R; i++) {
            int j = requestToDriver[i];
            if (j == UNASSIGNED) {
                continue; // request was unmatched or dummy
            }

            RideRequest req    = requests.get(i);
            Driver      driver = eligible.get(j);

            // Atomic claim — the sole authoritative gate for concurrent safety.
            boolean claimed = driver.state().tryClaim(req.id());
            if (!claimed) {
                // Concurrent external claim took this driver first.
                // The request is left unmatched. No duplicate assignment emitted.
                continue;
            }

            assignments.add(new Assignment(req, driver, cost[i][j]));
        }

        return List.copyOf(assignments);
    }
}
