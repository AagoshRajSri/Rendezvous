package com.aagosh.dispatch.persistence;

import com.aagosh.dispatch.domain.TripStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link TripEntity}.
 */
public interface TripRepository extends JpaRepository<TripEntity, Long> {

    Optional<TripEntity> findByRequestId(String requestId);

    boolean existsByRequestId(String requestId);

    Optional<TripEntity> findFirstByDriverIdAndStatusIn(String driverId, Collection<TripStatus> statuses);

    List<TripEntity> findByDriverIdAndStatusIn(String driverId, Collection<TripStatus> statuses);

    /**
     * Atomically transitions a trip's status from {@code expectedStatus} to {@code newStatus}
     * using a conditional UPDATE predicate.
     *
     * <p>If a concurrent transaction already changed the trip's status between the caller's
     * initial SELECT and this UPDATE, the WHERE predicate fails and 0 rows are affected.
     * The caller must handle the 0-row case by re-reading the committed status and returning
     * an appropriate outcome (e.g., {@link LifecycleOutcome#ALREADY_IN_TARGET_STATE} or
     * {@link LifecycleOutcome#INVALID_TRANSITION}).
     *
     * <p>{@code clearAutomatically = true} flushes the JPA L1 cache after the bulk UPDATE
     * so that subsequent reads within the same transaction see the actual database state.
     *
     * @param requestId      the unique ride-request identifier
     * @param expectedStatus the status the trip must currently have for the UPDATE to succeed
     * @param newStatus      the target status to write
     * @param now            the timestamp to record in the {@code updated_at} column
     * @return the number of rows updated (1 on success, 0 if the precondition failed)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TripEntity t SET t.status = :newStatus, t.updatedAt = :now " +
           "WHERE t.requestId = :requestId AND t.status = :expectedStatus")
    int transitionStatus(@Param("requestId")      String     requestId,
                         @Param("expectedStatus") TripStatus expectedStatus,
                         @Param("newStatus")      TripStatus newStatus,
                         @Param("now")            Instant    now);
}
