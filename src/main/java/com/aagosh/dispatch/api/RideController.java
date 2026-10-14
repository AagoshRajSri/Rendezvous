package com.aagosh.dispatch.api;

import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.matching.DispatchCostConfig;
import com.aagosh.dispatch.matching.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * REST controller for ride dispatch request submission.
 *
 * <h2>Asynchronous event publication (Phase 5)</h2>
 * <p>{@code POST /request} now publishes the validated {@link RideRequestDto} to the
 * {@value KafkaTopics#RIDE_REQUESTS} Kafka topic and returns <em>HTTP 202 Accepted</em>.
 * The response does <strong>not</strong> contain a driver assignment.
 *
 * <p>Matching is performed asynchronously by {@code RideRequestConsumer}, which invokes
 * the configured {@link Matcher} and commits a driver claim via
 * {@code DriverState.tryClaim()}. A future phase will expose the assignment result through
 * a status endpoint or push notification.
 *
 * <h2>Synchronous acknowledgement</h2>
 * <p>The controller blocks on the broker acknowledgement up to
 * {@value #SEND_TIMEOUT_SECONDS} seconds. If the broker does not acknowledge, a
 * {@link KafkaPublishException} is thrown, mapped to HTTP 503 by
 * {@link GlobalExceptionHandler}. A 202 is never returned unless the broker confirmed
 * receipt.
 */
@RestController
public class RideController {

    private static final Logger log = LoggerFactory.getLogger(RideController.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, RideRequestDto> kafkaTemplate;

    @Autowired
    public RideController(KafkaTemplate<String, RideRequestDto> kafkaTemplate) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
    }

    /**
     * Submits a ride dispatch request.
     *
     * <p>Validates the payload and publishes to {@code ride-requests} using
     * {@code requestId} as the message key. Returns HTTP 202 Accepted on success,
     * HTTP 400 on validation failure, HTTP 503 if the broker is unavailable.
     */
    @PostMapping("/request")
    public ResponseEntity<RideAcceptedResponse> requestRide(@RequestBody RideRequestDto requestDto) {
        if (requestDto == null) {
            throw new InvalidRequestException("Request body must not be null");
        }
        requestDto.validate();

        String requestId = requestDto.resolveId();
        publish(requestId, requestDto);

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(new RideAcceptedResponse(
                        requestId,
                        "Ride request accepted and queued for matching"));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Publishes {@code event} to the {@code ride-requests} topic using {@code requestId}
     * as the message key (ensures partition affinity per request ID; simplifies
     * idempotency tracking in the consumer).
     *
     * @param requestId partition key
     * @param event     the validated ride request payload
     * @throws KafkaPublishException if the broker does not acknowledge within the timeout
     */
    private void publish(String requestId, RideRequestDto event) {
        try {
            SendResult<String, RideRequestDto> result = kafkaTemplate
                    .send(KafkaTopics.RIDE_REQUESTS, requestId, event)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.debug("Published ride request: requestId={}, partition={}, offset={}",
                    requestId,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException("Kafka send interrupted for request: " + requestId, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new KafkaPublishException("Kafka send failed for request: " + requestId, e);
        }
    }
}
