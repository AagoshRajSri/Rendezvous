package com.aagosh.dispatch.persistence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolated write helper for durable ride request idempotency.
 *
 * <h2>Why a separate bean?</h2>
 * <p>Spring's {@code @Transactional} proxy only intercepts calls that cross a bean boundary.
 * If {@code saveNew} were a private or self-invoked method on the caller, calling it would
 * bypass the Spring proxy and {@code PROPAGATION_REQUIRES_NEW} would have no effect —
 * running inside the caller's transaction.
 *
 * <p>By placing the INSERT on a separate {@code @Service} bean, every call to {@code saveNew}
 * is routed through the Spring transaction interceptor, which suspends any active transaction,
 * opens a brand-new database transaction, commits or rolls back, and returns control.
 *
 * <h2>The Hibernate Poison Trap</h2>
 * <p>If 50 concurrent threads attempt an INSERT with the same {@code idempotency_key},
 * 1 wins and 49 throw {@link org.springframework.dao.DataIntegrityViolationException}.
 * Because the exception is thrown inside an isolated transaction that rolls back immediately,
 * the caller catches it <em>outside</em> that boundary, with an unpoisoned {@code EntityManager},
 * and safely performs a fallback {@code findByIdempotencyKey} query without triggering
 * {@code UnexpectedRollbackException}.
 */
@Service
public class IdempotentRideWriter {

    private final RideRequestRepository repository;

    public IdempotentRideWriter(RideRequestRepository repository) {
        this.repository = repository;
    }

    /**
     * Persists the given entity inside a brand-new, isolated transaction.
     *
     * @param entity the ride request entity to save
     * @return the saved and flushed entity
     * @throws org.springframework.dao.DataIntegrityViolationException on UNIQUE constraint violation
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RideRequestEntity saveNew(RideRequestEntity entity) {
        return repository.saveAndFlush(entity);
    }
}

