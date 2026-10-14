package com.aagosh.dispatch.api;

import com.aagosh.dispatch.config.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
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

    public DriverController(KafkaTemplate<String, DriverPingRequest> kafkaTemplate) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
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

        // Force REGISTER semantics regardless of what the caller supplied in the action field.
        DriverPingRequest registerRequest = new DriverPingRequest(
                request.driverId(),
                request.latitude(),
                request.longitude(),
                PingAction.REGISTER);
        publish(registerRequest.driverId(), registerRequest);
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(new DriverPingAcceptedResponse(
                        request.driverId(),
                        "Driver registration accepted and queued for processing"));
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
