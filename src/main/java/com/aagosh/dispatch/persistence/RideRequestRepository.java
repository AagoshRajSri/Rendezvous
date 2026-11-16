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
 * <p>Supports lookups by {@code requestId} and durable idempotency lookups
 * by {@code idempotencyKey}.
 */
public interface RideRequestRepository extends JpaRepository<RideRequestEntity, Long> {

    Optional<RideRequestEntity> findByRequestId(String requestId);

    Optional<RideRequestEntity> findByIdempotencyKey(String idempotencyKey);

    boolean existsByRequestId(String requestId);

    boolean existsByIdempotencyKey(String idempotencyKey);

    @Query("SELECT r.requestId FROM RideRequestEntity r WHERE r.requestId IN :requestIds AND r.status IN :statuses")
    List<String> findRequestIdsByRequestIdInAndStatusIn(
            @Param("requestIds") Collection<String> requestIds,
            @Param("statuses") Collection<String> statuses
    );
}
