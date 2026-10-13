package com.aagosh.dispatch.api;

import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;

/**
 * REST controller for driver registration and location telemetry.
 *
 * <h2>Caveat: Chronological ordering</h2>
 * <p>Location telemetry updates have no chronological or event-time ordering guarantee.
 * Stored driver coordinates reflect whichever compare-and-set committed last.
 * Stale telemetry rejection requires monotonic client sequence numbers or partitioned
 * message brokers, which are deferred to subsequent architectural phases.
 */
@RestController
public class DriverController {

    private final DriverRegistry driverRegistry;

    public DriverController(DriverRegistry driverRegistry) {
        this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
    }

    /**
     * Ingests a driver location ping.
     *
     * <p>Behavior depends on the optional {@code action} field in the payload:
     * <ul>
     *   <li>{@link PingAction#REGISTER}: Attempts registration; returns 409 Conflict if duplicate.</li>
     *   <li>{@link PingAction#UPDATE}: Attempts location update; returns 404 Not Found if unknown.</li>
     *   <li>{@link PingAction#AUTO} (or omitted): Registers if new (201 Created), or updates
     *       location while preserving authoritative {@link DriverState} (200 OK).</li>
     * </ul>
     */
    @PostMapping("/ping")
    public ResponseEntity<DriverPingResponse> ping(@RequestBody DriverPingRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Request body must not be null");
        }
        request.validate();

        GeoPoint location = new GeoPoint(request.latitude(), request.longitude());
        PingAction action = request.resolveAction();

        return switch (action) {
            case REGISTER -> executeRegistration(request.driverId(), location);
            case UPDATE -> executeUpdate(request.driverId(), location);
            case AUTO -> executeAuto(request.driverId(), location);
        };
    }

    /**
     * Explicit driver registration endpoint.
     *
     * <p>Registers a driver with an initial location. Returns 201 Created on success,
     * or 409 Conflict if a driver with the same ID already exists.
     */
    @PostMapping("/register")
    public ResponseEntity<DriverPingResponse> register(@RequestBody DriverPingRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Request body must not be null");
        }
        request.validate();

        GeoPoint location = new GeoPoint(request.latitude(), request.longitude());
        return executeRegistration(request.driverId(), location);
    }

    private ResponseEntity<DriverPingResponse> executeRegistration(String driverId, GeoPoint location) {
        try {
            Driver newDriver = new Driver(driverId, location, new DriverState());
            driverRegistry.register(newDriver);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new DriverPingResponse(
                            driverId,
                            "REGISTERED",
                            location.latitude(),
                            location.longitude(),
                            MatchStatus.AVAILABLE,
                            "Driver registered successfully"
                    ));
        } catch (IllegalStateException ex) {
            throw new DriverAlreadyExistsException("Driver already registered: " + driverId);
        }
    }

    private ResponseEntity<DriverPingResponse> executeUpdate(String driverId, GeoPoint location) {
        try {
            driverRegistry.updateLocation(driverId, location);
            Driver updated = driverRegistry.findById(driverId)
                    .orElseThrow(() -> new DriverNotFoundException("No driver registered with ID: " + driverId));
            return ResponseEntity.ok(new DriverPingResponse(
                    driverId,
                    "UPDATED",
                    location.latitude(),
                    location.longitude(),
                    updated.state().getStatus(),
                    "Driver location updated successfully"
            ));
        } catch (IllegalStateException ex) {
            throw new DriverNotFoundException("No driver registered with ID: " + driverId);
        }
    }

    private ResponseEntity<DriverPingResponse> executeAuto(String driverId, GeoPoint location) {
        Optional<Driver> existing = driverRegistry.findById(driverId);
        if (existing.isPresent()) {
            return executeUpdate(driverId, location);
        } else {
            try {
                return executeRegistration(driverId, location);
            } catch (DriverAlreadyExistsException ex) {
                // If a race occurred and another thread registered between findById and register,
                // fall back to update without resetting state.
                return executeUpdate(driverId, location);
            }
        }
    }
}
