-- =============================================================================
-- V2: assignments table
--
-- Records each successful driver-to-request match.  The request_id column
-- has a UNIQUE constraint so that a database re-insert (e.g. on Kafka
-- re-delivery after a crash between commit and ack) is detected as a duplicate
-- rather than creating a second assignment row.
--
-- driver_id stores the matched driver's logical identifier (not a foreign key
-- to a drivers table, which is out of scope for this phase).
-- =============================================================================

CREATE TABLE IF NOT EXISTS assignments (
    id              BIGSERIAL       PRIMARY KEY,
    request_id      VARCHAR(255)    NOT NULL,
    driver_id       VARCHAR(255)    NOT NULL,
    cost            DOUBLE PRECISION NOT NULL,
    assigned_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_assignments_request_id UNIQUE (request_id),
    CONSTRAINT fk_assignments_ride_request
        FOREIGN KEY (request_id)
        REFERENCES ride_requests (request_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_assignments_driver_id ON assignments (driver_id);
