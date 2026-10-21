package com.aagosh.dispatch.persistence;

/**
 * Outcome of a persistence operation performed by {@link AssignmentPersistenceService}.
 *
 * <ul>
 *   <li>{@link #PERSISTED} - the row was newly inserted this call.</li>
 *   <li>{@link #DUPLICATE} - the row already existed (idempotent re-delivery).</li>
 *   <li>{@link #FAILURE} - an unexpected database error occurred; the caller
 *       should compensate any in-memory state (e.g. release a driver claim).</li>
 * </ul>
 */
public enum PersistOutcome {
    PERSISTED,
    DUPLICATE,
    FAILURE
}
