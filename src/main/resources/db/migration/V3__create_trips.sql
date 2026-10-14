-- =============================================================================
-- V3: trips table and ride_requests status check constraint
--
-- Records the execution lifecycle of a ride (ASSIGNED, EN_ROUTE, IN_PROGRESS,
-- COMPLETED, CANCELLED).
--
-- The request_id column carries a UNIQUE constraint to ensure exactly one trip
-- exists per ride request (matching the current no-reassignment scope).
--
-- driver_id references the logical driver ID. A partial unique index ensures
-- at most one active trip (ASSIGNED, EN_ROUTE, IN_PROGRESS) exists per driver.
-- =============================================================================

CREATE TABLE IF NOT EXISTS trips (
    id              BIGSERIAL       PRIMARY KEY,
    request_id      VARCHAR(255)    NOT NULL,
    driver_id       VARCHAR(255)    NOT NULL,
    status          VARCHAR(32)     NOT NULL DEFAULT 'ASSIGNED',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_trips_request_id UNIQUE (request_id),
    CONSTRAINT fk_trips_ride_request
        FOREIGN KEY (request_id)
        REFERENCES ride_requests (request_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_trips_status
        CHECK (status IN ('ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_trips_driver_status ON trips (driver_id, status);
CREATE INDEX IF NOT EXISTS idx_trips_driver_id ON trips (driver_id);

-- Enforce that a driver can have at most one active trip at any time in PostgreSQL
CREATE UNIQUE INDEX IF NOT EXISTS uq_trips_active_driver
    ON trips (driver_id)
    WHERE status IN ('ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS');

-- Add CHECK constraint to ride_requests status column
ALTER TABLE ride_requests
    ADD CONSTRAINT chk_ride_requests_status
    CHECK (status IN ('PENDING', 'MATCHED', 'NO_DRIVERS', 'EN_ROUTE', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'));
