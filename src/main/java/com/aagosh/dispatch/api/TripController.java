package com.aagosh.dispatch.api;

import com.aagosh.dispatch.domain.TripStatus;
import com.aagosh.dispatch.persistence.LifecycleOutcome;
import com.aagosh.dispatch.persistence.TripEntity;
import com.aagosh.dispatch.persistence.TripLifecycleService;
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

import java.util.Objects;
import java.util.Optional;

/**
 * REST controller exposing trip lifecycle operations and status queries.
 *
 * <h2>Supported routes</h2>
 * <p>Supports both the proposed {@code /api/trips} route and root-relative {@code /trips}
 * to maintain complete consistency with the existing non-prefixed REST endpoints
 * ({@code /ping}, {@code /register}, {@code /request}).
 *
 * <h2>Security & Authentication Disclaimer</h2>
 * <p><strong>Notice:</strong> {@code driverId} supplied in requests is unauthenticated and
 * serves strictly as a demonstration-level ownership and consistency check against the
 * persistent {@code trips} record. It does not provide cryptographic or verified proof of
 * driver identity.
 *
 * <h2>Thin controller architecture</h2>
 * <p>This controller performs input validation and maps domain results to HTTP responses.
 * All state machine rules, transactional database persistence, and in-memory synchronization
 * remain authoritative within {@link TripLifecycleService}.
 */
@RestController
@RequestMapping({"/api/trips", "/trips"})
public class TripController {

    private static final Logger log = LoggerFactory.getLogger(TripController.class);

    private final TripLifecycleService tripLifecycleService;

    public TripController(TripLifecycleService tripLifecycleService) {
        this.tripLifecycleService = Objects.requireNonNull(tripLifecycleService, "tripLifecycleService");
    }

    /**
     * Retrieves the current persisted trip status and timestamps.
     *
     * @param requestId the ride-request identifier
     * @return 200 OK with {@link TripResponse} if found; 404 Not Found otherwise
     */
    @GetMapping("/{requestId}")
    public ResponseEntity<?> getTripStatus(@PathVariable("requestId") String requestId) {
        validateRequestId(requestId);

        Optional<TripEntity> tripOpt = tripLifecycleService.findTrip(requestId);
        if (tripOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiErrorResponse(
                            HttpStatus.NOT_FOUND.value(),
                            "Not Found",
                            "Trip not found for requestId: " + requestId));
        }

        return ResponseEntity.ok(TripResponse.from(tripOpt.get()));
    }

    /**
     * Transitions trip from {@code ASSIGNED} to {@code EN_ROUTE}.
     */
    @PostMapping("/{requestId}/en-route")
    public ResponseEntity<?> beginEnRoute(@PathVariable("requestId") String requestId,
                                          @RequestBody TripLifecycleRequest request) {
        validateRequestId(requestId);
        validateRequestBody(request);

        LifecycleOutcome outcome = tripLifecycleService.beginEnRoute(requestId, request.driverId());
        return mapOutcome(outcome, requestId, request.driverId(), TripStatus.EN_ROUTE);
    }

    /**
     * Transitions trip from {@code EN_ROUTE} to {@code IN_PROGRESS} (passenger pickup).
     */
    @PostMapping("/{requestId}/start")
    public ResponseEntity<?> startTrip(@PathVariable("requestId") String requestId,
                                       @RequestBody TripLifecycleRequest request) {
        validateRequestId(requestId);
        validateRequestBody(request);

        LifecycleOutcome outcome = tripLifecycleService.beginPickup(requestId, request.driverId());
        return mapOutcome(outcome, requestId, request.driverId(), TripStatus.IN_PROGRESS);
    }

    /**
     * Transitions trip from {@code IN_PROGRESS} to {@code COMPLETED} (passenger drop-off).
     */
    @PostMapping("/{requestId}/complete")
    public ResponseEntity<?> completeTrip(@PathVariable("requestId") String requestId,
                                          @RequestBody TripLifecycleRequest request) {
        validateRequestId(requestId);
        validateRequestBody(request);

        LifecycleOutcome outcome = tripLifecycleService.completeTrip(requestId, request.driverId());
        return mapOutcome(outcome, requestId, request.driverId(), TripStatus.COMPLETED);
    }

    /**
     * Cancels the trip. Permitted only prior to passenger pickup (from {@code ASSIGNED} or {@code EN_ROUTE}).
     */
    @PostMapping("/{requestId}/cancel")
    public ResponseEntity<?> cancelTrip(@PathVariable("requestId") String requestId,
                                        @RequestBody TripLifecycleRequest request) {
        validateRequestId(requestId);
        validateRequestBody(request);

        LifecycleOutcome outcome = tripLifecycleService.cancelTrip(requestId, request.driverId());
        return mapOutcome(outcome, requestId, request.driverId(), TripStatus.CANCELLED);
    }

    // -------------------------------------------------------------------------
    // Outcome mapping & Validation
    // -------------------------------------------------------------------------

    private ResponseEntity<?> mapOutcome(LifecycleOutcome outcome,
                                         String requestId,
                                         String driverId,
                                         TripStatus targetStatus) {
        if (outcome == null) {
            throw new IllegalStateException("TripLifecycleService returned null outcome");
        }
        return switch (outcome) {
            case TRANSITIONED -> ResponseEntity.ok(
                    new TripLifecycleResponse(
                            requestId,
                            driverId,
                            targetStatus.name(),
                            outcome.name(),
                            "Trip successfully transitioned to " + targetStatus.name()));

            case ALREADY_IN_TARGET_STATE -> ResponseEntity.ok(
                    new TripLifecycleResponse(
                            requestId,
                            driverId,
                            targetStatus.name(),
                            outcome.name(),
                            "Trip is already in status " + targetStatus.name()));

            case INVALID_TRANSITION -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiErrorResponse(
                            HttpStatus.CONFLICT.value(),
                            "Conflict",
                            "Invalid trip transition: trip " + requestId + " cannot transition to " + targetStatus.name()));

            case OWNERSHIP_MISMATCH -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiErrorResponse(
                            HttpStatus.CONFLICT.value(),
                            "Conflict",
                            "Driver " + driverId + " does not own trip " + requestId));

            case TRIP_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiErrorResponse(
                            HttpStatus.NOT_FOUND.value(),
                            "Not Found",
                            "Trip not found for requestId: " + requestId));

            case DRIVER_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiErrorResponse(
                            HttpStatus.NOT_FOUND.value(),
                            "Not Found",
                            "Driver " + driverId + " not found in registry"));

            case DRIVER_STATE_DIVERGED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiErrorResponse(
                            HttpStatus.SERVICE_UNAVAILABLE.value(),
                            "Service Unavailable",
                            "Trip transition to " + targetStatus.name() + " was committed to the database, but driver in-memory state diverged. "
                                    + "Do NOT retry blindly; check GET /api/trips/" + requestId + " to verify committed status. State will reconcile automatically on next driver ping."));
        };
    }

    private void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new InvalidRequestException("requestId must not be null or blank");
        }
        try {
            String decoded = java.net.URLDecoder.decode(requestId, java.nio.charset.StandardCharsets.UTF_8);
            if (decoded.isBlank()) {
                throw new InvalidRequestException("requestId must not be null or blank");
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("requestId must not be null or blank");
        }
    }

    private void validateRequestBody(TripLifecycleRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Request body must not be null");
        }
        request.validate();
    }
}
