package com.aagosh.dispatch.persistence;

/**
 * Result of a trip lifecycle transition performed by {@link TripLifecycleService}.
 *
 * <ul>
 *   <li>{@link #TRANSITIONED}            — database committed and in-memory {@code DriverState} updated.</li>
 *   <li>{@link #ALREADY_IN_TARGET_STATE} — trip already in the requested status; idempotent repeat.</li>
 *   <li>{@link #INVALID_TRANSITION}      — current status does not permit this operation.</li>
 *   <li>{@link #TRIP_NOT_FOUND}          — no trip row exists for the given {@code requestId}.</li>
 *   <li>{@link #DRIVER_NOT_FOUND}        — trip exists but its {@code driverId} is not in {@code DriverRegistry}.</li>
 *   <li>{@link #OWNERSHIP_MISMATCH}      — caller-supplied {@code driverId} does not match the persisted trip owner.</li>
 *   <li>{@link #DRIVER_STATE_DIVERGED}   — database committed but in-memory {@code DriverState} CAS failed.
 *       The database is authoritative. Recovery occurs on the next driver ping via lazy
 *       {@code DriverPingConsumer.recoverDriverStateIfActive}.</li>
 * </ul>
 */
public enum LifecycleOutcome {
    TRANSITIONED,
    ALREADY_IN_TARGET_STATE,
    INVALID_TRANSITION,
    TRIP_NOT_FOUND,
    DRIVER_NOT_FOUND,
    OWNERSHIP_MISMATCH,
    DRIVER_STATE_DIVERGED
}
