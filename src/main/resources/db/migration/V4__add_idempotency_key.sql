-- =============================================================================
-- V4: Add idempotency_key to ride_requests
--
-- Adds durable API idempotency support at the ride-creation boundary.
-- A UNIQUE partial index on idempotency_key guarantees that concurrent client
-- retries with the same key produce exactly one row in PostgreSQL and
-- exactly one published event in Kafka.
--
-- NULL values are permitted for legacy callers without Idempotency-Key headers.
-- =============================================================================

ALTER TABLE ride_requests
    ADD COLUMN idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ride_requests_idempotency_key
    ON ride_requests (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

