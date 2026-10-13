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
        int maxRounds
) {

    /** Conservative defaults: epsilon=1e-6, maxRounds=100_000. */
    public static final AuctionConfig DEFAULT =
            new AuctionConfig(1e-6, 100_000);

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
    }
}
