package com.aagosh.dispatch.api;

import com.aagosh.dispatch.cache.RedisLocationCache;
import com.aagosh.dispatch.cache.RedisLocationCache.LocationResult;
import com.aagosh.dispatch.config.DriverFreshnessConfig;
import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * REST controller for driver registration and location telemetry.
 *
 * <h2>Asynchronous event publication</h2>
 * <p>Both {@code POST /ping} and {@code POST /register} now publish the validated
 * {@link DriverPingRequest} to the {@value KafkaTopics#DRIVER_PINGS} Kafka topic after
 * input validation. The response is <em>HTTP 202 Accepted</em>, indicating that the event
 * was durably received by the broker. The actual driver registration or location update
 * is performed asynchronously by {@code DriverPingConsumer}.
 *
 * <h2>Synchronous publication acknowledgement</h2>
 * <p>The controller blocks on the Kafka {@link java.util.concurrent.Future} up to
 * {@value #SEND_TIMEOUT_SECONDS} seconds. If the broker does not acknowledge within that
 * window, a {@link KafkaPublishException} is raised, which is mapped to HTTP 503 by
 * {@link GlobalExceptionHandler}. This ensures the controller never silently absorbs a
 * failed publish.
 *
 * <h2>Caveat: Chronological ordering</h2>
 * <p>Location telemetry updates have no chronological or event-time ordering guarantee.
 * Stored driver coordinates reflect whichever compare-and-set committed last.
 * Stale telemetry rejection requires monotonic client sequence numbers or partitioned
 * message brokers, which are deferred to subsequent architectural phases.
 */
@RestController
public class DriverController {

    private static final Logger log = LoggerFactory.getLogger(DriverController.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, DriverPingRequest> kafkaTemplate;
    private final DriverRegistry driverRegistry;
    private final RedisLocationCache redisLocationCache;
    private final DriverFreshnessConfig freshnessConfig;

    public DriverController(KafkaTemplate<String, DriverPingRequest> kafkaTemplate,
                            DriverRegistry driverRegistry,
                            RedisLocationCache redisLocationCache,
                            DriverFreshnessConfig freshnessConfig) {
        this.kafkaTemplate      = Objects.requireNonNull(kafkaTemplate,      "kafkaTemplate");
        this.driverRegistry     = Objects.requireNonNull(driverRegistry,     "driverRegistry");
        this.redisLocationCache = Objects.requireNonNull(redisLocationCache, "redisLocationCache");
        this.freshnessConfig    = Objects.requireNonNull(freshnessConfig,    "freshnessConfig");
    }

    /**
     * Ingests a driver location ping and publishes it to {@code driver-pings}.
     *
     * <p>Validates the payload, then publishes to Kafka. Returns HTTP 202 Accepted on
     * successful publication, HTTP 400 on validation failure, HTTP 503 if the broker
     * is unavailable.
     *
     * <p>The {@code action} field (AUTO / REGISTER / UPDATE) is forwarded as-is inside
     * the published event and interpreted by {@code DriverPingConsumer}.
     */
    @PostMapping("/ping")
    public ResponseEntity<DriverPingAcceptedResponse> ping(@RequestBody DriverPingRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Request body must not be null");
        }
        request.validate();
        publish(request.driverId(), request);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(new DriverPingAcceptedResponse(
                        request.driverId(),
                        "Driver ping accepted and queued for processing"));
    }

    /**
     * Explicit driver registration: publishes a REGISTER-action ping to {@code driver-pings}.
     *
     * <p>Returns HTTP 202 Accepted on successful publication, HTTP 400 on validation
     * failure, HTTP 503 if the broker is unavailable.
     */
    @PostMapping("/register")
    public ResponseEntity<DriverPingAcceptedResponse> register(@RequestBody DriverPingRequest request) {
        if (request == null) {
            throw new InvalidRequestException("Request body must not be null");
        }
        request.validate();

        // Force REGISTER semantics regardless of what the caller supplied in the action field,
        // while preserving client-provided capturedAt timestamp.
        DriverPingRequest registerRequest = new DriverPingRequest(
                request.driverId(),
                request.latitude(),
                request.longitude(),
                PingAction.REGISTER,
                request.capturedAt());
        publish(registerRequest.driverId(), registerRequest);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(new DriverPingAcceptedResponse(
                        request.driverId(),
                        "Driver registration accepted and queued for processing"));
    }

    // -------------------------------------------------------------------------
    // Fleet query (Phase 11.2)
    // -------------------------------------------------------------------------

    /**
     * Returns a snapshot of fresh, registered drivers eligible for dispatch.
     *
     * <p>Queries the authoritative in-memory {@link DriverRegistry#snapshotFresh(DriverFreshnessConfig)}.
     * Filters out stale drivers (older than configured staleness threshold) and clock-skewed timestamps.
     * Exposes strictly map-required coordinates, driver ID, and status.
     *
     * <p>Compatible URL routes:
     * <ul>
     *   <li>{@code GET /drivers}</li>
     *   <li>{@code GET /api/drivers}</li>
     * </ul>
     */
    @GetMapping({"/drivers", "/api/drivers"})
    public ResponseEntity<java.util.List<EligibleDriverResponse>> getEligibleDrivers() {
        java.util.List<Driver> freshDrivers = driverRegistry.snapshotFresh(freshnessConfig);
        java.util.List<EligibleDriverResponse> responses = freshDrivers.stream()
                .map(d -> new EligibleDriverResponse(
                        d.id(),
                        d.location().latitude(),
                        d.location().longitude(),
                        d.state().getStatus().name(),
                        Instant.ofEpochMilli(d.updatedAt())))
                .toList();

        return ResponseEntity.ok(responses);
    }

    // -------------------------------------------------------------------------
    // Location query
    // -------------------------------------------------------------------------

    /**
     * Returns the last-known location of a registered driver.
     *
     * <p>The in-memory {@link DriverRegistry} is the authoritative source.
     * Coordinates are never fabricated: if no verified location exists the response
     * has {@code fresh == false} and {@code latitude}/{@code longitude} are {@code null}.
     *
     * <p>HTTP status:
     * <ul>
     *   <li>200 – driver known and location is fresh.</li>
     *   <li>404 – driver not registered.</li>
     *   <li>409 – driver registered but location is stale, absent, or unverified.</li>
     * </ul>
     *
     * <p>Compatible URL aliases:
     * <ul>
     *   <li>{@code GET /drivers/{driverId}/location}</li>
     *   <li>{@code GET /api/drivers/{driverId}/location} (registered in RequestStatusController)</li>
     * </ul>
     */
    @GetMapping({"/drivers/{driverId}/location"})
    public ResponseEntity<?> getDriverLocation(
            @PathVariable("driverId") String driverId) {

        if (driverId == null || driverId.isBlank()) {
            throw new InvalidRequestException("driverId must not be null or blank");
        }

        // 1. Query Redis location cache
        LocationResult redisResult = redisLocationCache.getLocation(driverId);

        if (redisResult instanceof LocationResult.Found found) {
            long ts = found.updatedAt();
            long nowMs = System.currentTimeMillis();
            long stalenessMs = freshnessConfig.stalenessSeconds() * 1000L;
            long maxFutureSkewMs = freshnessConfig.maxFutureSkewSeconds() * 1000L;
            boolean fresh = (ts > 0L) && (ts >= nowMs - stalenessMs) && (ts <= nowMs + maxFutureSkewMs);

            DriverLocationResponse body = new DriverLocationResponse(
                    driverId,
                    found.location().latitude(),
                    found.location().longitude(),
                    Instant.ofEpochMilli(ts),
                    fresh);

            log.debug("Redis location hit: driverId={} fresh={} ts={}", driverId, fresh, ts);
            HttpStatus status = fresh ? HttpStatus.OK : HttpStatus.CONFLICT;
            return ResponseEntity.status(status).body(body);
        }

        if (redisResult instanceof LocationResult.Miss) {
            // 2. Cache miss: explicit in-memory fallback to DriverRegistry
            Optional<Driver> driverOpt = driverRegistry.findById(driverId);
            if (driverOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ApiErrorResponse(
                                HttpStatus.NOT_FOUND.value(),
                                "Not Found",
                                "No driver registered with id: " + driverId));
            }

            Driver driver = driverOpt.get();
            long   ts     = driver.updatedAt();
            boolean fresh = driverRegistry.isFresh(driverId, freshnessConfig);

            Double  lat = (ts > 0L) ? driver.location().latitude()  : null;
            Double  lon = (ts > 0L) ? driver.location().longitude() : null;
            Instant updatedAt = (ts > 0L) ? Instant.ofEpochMilli(ts) : null;

            DriverLocationResponse body = new DriverLocationResponse(
                    driverId, lat, lon, updatedAt, fresh);

            log.debug("Fallback in-memory location (miss): driverId={} fresh={} ts={}", driverId, fresh, ts);
            HttpStatus status = fresh ? HttpStatus.OK : HttpStatus.CONFLICT;
            return ResponseEntity.status(status).body(body);
        }

        if (redisResult instanceof LocationResult.RedisError error) {
            // 3. Redis infrastructure failure: do NOT report as ordinary missing driver (404)!
            // Fall back to authoritative in-memory registry if the driver is known
            Optional<Driver> driverOpt = driverRegistry.findById(driverId);
            if (driverOpt.isPresent()) {
                Driver driver = driverOpt.get();
                long   ts     = driver.updatedAt();
                boolean fresh = driverRegistry.isFresh(driverId, freshnessConfig);

                Double  lat = (ts > 0L) ? driver.location().latitude()  : null;
                Double  lon = (ts > 0L) ? driver.location().longitude() : null;
                Instant updatedAt = (ts > 0L) ? Instant.ofEpochMilli(ts) : null;

                DriverLocationResponse body = new DriverLocationResponse(
                        driverId, lat, lon, updatedAt, fresh);

                log.warn("Fallback in-memory location during Redis error ({}) for driverId={}",
                        error.message(), driverId);
                HttpStatus status = fresh ? HttpStatus.OK : HttpStatus.CONFLICT;
                return ResponseEntity.status(status).body(body);
            }

            // Driver is NOT known in memory either: report 503 Service Unavailable, NEVER 404!
            log.warn("Redis location query failed and driver not in memory: driverId={} error={}",
                    driverId, error.message());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiErrorResponse(
                            HttpStatus.SERVICE_UNAVAILABLE.value(),
                            "Service Unavailable",
                            "Driver location cache is temporarily unavailable: " + error.message()));
        }

        throw new IllegalStateException("Unhandled LocationResult variant: " + redisResult);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Publishes {@code event} to the {@code driver-pings} topic using {@code driverId}
     * as the message key (ensures partition affinity per driver).
     *
     * <p>Blocks until the broker acknowledges the send (up to {@value #SEND_TIMEOUT_SECONDS}
     * seconds). Throws {@link KafkaPublishException} on timeout or execution failure so
     * that {@link GlobalExceptionHandler} can map it to HTTP 503.
     *
     * @param driverId partition key (driver ID)
     * @param event    the validated ping payload to publish
     * @throws KafkaPublishException if the broker does not acknowledge within the timeout
     */
    private void publish(String driverId, DriverPingRequest event) {
        try {
            SendResult<String, DriverPingRequest> result = kafkaTemplate
                    .send(KafkaTopics.DRIVER_PINGS, driverId, event)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.debug("Published driver ping: driverId={}, partition={}, offset={}",
                    driverId,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException("Kafka send interrupted for driver: " + driverId, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new KafkaPublishException("Kafka send failed for driver: " + driverId, e);
        }
    }
}
