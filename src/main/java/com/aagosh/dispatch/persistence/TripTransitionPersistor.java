package com.aagosh.dispatch.persistence;

import com.aagosh.dispatch.domain.TripStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Spring service that owns the <em>database transaction boundary</em> for
 * trip lifecycle state transitions.
 *
 * <h2>Responsibility</h2>
 * <p>Each public method is annotated {@link Transactional} and returns only after the
 * database transaction has committed. {@link TripLifecycleService} calls these methods
 * and updates in-memory {@link com.aagosh.dispatch.domain.DriverState} only after a
 * successful return, enforcing Option-B sequencing: <em>commit first, then mutate
 * in-memory state</em>. A database rollback inside a method here never reaches the
 * in-memory update in the caller.
 *
 * <h2>Concurrency safety</h2>
 * <p>{@link #transition(String, String, TripStatus)} uses a conditional JPQL UPDATE
 * ({@code WHERE status = :expectedStatus}) so that exactly one concurrent caller can win
 * a given status transition. If the UPDATE affects 0 rows, the method re-reads the
 * committed trip status and returns {@link LifecycleOutcome#ALREADY_IN_TARGET_STATE}
 * or {@link LifecycleOutcome#INVALID_TRANSITION} without touching
 * {@link com.aagosh.dispatch.domain.DriverState}.
 */
@Service
public class TripTransitionPersistor {

    private static final Logger log = LoggerFactory.getLogger(TripTransitionPersistor.class);

    /**
     * Valid predecessor statuses for each target lifecycle status.
     * Any target not listed here cannot be reached through this service.
     */
    private static final Map<TripStatus, Set<TripStatus>> VALID_PREDECESSORS = Map.of(
            TripStatus.EN_ROUTE,    Set.of(TripStatus.ASSIGNED),
            TripStatus.IN_PROGRESS, Set.of(TripStatus.EN_ROUTE),
            TripStatus.COMPLETED,   Set.of(TripStatus.IN_PROGRESS),
            TripStatus.CANCELLED,   Set.of(TripStatus.ASSIGNED, TripStatus.EN_ROUTE)
    );

    private final TripRepository tripRepository;

    public TripTransitionPersistor(TripRepository tripRepository) {
        this.tripRepository = Objects.requireNonNull(tripRepository, "tripRepository");
    }

    /**
     * Attempts to transition the named trip to {@code targetStatus} in a single database
     * transaction that commits on method return.
     *
     * <p>The operation performs the following checks inside the transaction:
     * <ol>
     *   <li>Verifies the trip exists ({@link LifecycleOutcome#TRIP_NOT_FOUND} if not).</li>
     *   <li>Verifies the recorded {@code driverId} matches the supplied value
     *       ({@link LifecycleOutcome#OWNERSHIP_MISMATCH} if not).</li>
     *   <li>Returns {@link LifecycleOutcome#ALREADY_IN_TARGET_STATE} immediately if the
     *       current status already equals {@code targetStatus}.</li>
     *   <li>Verifies the transition is permitted ({@link LifecycleOutcome#INVALID_TRANSITION}
     *       if not).</li>
     *   <li>Executes a conditional UPDATE ({@code WHERE status = :currentStatus}); if 0 rows
     *       are affected (lost concurrent race), re-reads the committed status and returns
     *       {@link LifecycleOutcome#ALREADY_IN_TARGET_STATE} or
     *       {@link LifecycleOutcome#INVALID_TRANSITION} accordingly.</li>
     * </ol>
     *
     * <p>{@link LifecycleOutcome#TRANSITIONED} is returned only when exactly 1 row was updated.
     *
     * @param requestId    the logical ride-request identifier
     * @param driverId     the driver ID expected to own this trip
     * @param targetStatus the desired post-transition status
     * @return the outcome; callers must not mutate {@code DriverState} unless
     *         {@link LifecycleOutcome#TRANSITIONED} is returned
     */
    @Transactional
    public LifecycleOutcome transition(String requestId, String driverId, TripStatus targetStatus) {
        Objects.requireNonNull(requestId,    "requestId");
        Objects.requireNonNull(driverId,     "driverId");
        Objects.requireNonNull(targetStatus, "targetStatus");

        // Step 1: load trip
        Optional<TripEntity> opt = tripRepository.findByRequestId(requestId);
        if (opt.isEmpty()) {
            log.debug("transition: TRIP_NOT_FOUND for requestId={}", requestId);
            return LifecycleOutcome.TRIP_NOT_FOUND;
        }
        TripEntity trip = opt.get();

        // Step 2: ownership check
        if (!trip.getDriverId().equals(driverId)) {
            log.warn("transition: OWNERSHIP_MISMATCH for requestId={}: caller={} trip.driverId={}",
                    requestId, driverId, trip.getDriverId());
            return LifecycleOutcome.OWNERSHIP_MISMATCH;
        }

        TripStatus currentStatus = trip.getStatus();

        // Step 3: idempotent already-at-target check
        if (currentStatus == targetStatus) {
            log.debug("transition: ALREADY_IN_TARGET_STATE {} for requestId={}", targetStatus, requestId);
            return LifecycleOutcome.ALREADY_IN_TARGET_STATE;
        }

        // Step 4: transition validity
        Set<TripStatus> validPredecessors = VALID_PREDECESSORS.get(targetStatus);
        if (validPredecessors == null || !validPredecessors.contains(currentStatus)) {
            log.warn("transition: INVALID_TRANSITION {} → {} for requestId={}",
                    currentStatus, targetStatus, requestId);
            return LifecycleOutcome.INVALID_TRANSITION;
        }

        // Step 5: conditional UPDATE serializes concurrent callers.
        // The WHERE predicate (status = currentStatus) guarantees that a concurrent transaction
        // that already updated the status will cause 0 rows to be affected here.
        // clearAutomatically = true on @Modifying evicts the L1 cache so the re-read below
        // hits the database.
        int rows = tripRepository.transitionStatus(requestId, currentStatus, targetStatus, Instant.now());

        if (rows == 0) {
            // Lost the concurrent race. Re-read committed state to return the correct outcome.
            TripEntity refreshed = tripRepository.findByRequestId(requestId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Trip " + requestId + " disappeared during concurrent transition to " + targetStatus));
            TripStatus refreshedStatus = refreshed.getStatus();
            log.debug("transition: concurrent update detected for requestId={}; committed status is now {}",
                    requestId, refreshedStatus);
            if (refreshedStatus == targetStatus) {
                return LifecycleOutcome.ALREADY_IN_TARGET_STATE;
            }
            return LifecycleOutcome.INVALID_TRANSITION;
        }

        log.info("transition: DB committed {} → {} for requestId={} driverId={}",
                currentStatus, targetStatus, requestId, driverId);
        return LifecycleOutcome.TRANSITIONED;
    }

    /**
     * Returns the current persisted trip for the given {@code requestId}, or empty if none exists.
     */
    @Transactional(readOnly = true)
    public Optional<TripEntity> findTrip(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        return tripRepository.findByRequestId(requestId);
    }
}
