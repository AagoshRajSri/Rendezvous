package com.aagosh.dispatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link RideRequestEntity}.
 *
 * <p>The {@link #existsByRequestId(String)} query is used by
 * {@link AssignmentPersistenceService} to distinguish a duplicate-key violation
 * (already persisted) from a genuine database failure after a
 * {@link org.springframework.dao.DataIntegrityViolationException}.
 */
public interface RideRequestRepository extends JpaRepository<RideRequestEntity, Long> {

    Optional<RideRequestEntity> findByRequestId(String requestId);

    boolean existsByRequestId(String requestId);
}
