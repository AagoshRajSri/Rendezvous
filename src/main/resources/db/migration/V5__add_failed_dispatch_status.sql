-- =============================================================================
-- V5: Add FAILED_DISPATCH status to ride_requests check constraint
--
-- Expands chk_ride_requests_status to include terminal failure states:
-- FAILED_DISPATCH, FAILED, and DISPATCHED, allowing the Dead-Letter Topic (DLT)
-- consumer to mark unrecoverable dispatch poison pills in PostgreSQL.
-- =============================================================================

ALTER TABLE ride_requests
    DROP CONSTRAINT IF EXISTS chk_ride_requests_status;

ALTER TABLE ride_requests
    ADD CONSTRAINT chk_ride_requests_status
    CHECK (status IN ('PENDING', 'MATCHED', 'NO_DRIVERS', 'EN_ROUTE', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'CANCELED', 'FAILED', 'FAILED_DISPATCH', 'DISPATCHED'));

