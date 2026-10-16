package com.aagosh.dispatch.persistence;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.domain.TripStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Transactional service responsible for durably persisting ride-request events,
 * driver assignments, and trip execution entities into PostgreSQL.
 *
 * <h2>Outcome semantics & Invariants</h2>
 * <ul>
 *   <li>Atomic finalization: {@link #persistMatchedAssignment(RideRequest, Assignment)}
 *       writes the {@code assignments} record, the initial {@code trips} record (in state {@code ASSIGNED}),
 *       and updates the {@code ride_requests} status to {@code MATCHED} in a single database transaction.</li>
 *   <li>Terminal no-driver state: {@link #persistNoDrivers(RideRequest)} records
 *       {@code NO_DRIVERS} durably so redeliveries do not rematch.</li>
 *   <li>Exception propagation: Transactional methods allow database exceptions
 *       to propagate so Spring rolls back the PostgreSQL transaction.</li>
 *   <li>Ambiguous failure reconciliation: When an exception occurs during persistence,
 *       the consumer reconciles against {@link #findAssignment(String)} to determine
 *       whether the commit succeeded despite the error before performing claim compensation.</li>
 * </ul>
 */
@Service
public class AssignmentPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentPersistenceService.class);

    /** Status value written to {@code ride_requests.status} on initial pending state. */
    public static final String STATUS_PENDING    = "PENDING";
    /** Status value written when a driver was successfully matched and persisted. */
    public static final String STATUS_MATCHED    = "MATCHED";
    /** Status value written when no available driver was found at processing time. */
    public static final String STATUS_NO_DRIVERS = "NO_DRIVERS";
    /** Alias for STATUS_NO_DRIVERS for compatibility with existing tests. */
    public static final String STATUS_NO_DRIVER  = STATUS_NO_DRIVERS;

    private final RideRequestRepository rideRequestRepository;
    private final AssignmentRepository  assignmentRepository;
    private final TripRepository        tripRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public AssignmentPersistenceService(RideRequestRepository rideRequestRepository,
                                        AssignmentRepository assignmentRepository,
                                        @org.springframework.beans.factory.annotation.Autowired(required = false) TripRepository tripRepository) {
        this.rideRequestRepository = Objects.requireNonNull(rideRequestRepository, "rideRequestRepository");
        this.assignmentRepository  = Objects.requireNonNull(assignmentRepository, "assignmentRepository");
        this.tripRepository        = tripRepository;
    }

    public AssignmentPersistenceService(RideRequestRepository rideRequestRepository,
                                        AssignmentRepository assignmentRepository) {
        this(rideRequestRepository, assignmentRepository, null);
    }

    // -------------------------------------------------------------------------
    // Core Transactional Operations
    // -------------------------------------------------------------------------

    /**
     * Checks the durable request status without mutating state.
     *
     * @param requestId the logical request identifier
     * @return an {@link Optional} containing the status string if present, or empty
     */
    @Transactional(readOnly = true)
    public Optional<String> getRequestStatus(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        return rideRequestRepository.findByRequestId(requestId)
                .map(RideRequestEntity::getStatus);
    }

    /**
     * Checks whether a request has already reached a durable terminal state (MATCHED or NO_DRIVERS).
     *
     * @param requestId the logical request identifier
     * @return {@code true} if already finalized, {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean isRequestFinalized(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        return rideRequestRepository.findByRequestId(requestId)
                .map(req -> STATUS_MATCHED.equals(req.getStatus())
                         || STATUS_NO_DRIVERS.equals(req.getStatus())
                         || "NO_DRIVER".equals(req.getStatus()))
                .orElse(false);
    }

    /**
     * Reconciles assignment persistence: fetches the persisted assignment entity if one exists.
     *
     * @param requestId the logical request identifier
     * @return an {@link Optional} containing the persisted {@link AssignmentEntity}
     */
    @Transactional(readOnly = true)
    public Optional<AssignmentEntity> findAssignment(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        return assignmentRepository.findByRequestId(requestId);
    }

    /**
     * Checks whether an assignment for the given requestId is durably persisted.
     *
     * @param requestId the logical request identifier
     * @return {@code true} if an assignment row exists
     */
    @Transactional(readOnly = true)
    public boolean isAssignmentPersisted(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        return assignmentRepository.existsByRequestId(requestId);
    }

    /**
     * Reconciles trip persistence: fetches the persisted trip entity if one exists.
     *
     * @param requestId the logical request identifier
     * @return an {@link Optional} containing the persisted {@link TripEntity}
     */
    @Transactional(readOnly = true)
    public Optional<TripEntity> findTrip(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (tripRepository == null) {
            return Optional.empty();
        }
        return tripRepository.findByRequestId(requestId);
    }

    /**
     * Checks whether a trip entity for the given requestId is durably persisted.
     *
     * @param requestId the logical request identifier
     * @return {@code true} if a trip row exists
     */
    @Transactional(readOnly = true)
    public boolean isTripPersisted(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (tripRepository == null) {
            return false;
        }
        return tripRepository.existsByRequestId(requestId);
    }

    /**
     * Atomically records the ride request as {@code MATCHED}, inserts the
     * assignment record, and inserts the initial trip record in state {@code ASSIGNED}
     * within a single database transaction.
     *
     * <p>Database exceptions (such as unique constraint violations on duplicate
     * delivery or connection drops) are NOT caught here, allowing Spring's transaction
     * manager to execute a true rollback.
     *
     * @param request    the domain ride request
     * @param assignment the matched assignment
     */
    @Transactional
    public void persistMatchedAssignment(RideRequest request, Assignment assignment) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(assignment, "assignment");

        String requestId = request.id();
        RideRequestEntity requestEntity = rideRequestRepository.findByRequestId(requestId)
                .orElseGet(() -> new RideRequestEntity(
                        requestId,
                        request.pickupLocation().latitude(),
                        request.pickupLocation().longitude(),
                        STATUS_MATCHED
                ));
        requestEntity.setStatus(STATUS_MATCHED);
        rideRequestRepository.saveAndFlush(requestEntity);

        AssignmentEntity assignmentEntity = new AssignmentEntity(
                requestId,
                assignment.driver().id(),
                assignment.cost()
        );
        assignmentRepository.saveAndFlush(assignmentEntity);

        if (tripRepository != null) {
            TripEntity tripEntity = new TripEntity(
                    requestId,
                    assignment.driver().id(),
                    TripStatus.ASSIGNED
            );
            tripRepository.saveAndFlush(tripEntity);
        }

        log.info("Persisted matched assignment: request={} driver={} cost={}",
                requestId, assignment.driver().id(), assignment.cost());
    }

    /**
     * Atomically records the ride request as {@code NO_DRIVERS} within a transaction.
     *
     * @param request the domain ride request
     */
    @Transactional
    public void persistNoDrivers(RideRequest request) {
        Objects.requireNonNull(request, "request");

        String requestId = request.id();
        RideRequestEntity requestEntity = rideRequestRepository.findByRequestId(requestId)
                .orElseGet(() -> new RideRequestEntity(
                        requestId,
                        request.pickupLocation().latitude(),
                        request.pickupLocation().longitude(),
                        STATUS_NO_DRIVERS
                ));
        requestEntity.setStatus(STATUS_NO_DRIVERS);
        rideRequestRepository.saveAndFlush(requestEntity);

        log.info("Persisted terminal outcome NO_DRIVERS for request {}", requestId);
    }

    // -------------------------------------------------------------------------
    // Phase 7: Batch Persistence and Compensation Operations
    // -------------------------------------------------------------------------

    /**
     * Processing statistics for an atomic batch persistence operation.
     *
     * @param matchedPersisted    count of requests persisted with {@link #STATUS_MATCHED}, assignment, and trip
     * @param noDriversPersisted  count of requests persisted with terminal {@link #STATUS_NO_DRIVERS}
     * @param duplicatesSkipped   count of requests already in a finalized terminal state and skipped
     */
    public record BatchPersistResult(
            int matchedPersisted,
            int noDriversPersisted,
            int duplicatesSkipped
    ) {}

    /**
     * Results of reconciling and compensating driver claims after a persistence failure.
     *
     * @param totalAssignments  total assignments inspected
     * @param claimsReleased    in-memory claims safely released because DB rollback was confirmed or conflict occurred
     * @param claimsRetained    in-memory claims preserved because assignment was confirmed durably committed
     * @param releasedRequestIds request IDs whose driver claims were released
     * @param retainedRequestIds request IDs whose driver claims were retained
     */
    public record BatchCompensationResult(
            int totalAssignments,
            int claimsReleased,
            int claimsRetained,
            List<String> releasedRequestIds,
            List<String> retainedRequestIds
    ) {}

    /**
     * Atomically persists a batch of ride requests, their matched driver assignments,
     * and their initial trips within a single database transaction.
     *
     * <h2>Outcome semantics & Invariants</h2>
     * <ul>
     *   <li><strong>Matched requests:</strong> {@code ride_requests} is updated/created with status
     *       {@link #STATUS_MATCHED}, the {@link AssignmentEntity} is inserted, and initial {@link TripEntity}
     *       is inserted in state {@link TripStatus#ASSIGNED}.</li>
     *   <li><strong>Unmatched requests:</strong> {@code ride_requests} is updated/created with terminal
     *       status {@link #STATUS_NO_DRIVERS}.</li>
     *   <li><strong>Deterministic ordering:</strong> Requests are processed in ascending {@code requestId}
     *       order to provide consistent lock acquisition sequencing in PostgreSQL. Note that sorting by request
     *       ID provides deterministic row processing order, though it does not eliminate all possible concurrent
     *       deadlocks (e.g., secondary index page contention or concurrent FK checking).</li>
     *   <li><strong>Durable idempotency:</strong> Requests already finalized in state {@code MATCHED} or
     *       {@code NO_DRIVERS} are treated as idempotent duplicates and skipped without overwriting existing
     *       assignments or trips.</li>
     *   <li><strong>Atomic transaction rollback:</strong> Database exceptions (such as constraint violations
     *       or connection errors) are NOT caught here, allowing Spring's transaction manager to execute a full
     *       PostgreSQL rollback. In-memory compensation of driver claims is handled separately by the calling
     *       layer using {@link #reconcileAndCompensateClaims(Collection)}.</li>
     * </ul>
     *
     * @param requests    the collection of domain ride requests in this dispatch batch
     * @param assignments the matched assignments produced for this batch
     * @return a {@link BatchPersistResult} detailing the persisted and skipped counts
     */
    @Transactional
    public BatchPersistResult persistBatch(Collection<RideRequest> requests, Collection<Assignment> assignments) {
        Objects.requireNonNull(requests, "requests");
        Objects.requireNonNull(assignments, "assignments");

        if (requests.isEmpty() && assignments.isEmpty()) {
            return new BatchPersistResult(0, 0, 0);
        }

        // Index assignments by requestId; reject duplicates within the batch
        Map<String, Assignment> assignmentByRequestId = new HashMap<>();
        for (Assignment a : assignments) {
            Objects.requireNonNull(a, "assignment in assignments");
            String reqId = a.request().id();
            if (assignmentByRequestId.putIfAbsent(reqId, a) != null) {
                throw new IllegalArgumentException("Duplicate assignment for requestId in batch: " + reqId);
            }
        }

        // Aggregate unique requests (including any assigned requests not in requests collection)
        Map<String, RideRequest> requestMap = new LinkedHashMap<>();
        for (RideRequest r : requests) {
            Objects.requireNonNull(r, "request in requests");
            requestMap.putIfAbsent(r.id(), r);
        }
        for (Assignment a : assignments) {
            requestMap.putIfAbsent(a.request().id(), a.request());
        }

        // Deterministic ordering by requestId
        List<RideRequest> sortedRequests = requestMap.values().stream()
                .sorted(Comparator.comparing(RideRequest::id))
                .toList();

        int matchedCount = 0;
        int noDriversCount = 0;
        int duplicateCount = 0;

        for (RideRequest request : sortedRequests) {
            String requestId = request.id();
            Assignment assignment = assignmentByRequestId.get(requestId);

            // Step 1: Check durable status before mutating
            Optional<RideRequestEntity> existingReq = rideRequestRepository.findByRequestId(requestId);
            if (existingReq.isPresent()) {
                String currentStatus = existingReq.get().getStatus();
                if (STATUS_MATCHED.equals(currentStatus)
                        || STATUS_NO_DRIVERS.equals(currentStatus)
                        || STATUS_NO_DRIVER.equals(currentStatus)) {
                    log.info("Ride request {} already durably finalized with status {}; skipping duplicate write",
                            requestId, currentStatus);
                    duplicateCount++;
                    continue;
                }
            }

            if (assignment != null) {
                // Matched request
                RideRequestEntity reqEntity = existingReq.orElseGet(() -> new RideRequestEntity(
                        requestId,
                        request.pickupLocation().latitude(),
                        request.pickupLocation().longitude(),
                        STATUS_MATCHED
                ));
                reqEntity.setStatus(STATUS_MATCHED);
                rideRequestRepository.save(reqEntity);

                // Assignment entity: insert only if not already present
                if (!assignmentRepository.existsByRequestId(requestId)) {
                    AssignmentEntity assignmentEntity = new AssignmentEntity(
                            requestId,
                            assignment.driver().id(),
                            assignment.cost()
                    );
                    assignmentRepository.save(assignmentEntity);
                }

                // Trip entity: insert only if not already present and tripRepository configured
                if (tripRepository != null && !tripRepository.existsByRequestId(requestId)) {
                    TripEntity tripEntity = new TripEntity(
                            requestId,
                            assignment.driver().id(),
                            TripStatus.ASSIGNED
                    );
                    tripRepository.save(tripEntity);
                }

                matchedCount++;
            } else {
                // Unmatched request: terminal NO_DRIVERS
                RideRequestEntity reqEntity = existingReq.orElseGet(() -> new RideRequestEntity(
                        requestId,
                        request.pickupLocation().latitude(),
                        request.pickupLocation().longitude(),
                        STATUS_NO_DRIVERS
                ));
                reqEntity.setStatus(STATUS_NO_DRIVERS);
                rideRequestRepository.save(reqEntity);

                noDriversCount++;
            }
        }

        // Flush all pending writes within the transaction so any constraint violations surface immediately
        rideRequestRepository.flush();
        assignmentRepository.flush();
        if (tripRepository != null) {
            tripRepository.flush();
        }

        log.info("Persisted batch: matched={}, noDrivers={}, duplicatesSkipped={}",
                matchedCount, noDriversCount, duplicateCount);

        return new BatchPersistResult(matchedCount, noDriversCount, duplicateCount);
    }

    /**
     * Reconciles durable persistence state and compensates in-memory driver claims
     * for a collection of assignments following a batch persistence failure.
     *
     * <p><strong>Ambiguous Commit Safety:</strong> In distributed environments where a commit
     * outcome is uncertain (e.g. timeout or exception during commit phase), this method inspects
     * {@link #findAssignment(String)} before touching any in-memory claim:
     * <ul>
     *   <li>If the assignment row exists and matches the assigned driver, the transaction
     *       actually succeeded; the driver claim is RETAINED.</li>
     *   <li>If the assignment row exists for a different driver, another delivery won;
     *       our claim is RELEASED via {@link DriverState#releaseClaim(String)}.</li>
     *   <li>If no assignment row exists, rollback is confirmed; the claim is RELEASED
     *       via {@link DriverState#releaseClaim(String)}.</li>
     * </ul>
     *
     * <p><strong>Ownership Separation:</strong> In-memory driver claims are updated here based on
     * durable state, but in-flight request reservations remain the responsibility of the consumer layer.
     *
     * @param assignments the collection of assignments whose claims are to be reconciled and compensated
     * @return a {@link BatchCompensationResult} summarizing the reconciliation outcomes
     */
    @Transactional(readOnly = true)
    public BatchCompensationResult reconcileAndCompensateClaims(Collection<Assignment> assignments) {
        Objects.requireNonNull(assignments, "assignments");

        int releasedCount = 0;
        int retainedCount = 0;
        List<String> releasedIds = new ArrayList<>();
        List<String> retainedIds = new ArrayList<>();

        for (Assignment assignment : assignments) {
            if (assignment == null) {
                continue;
            }
            String requestId = assignment.request().id();
            Driver matchedDriver = assignment.driver();

            Optional<AssignmentEntity> existing;
            try {
                existing = findAssignment(requestId);
            } catch (Exception ex) {
                log.error("Reconciliation: lookup failed for request {}: {}; retaining driver claim as safety precaution",
                        requestId, ex.getMessage());
                retainedCount++;
                retainedIds.add(requestId);
                continue;
            }

            if (existing.isPresent()) {
                String persistedDriverId = existing.get().getDriverId();
                if (persistedDriverId.equals(matchedDriver.id())) {
                    // Ambiguous commit confirmed: the assignment was durably saved with this driver!
                    log.info("Reconciliation: assignment for request {} and driver {} was committed; retaining driver claim",
                            requestId, matchedDriver.id());
                    retainedCount++;
                    retainedIds.add(requestId);
                } else {
                    // Conflicting assignment committed with another driver: release our claim!
                    boolean released = matchedDriver.state().releaseClaim(requestId);
                    log.info("Reconciliation: request {} already assigned to driver {}; released claim on driver {} (released={})",
                            requestId, persistedDriverId, matchedDriver.id(), released);
                    releasedCount++;
                    releasedIds.add(requestId);
                }
            } else {
                // Rollback confirmed: no assignment persisted in PostgreSQL
                boolean released = matchedDriver.state().releaseClaim(requestId);
                log.warn("Reconciliation: rollback confirmed for request {}; released claim on driver {} (released={})",
                        requestId, matchedDriver.id(), released);
                releasedCount++;
                releasedIds.add(requestId);
            }
        }

        return new BatchCompensationResult(
                assignments.size(),
                releasedCount,
                retainedCount,
                List.copyOf(releasedIds),
                List.copyOf(retainedIds)
        );
    }

    // -------------------------------------------------------------------------
    // Legacy / Slice-level helper methods (used by integration tests)
    // -------------------------------------------------------------------------

    /**
     * Persists a ride-request record with {@code PENDING} status.
     *
     * @param request the domain ride request to record
     * @return {@link PersistOutcome#PERSISTED} on new insert,
     *         {@link PersistOutcome#DUPLICATE} if the row already exists,
     *         {@link PersistOutcome#FAILURE} on unexpected error
     */
    @Transactional
    public PersistOutcome persistRequest(RideRequest request) {
        Objects.requireNonNull(request, "request");
        if (rideRequestRepository.existsByRequestId(request.id())) {
            log.info("Ride request {} already persisted; treating as idempotent duplicate", request.id());
            return PersistOutcome.DUPLICATE;
        }
        try {
            var entity = new RideRequestEntity(
                    request.id(),
                    request.pickupLocation().latitude(),
                    request.pickupLocation().longitude(),
                    STATUS_PENDING
            );
            rideRequestRepository.saveAndFlush(entity);
            log.debug("Persisted ride request {} with status PENDING", request.id());
            return PersistOutcome.PERSISTED;

        } catch (DataIntegrityViolationException ex) {
            return disambiguateRequestDuplicate(request.id(), ex);
        } catch (Exception ex) {
            log.error("Unexpected failure persisting ride request {}: {}", request.id(), ex.getMessage(), ex);
            return PersistOutcome.FAILURE;
        }
    }

    /**
     * Persists the matched assignment, updates the ride-request status to
     * {@code MATCHED}, and inserts the initial {@link TripEntity} within a single transaction.
     *
     * @param assignment the matched assignment to record
     * @return {@link PersistOutcome#PERSISTED} on new insert,
     *         {@link PersistOutcome#DUPLICATE} if already persisted,
     *         {@link PersistOutcome#FAILURE} on unexpected error
     */
    @Transactional
    public PersistOutcome persistAssignment(Assignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        String requestId = assignment.request().id();
        if (assignmentRepository.existsByRequestId(requestId)) {
            log.info("Assignment for request {} already persisted; treating as idempotent duplicate", requestId);
            return PersistOutcome.DUPLICATE;
        }
        try {
            var entity = new AssignmentEntity(
                    requestId,
                    assignment.driver().id(),
                    assignment.cost()
            );
            assignmentRepository.saveAndFlush(entity);

            rideRequestRepository.findByRequestId(requestId).ifPresent(req -> {
                req.setStatus(STATUS_MATCHED);
                rideRequestRepository.save(req);
            });

            if (tripRepository != null && !tripRepository.existsByRequestId(requestId)) {
                TripEntity tripEntity = new TripEntity(
                        requestId,
                        assignment.driver().id(),
                        TripStatus.ASSIGNED
                );
                tripRepository.saveAndFlush(tripEntity);
            }

            log.info("Persisted assignment: request={} driver={} cost={}",
                    requestId, assignment.driver().id(), assignment.cost());
            return PersistOutcome.PERSISTED;

        } catch (DataIntegrityViolationException ex) {
            return disambiguateAssignmentDuplicate(requestId, ex);
        } catch (Exception ex) {
            log.error("Unexpected failure persisting assignment for request {}: {}",
                    requestId, ex.getMessage(), ex);
            return PersistOutcome.FAILURE;
        }
    }

    /**
     * Updates the status of an existing ride-request row.
     *
     * @param requestId the logical request identifier
     * @param status    the target status string
     */
    @Transactional
    public void updateRequestStatus(String requestId, String status) {
        rideRequestRepository.findByRequestId(requestId).ifPresent(entity -> {
            entity.setStatus(status);
            rideRequestRepository.save(entity);
            log.debug("Updated ride request {} status to {}", requestId, status);
        });
    }

    // -------------------------------------------------------------------------
    // Duplicate disambiguation helpers
    // -------------------------------------------------------------------------

    private PersistOutcome disambiguateRequestDuplicate(String requestId,
                                                        DataIntegrityViolationException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (msg.contains("uq_ride_requests_request_id") || msg.contains("duplicate key") || msg.contains("unique constraint")) {
            log.info("Concurrent insert for ride request {} hit unique constraint; treating as duplicate", requestId);
            return PersistOutcome.DUPLICATE;
        }
        log.error("DataIntegrityViolationException persisting ride request {} — genuine failure: {}",
                requestId, ex.getMessage(), ex);
        return PersistOutcome.FAILURE;
    }

    private PersistOutcome disambiguateAssignmentDuplicate(String requestId,
                                                           DataIntegrityViolationException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (msg.contains("uq_assignments_request_id") || msg.contains("uq_trips_request_id")
                || msg.contains("duplicate key") || msg.contains("unique constraint")) {
            log.info("Concurrent insert for assignment {} hit unique constraint; treating as duplicate", requestId);
            return PersistOutcome.DUPLICATE;
        }
        log.error("DataIntegrityViolationException persisting assignment for request {} — genuine failure: {}",
                requestId, ex.getMessage(), ex);
        return PersistOutcome.FAILURE;
    }
}
