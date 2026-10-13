package com.aagosh.dispatch.api;

/**
 * Action type for driver ping telemetry and lifecycle operations.
 */
public enum PingAction {
    /**
     * Explicitly registers a new driver with an initial location.
     * Rejects with HTTP 409 Conflict if already registered.
     */
    REGISTER,

    /**
     * Explicitly updates the location of an existing registered driver.
     * Rejects with HTTP 404 Not Found if the driver is not registered.
     */
    UPDATE,

    /**
     * Automatically registers the driver if absent, or updates location if present.
     * Preserves existing claim state during location updates.
     */
    AUTO
}
