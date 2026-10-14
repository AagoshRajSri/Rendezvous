package com.aagosh.dispatch.persistence;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.RideRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Transactional service responsible for durably persisting ride-request events
 * and driver assignments into PostgreSQL.
 *
 * <h2>Outcome semantics & Invariants</h2>
 * <ul>
 *   <li>Atomic finalization: {@link #persistMatchedAssignment(RideRequest, Assignment)}
 *       writes the {@code assignments} record and updates the {@code ride_requests}
 *       status to {@code MATCHED} in a single database transaction.</li>
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

    public AssignmentPersistenceService(RideRequestRepository rideRequestRepository,
                                        AssignmentRepository assignmentRepository) {
        this.rideRequestRepository = Objects.requireNonNull(rideRequestRepository, "rideRequestRepository");
        this.assignmentRepository  = Objects.requireNonNull(assignmentRepository, "assignmentRepository");
    }

    // -------------------------------------------------------------------------
    // Phase 5 Step 2 Core Transactional Operations
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
     * Atomically records the ride request as {@code MATCHED} and inserts the
     * assignment record within a single database transaction.
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
     * Persists the matched assignment and updates the ride-request status to
     * {@code MATCHED} within a single transaction.
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
        if (msg.contains("uq_assignments_request_id") || msg.contains("duplicate key") || msg.contains("unique constraint")) {
            log.info("Concurrent insert for assignment {} hit unique constraint; treating as duplicate", requestId);
            return PersistOutcome.DUPLICATE;
        }
        log.error("DataIntegrityViolationException persisting assignment for request {} — genuine failure: {}",
                requestId, ex.getMessage(), ex);
        return PersistOutcome.FAILURE;
    }
}
