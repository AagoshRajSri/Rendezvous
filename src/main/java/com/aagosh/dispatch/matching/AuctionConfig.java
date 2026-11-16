package com.aagosh.dispatch.matching;

/**
 * Immutable, validated configuration for {@link AuctionMatcher}.
 *
 * <h2>Algorithm overview</h2>
 * <p>The Auction Algorithm (Bertsekas, 1988) solves an assignment problem
 * by simulating a bidding market. Each unassigned "bidder" (request) finds
 * the "object" (driver) with the highest adjusted utility and raises that
 * object's price by a bid increment. The increment equals the gap between
 * the best and second-best adjusted utilities plus {@code epsilon}.
 *
 * <h2>Epsilon policy</h2>
 * <p>With a fixed positive {@code epsilon}, the algorithm is guaranteed to
 * terminate in at most O(n / epsilon) iterations, where n is the number of
 * bidders. The resulting assignment is within {@code n * epsilon} of the
 * true optimum. The default value of {@code 1e-6} is appropriate when costs
 * are in the range {@code [0, 3]} (which is the maximum for three
 * unit-weight components each normalized to {@code [0, 1]}).
 *
 * <p>Smaller epsilon gives tighter approximation but more iterations.
 * Larger epsilon gives fewer iterations but coarser approximation.
 *
 * <h2>Round limit</h2>
 * <p>As a safety net against degenerate cost matrices (e.g., many ties),
 * a maximum round count is enforced. If the limit is reached before the
 * algorithm converges, remaining unassigned requests are left unmatched.
 * The default of {@code 100_000} is conservative for batches up to a few
 * thousand requests.
 *
 * <h2>Spatial candidate pruning</h2>
 * <p>To prevent O(R × D) compute and memory explosion, candidate drivers for
 * each request can be spatially bounded to {@code maxDispatchRadiusKm} and
 * capped at {@code maxCandidatesPerRequest}.
 */
public record AuctionConfig(
        /**
         * Strictly positive bid increment floor. Smaller values produce
         * more accurate assignments at the cost of more bidding rounds.
         * Must be finite and strictly positive.
         */
        double epsilon,

        /**
         * Maximum number of bidding rounds (one round = one bid by one
         * unassigned request). Must be a strictly positive integer. If the
         * algorithm does not converge within this many rounds, unassigned
         * requests are left unmatched.
         */
        int maxRounds,

        /**
         * Maximum spatial dispatch radius in kilometres for candidate driver
         * pruning. Can be {@link Double#POSITIVE_INFINITY} for dense matching.
         * Must be strictly positive.
         */
        double maxDispatchRadiusKm,

        /**
         * Maximum number of candidate drivers considered per ride request.
         * Must be a strictly positive integer.
         */
        int maxCandidatesPerRequest
) {

    /** Default: dense baseline with unbounded radius and unlimited candidates. */
    public static final AuctionConfig DEFAULT =
            new AuctionConfig(1e-6, 100_000, Double.POSITIVE_INFINITY, Integer.MAX_VALUE);

    /** Dense baseline: infinite radius and unlimited candidates. */
    public static final AuctionConfig DENSE =
            new AuctionConfig(1e-6, 100_000, Double.POSITIVE_INFINITY, Integer.MAX_VALUE);

    /** Spatially-pruned configuration: strict 10.0 km radius and max 50 candidates per request. */
    public static final AuctionConfig SPARSE =
            new AuctionConfig(1e-6, 100_000, 10.0, 50);

    /** Backwards-compatible 2-argument constructor defaulting to unbounded radius and candidates. */
    public AuctionConfig(double epsilon, int maxRounds) {
        this(epsilon, maxRounds, Double.POSITIVE_INFINITY, Integer.MAX_VALUE);
    }

    public AuctionConfig {
        if (!Double.isFinite(epsilon) || epsilon <= 0) {
            throw new IllegalArgumentException(
                    "epsilon must be finite and strictly positive, got: " + epsilon
            );
        }
        if (maxRounds <= 0) {
            throw new IllegalArgumentException(
                    "maxRounds must be strictly positive, got: " + maxRounds
            );
        }
        if (Double.isNaN(maxDispatchRadiusKm) || maxDispatchRadiusKm <= 0.0) {
            throw new IllegalArgumentException(
                    "maxDispatchRadiusKm must be strictly positive (or POSITIVE_INFINITY), got: " + maxDispatchRadiusKm
            );
        }
        if (maxCandidatesPerRequest <= 0) {
            throw new IllegalArgumentException(
                    "maxCandidatesPerRequest must be strictly positive, got: " + maxCandidatesPerRequest
            );
        }
    }
}
