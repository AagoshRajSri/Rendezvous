-- =============================================================================
-- V1: ride_requests table
--
-- Persists every ride request event consumed from the Kafka ride-requests topic.
-- The request_id column carries a UNIQUE constraint that serves as the durable
-- idempotency guard: a duplicate Kafka re-delivery will produce a
-- DataIntegrityViolationException on insert, which the consumer distinguishes
-- from a genuine database failure by querying whether the row already exists.
--
-- status values: PENDING (no driver yet), MATCHED (assignment persisted),
--                NO_DRIVER (no available driver at the time of processing).
-- =============================================================================

CREATE TABLE IF NOT EXISTS ride_requests (
    id              BIGSERIAL       PRIMARY KEY,
    request_id      VARCHAR(255)    NOT NULL,
    pickup_lat      DOUBLE PRECISION NOT NULL,
    pickup_lon      DOUBLE PRECISION NOT NULL,
    status          VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    received_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_ride_requests_request_id UNIQUE (request_id)
);

CREATE INDEX IF NOT EXISTS idx_ride_requests_status ON ride_requests (status);
