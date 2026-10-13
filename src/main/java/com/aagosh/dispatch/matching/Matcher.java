package com.aagosh.dispatch.matching;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;

import java.util.List;

/**
 * Strategy interface for assigning ride requests to available drivers.
 *
 * <h2>Contractual Invariants</h2>
 * <ul>
 *   <li>Every {@link Assignment} returned by {@link #match} MUST represent a successfully
 *       committed driver claim where {@code driver.state().getStatus() == MatchStatus.CLAIMED}
 *       and {@code driver.state().getClaimedByRequestId()} equals {@code request.id()}.</li>
 *   <li>Implementations must perform atomic claims via {@link DriverState#tryClaim(String)}
 *       before emitting an {@link Assignment}. If a claim attempt fails (e.g. due to concurrent
 *       competition), the request must be dropped from the result rather than emitting an
 *       uncommitted assignment.</li>
 *   <li>Each request and each driver must appear in at most one {@link Assignment}.</li>
 * </ul>
 */
public interface Matcher {

    /**
     * Matches the given requests to available drivers based on evaluated cost.
     *
     * @param requests     list of ride requests to match; must not be {@code null}
     * @param drivers      snapshot of active drivers; must not be {@code null}
     * @param costFunction cost evaluator; must not be {@code null}
     * @return list of committed assignments with successfully claimed drivers
     */
    List<Assignment> match(
            List<RideRequest> requests,
            List<Driver> drivers,
            CostFunction costFunction
    );
}
