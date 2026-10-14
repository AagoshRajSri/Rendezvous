package com.aagosh.dispatch.persistence;

import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.TripStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * Application service for trip lifecycle state transitions.
 *
 * <h2>Option-B transaction sequencing</h2>
 * <p>Public methods on this class are <em>not</em> annotated
 * {@link org.springframework.transaction.annotation.Transactional}. All database writes
 * are delegated to {@link TripTransitionPersistor}, whose {@code @Transactional} methods
 * commit the database transaction before returning to this class. Only after that return
 * does this class mutate the in-memory {@link DriverState}. This guarantees that a database
 * rollback inside {@code TripTransitionPersistor} never reaches — and therefore never
 * corrupts — the in-memory driver state. No self-invocation is used; the delegation to a
 * separate Spring-managed bean ensures the proxy transaction boundary is honoured.
 *
 * <h2>Driver absence handling</h2>
 * <p>The driver's presence in {@link DriverRegistry} is checked before any database write.
 * If the driver is absent, {@link LifecycleOutcome#DRIVER_NOT_FOUND} is returned without
 * touching the database. Drivers are never removed from the registry within the current
 * implementation scope, so the {@link Driver} reference obtained here remains valid through
 * the database commit and subsequent {@link DriverState} update.
 *
 * <h2>Post-commit divergence ({@code DRIVER_STATE_DIVERGED})</h2>
 * <p>If the database commits successfully but the subsequent in-memory {@link DriverState}
 * compare-and-set fails (e.g., the driver's state has been concurrently advanced by another
 * operation, or the in-memory state is unexpectedly out of sync), this class returns
 * {@link LifecycleOutcome#DRIVER_STATE_DIVERGED} and logs enough context to diagnose the
 * condition. No compensating database rollback is attempted — the committed database state
 * is authoritative. The in-memory state will be lazily reconciled by
 * {@code DriverPingConsumer.recoverDriverStateIfActive} on the next driver ping.
 *
 * <h2>Concurrent safety</h2>
 * <p>Concurrent serialization is enforced at the database level by the conditional UPDATE
 * inside {@link TripTransitionPersistor#transition}. The losing concurrent caller receives
 * {@link LifecycleOutcome#ALREADY_IN_TARGET_STATE} or {@link LifecycleOutcome#INVALID_TRANSITION}
 * without ever reaching the {@link DriverState} update path.
 */
@Service
public class TripLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(TripLifecycleService.class);

    private final TripTransitionPersistor persistor;
    private final DriverRegistry          driverRegistry;

    public TripLifecycleService(TripTransitionPersistor persistor,
                                DriverRegistry          driverRegistry) {
        this.persistor      = Objects.requireNonNull(persistor,      "persistor");
        this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
    }

    /**
     * Advances a trip from {@code ASSIGNED} to {@code EN_ROUTE}.
     * Called when the driver departs for the pickup location.
     *
     * @param requestId the ride-request identifier
     * @param driverId  the driver identifier (must match the trip's recorded owner)
     * @return the outcome of the operation
     */
    public LifecycleOutcome beginEnRoute(String requestId, String driverId) {
        return applyTransition(requestId, driverId, TripStatus.EN_ROUTE);
    }

    /**
     * Advances a trip from {@code EN_ROUTE} to {@code IN_PROGRESS}.
     * Called when the driver picks up the passenger.
     *
     * @param requestId the ride-request identifier
     * @param driverId  the driver identifier (must match the trip's recorded owner)
     * @return the outcome of the operation
     */
    public LifecycleOutcome beginPickup(String requestId, String driverId) {
        return applyTransition(requestId, driverId, TripStatus.IN_PROGRESS);
    }

    /**
     * Advances a trip from {@code IN_PROGRESS} to {@code COMPLETED}.
     * Called when the ride ends.
     *
     * @param requestId the ride-request identifier
     * @param driverId  the driver identifier (must match the trip's recorded owner)
     * @return the outcome of the operation
     */
    public LifecycleOutcome completeTrip(String requestId, String driverId) {
        return applyTransition(requestId, driverId, TripStatus.COMPLETED);
    }

    /**
     * Cancels a trip. Permitted only from {@code ASSIGNED} or {@code EN_ROUTE}.
     * Cancellation after pickup ({@code IN_PROGRESS}) is rejected with
     * {@link LifecycleOutcome#INVALID_TRANSITION}.
     *
     * @param requestId the ride-request identifier
     * @param driverId  the driver identifier (must match the trip's recorded owner)
     * @return the outcome of the operation
     */
    public LifecycleOutcome cancelTrip(String requestId, String driverId) {
        return applyTransition(requestId, driverId, TripStatus.CANCELLED);
    }

    /**
     * Returns the current persisted trip for the given {@code requestId}.
     *
     * @param requestId the ride-request identifier
     * @return the trip entity, or {@link Optional#empty()} if not found
     */
    public Optional<TripEntity> findTrip(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        return persistor.findTrip(requestId);
    }

    // -------------------------------------------------------------------------
    // Coordination
    // -------------------------------------------------------------------------

    /**
     * Coordinates the full lifecycle transition: pre-flight driver check, database commit
     * via {@link TripTransitionPersistor}, then in-memory {@link DriverState} update.
     *
     * <p>The {@link DriverState} update is attempted only after the database transaction
     * returned from {@code persistor.transition()} has committed, enforcing Option-B
     * sequencing.
     */
    private LifecycleOutcome applyTransition(String requestId, String driverId, TripStatus targetStatus) {
        Objects.requireNonNull(requestId,    "requestId");
        Objects.requireNonNull(driverId,     "driverId");
        Objects.requireNonNull(targetStatus, "targetStatus");

        // Pre-flight: resolve the driver before any database write.
        // Returning DRIVER_NOT_FOUND here avoids a committed DB transition with no
        // in-memory counterpart that would be invisible to the registry.
        Optional<Driver> driverOpt = driverRegistry.findById(driverId);
        if (driverOpt.isEmpty()) {
            log.warn("applyTransition: DRIVER_NOT_FOUND for requestId={} driverId={} target={}",
                    requestId, driverId, targetStatus);
            return LifecycleOutcome.DRIVER_NOT_FOUND;
        }

        // Commit the database transition. The @Transactional boundary inside
        // TripTransitionPersistor commits before this call returns.
        // Any exception thrown here (e.g., DataIntegrityViolationException) propagates
        // before the DriverState update below, leaving DriverState unmodified.
        LifecycleOutcome dbOutcome = persistor.transition(requestId, driverId, targetStatus);
        if (dbOutcome != LifecycleOutcome.TRANSITIONED) {
            return dbOutcome;
        }

        // Database is committed. Now update the in-memory DriverState.
        DriverState state = driverOpt.get().state();
        boolean stateUpdated = applyDriverStateTransition(state, requestId, targetStatus);

        if (!stateUpdated) {
            log.warn("DRIVER_STATE_DIVERGED: DB committed {} for requestId={} driverId={} "
                    + "but DriverState CAS failed (driverCurrentStatus={}). "
                    + "DB is authoritative. Recovery via recoverDriverStateIfActive on next ping.",
                    targetStatus, requestId, driverId, state.getStatus());
            return LifecycleOutcome.DRIVER_STATE_DIVERGED;
        }

        log.info("Lifecycle transition complete: requestId={} driverId={} status={}",
                requestId, driverId, targetStatus);
        return LifecycleOutcome.TRANSITIONED;
    }

    /**
     * Calls the appropriate {@link DriverState} compare-and-set method for the given target trip status.
     *
     * @param state        the driver's mutable state object
     * @param requestId    the request ID that owns the claim
     * @param targetStatus the committed target trip status
     * @return {@code true} if the in-memory transition succeeded; {@code false} if the CAS failed
     */
    private boolean applyDriverStateTransition(DriverState state, String requestId, TripStatus targetStatus) {
        return switch (targetStatus) {
            case EN_ROUTE    -> state.transitionToEnRoute(requestId);
            case IN_PROGRESS -> state.transitionToOnTrip(requestId);
            case COMPLETED   -> state.completeTrip(requestId);
            case CANCELLED   -> state.cancelTrip(requestId);
            default -> throw new IllegalStateException(
                    "Unexpected targetStatus in applyDriverStateTransition: " + targetStatus);
        };
    }
}
