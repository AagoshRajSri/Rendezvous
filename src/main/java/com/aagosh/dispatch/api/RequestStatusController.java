package com.aagosh.dispatch.api;

import com.aagosh.dispatch.config.DriverFreshnessConfig;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.persistence.AssignmentEntity;
import com.aagosh.dispatch.persistence.AssignmentPersistenceService;
import com.aagosh.dispatch.persistence.RideRequestEntity;
import com.aagosh.dispatch.persistence.RideRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides request-status queries and {@code /api/*} route aliases for dispatch operations.
 *
 * <h2>Request status endpoint</h2>
 * <p>{@code GET /api/requests/{requestId}} – returns the persisted dispatch status
 * for a ride request. The frontend polls this endpoint with bounded exponential
 * backoff after posting a ride request. Possible status values are:
 * <ul>
 *   <li>{@code PENDING}    – accepted and queued; matching in progress.</li>
 *   <li>{@code MATCHED}    – driver assigned; {@code driverId} is populated.</li>
 *   <li>{@code NO_DRIVERS} – terminal; no driver was available.</li>
 * </ul>
 * Returns HTTP 404 until the Kafka consumer persists the initial PENDING row.
 *
 * <h2>/api aliases</h2>
 * <p>{@code POST /api/request}  – thin alias for {@code POST /request} (RideController).
 * <p>{@code POST /api/register} – thin alias for {@code POST /register} (DriverController).
 * Original routes are preserved; aliases do not replace them.
 *
 * <h2>Security disclaimer</h2>
 * <p>This is unauthenticated demonstration infrastructure. The {@code driverId} in
 * assignment data is not a verified caller identity.
 */
@RestController
@RequestMapping("/api")
public class RequestStatusController {

    private static final Logger log = LoggerFactory.getLogger(RequestStatusController.class);

    private final RideRequestRepository rideRequestRepository;
    private final AssignmentPersistenceService assignmentPersistenceService;
    private final RideController rideController;
    private final DriverController driverController;
    private final DriverRegistry driverRegistry;
    private final DriverFreshnessConfig freshnessConfig;

    public RequestStatusController(RideRequestRepository rideRequestRepository,
                                   AssignmentPersistenceService assignmentPersistenceService,
                                   RideController rideController,
                                   DriverController driverController,
                                   DriverRegistry driverRegistry,
                                   DriverFreshnessConfig freshnessConfig) {
        this.rideRequestRepository        = Objects.requireNonNull(rideRequestRepository, "rideRequestRepository");
        this.assignmentPersistenceService = Objects.requireNonNull(assignmentPersistenceService, "assignmentPersistenceService");
        this.rideController               = Objects.requireNonNull(rideController, "rideController");
        this.driverController             = Objects.requireNonNull(driverController, "driverController");
        this.driverRegistry               = Objects.requireNonNull(driverRegistry, "driverRegistry");
        this.freshnessConfig              = Objects.requireNonNull(freshnessConfig, "freshnessConfig");
    }

    // -------------------------------------------------------------------------
    // Request status query
    // -------------------------------------------------------------------------

    /**
     * Returns the persisted dispatch status for a ride request.
     *
     * <p>HTTP 200 with {@link RequestStatusResponse} when a record exists.
     * HTTP 404 when no record has been persisted yet (request not yet consumed by Kafka).
     * HTTP 400 when {@code requestId} is blank.
     */
    @GetMapping("/requests/{requestId}")
    public ResponseEntity<?> getRequestStatus(@PathVariable("requestId") String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new InvalidRequestException("requestId must not be null or blank");
        }

        Optional<RideRequestEntity> entityOpt = rideRequestRepository.findByRequestId(requestId);
        if (entityOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiErrorResponse(
                            HttpStatus.NOT_FOUND.value(),
                            "Not Found",
                            "No ride request found for requestId: " + requestId));
        }

        RideRequestEntity entity = entityOpt.get();

        // Populate driverId only when MATCHED (from the assignments table)
        String driverId = null;
        Double driverLat = null;
        Double driverLon = null;
        Instant driverLocationAt = null;

        if (AssignmentPersistenceService.STATUS_MATCHED.equals(entity.getStatus())) {
            driverId = assignmentPersistenceService.findAssignment(requestId)
                    .map(AssignmentEntity::getDriverId)
                    .orElse(null);

            // Attempt to attach last-known telemetry for the matched driver.
            // Per mandatory correction #6: a location lookup failure must NOT undo
            // or misrepresent the already-persisted match. If anything goes wrong
            // the match record is still returned correctly, with null telemetry.
            if (driverId != null) {
                try {
                    Optional<Driver> driverOpt = driverRegistry.findById(driverId);
                    if (driverOpt.isPresent()) {
                        Driver d  = driverOpt.get();
                        long   ts = d.updatedAt();
                        if (ts > 0L && driverRegistry.isFresh(driverId, freshnessConfig)) {
                            driverLat       = d.location().latitude();
                            driverLon       = d.location().longitude();
                            driverLocationAt = Instant.ofEpochMilli(ts);
                        }
                    }
                } catch (Exception ex) {
                    // Location lookup failure must not affect match reporting.
                    log.warn("Failed to read driver location for driverId={} during status query: {}",
                            driverId, ex.getMessage());
                }
            }
        }

        log.debug("Status query: requestId={} status={} driverId={} hasTelemetry={}",
                requestId, entity.getStatus(), driverId, driverLat != null);

        return ResponseEntity.ok(new RequestStatusResponse(
                entity.getRequestId(),
                entity.getStatus(),
                driverId,
                entity.getReceivedAt(),
                entity.getUpdatedAt(),
                driverLat,
                driverLon,
                driverLocationAt
        ));
    }

    // -------------------------------------------------------------------------
    // /api aliases (thin delegation; original routes preserved)
    // -------------------------------------------------------------------------

    /**
     * Alias for {@code POST /request}. Delegates to {@link RideController#requestRide}.
     */
    @PostMapping("/request")
    public ResponseEntity<RideAcceptedResponse> requestRideAlias(@RequestBody RideRequestDto requestDto) {
        return rideController.requestRide(requestDto);
    }

    /**
     * Alias for {@code POST /register}. Delegates to {@link DriverController#register}.
     */
    @PostMapping("/register")
    public ResponseEntity<DriverPingAcceptedResponse> registerAlias(@RequestBody DriverPingRequest request) {
        return driverController.register(request);
    }

    /**
     * Alias for {@code GET /drivers/{driverId}/location}.
     * Delegates to {@link DriverController#getDriverLocation}.
     */
    @GetMapping("/drivers/{driverId}/location")
    public ResponseEntity<?> driverLocationAlias(
            @PathVariable("driverId") String driverId) {
        return driverController.getDriverLocation(driverId);
    }
}
