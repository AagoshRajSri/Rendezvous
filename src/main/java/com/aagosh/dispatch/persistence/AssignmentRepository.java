package com.aagosh.dispatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link AssignmentEntity}.
 *
 * <p>{@link #existsByRequestId(String)} is used by
 * {@link AssignmentPersistenceService} to verify whether an assignment row
 * already exists after a {@link org.springframework.dao.DataIntegrityViolationException},
 * distinguishing a previously persisted duplicate from a genuine write failure.
 */
public interface AssignmentRepository extends JpaRepository<AssignmentEntity, Long> {

    Optional<AssignmentEntity> findByRequestId(String requestId);

    boolean existsByRequestId(String requestId);
}
