package com.aagosh.dispatch.domain;

/**
 * Operational lifecycle statuses of a trip.
 */
public enum TripStatus {
    /** Matched and assigned to a driver; driver has not yet departed for pickup. */
    ASSIGNED,
    /** Driver has acknowledged assignment and is en route to pickup location. */
    EN_ROUTE,
    /** Driver has picked up passenger; ride is actively in progress. */
    IN_PROGRESS,
    /** Ride has been successfully completed. Terminal state. */
    COMPLETED,
    /** Ride has been cancelled. Permitted only in ASSIGNED or EN_ROUTE. Terminal state. */
    CANCELLED
}
