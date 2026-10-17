package com.aagosh.dispatch.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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

    @Query("SELECT r.requestId FROM RideRequestEntity r WHERE r.requestId IN :requestIds AND r.status IN :statuses")
    List<String> findRequestIdsByRequestIdInAndStatusIn(
            @Param("requestIds") Collection<String> requestIds,
            @Param("statuses") Collection<String> statuses
    );
}
