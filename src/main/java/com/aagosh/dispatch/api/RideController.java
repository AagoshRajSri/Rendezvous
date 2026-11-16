package com.aagosh.dispatch.api;

import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.matching.DispatchCostConfig;
import com.aagosh.dispatch.matching.Matcher;
import com.aagosh.dispatch.service.RideCreationService;
import com.aagosh.dispatch.service.RideCreationService.RideCreationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * REST controller for ride dispatch request submission.
 *
 * <h2>Durable Idempotency (Phase 1)</h2>
 * <p>Supports client-provided {@code Idempotency-Key} headers up to 128 characters.
 * Concurrent requests sharing the same key are deduplicated at the PostgreSQL storage
 * boundary via an isolated transaction, ensuring:
 * <ul>
 *   <li>Exactly 1 row created in {@code ride_requests}</li>
 *   <li>Exactly 1 event published to Kafka</li>
 *   <li>100% deterministic response payloads (identical {@code requestId}) across all callers</li>
 *   <li>Zero HTTP 500 errors or poisoned Hibernate sessions under high contention</li>
 * </ul>
 */
@RestController
public class RideController {

    private static final Logger log = LoggerFactory.getLogger(RideController.class);
    private static final long   SEND_TIMEOUT_SECONDS = 5;
    private static final int    MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final KafkaTemplate<String, RideRequestDto> kafkaTemplate;

    @Nullable
    private final RideCreationService rideCreationService;

    @Autowired
    public RideController(KafkaTemplate<String, RideRequestDto> kafkaTemplate,
                          @Nullable RideCreationService rideCreationService) {
        this.kafkaTemplate       = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
        this.rideCreationService = rideCreationService;
    }

    public RideController(KafkaTemplate<String, RideRequestDto> kafkaTemplate) {
        this(kafkaTemplate, null);
    }

    /**
     * Submits a ride dispatch request with an optional {@code Idempotency-Key} header.
     */
    @PostMapping("/request")
    public ResponseEntity<RideAcceptedResponse> requestRide(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) RideRequestDto requestDto) {

        // Idempotency-Key validation first
        if (idempotencyKey != null) {
            if (idempotencyKey.isBlank()) {
                throw new InvalidRequestException("Idempotency-Key header must not be blank");
            }
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw new InvalidRequestException(
                        "Idempotency-Key header must not exceed " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
            }
        }

        if (requestDto == null) {
            throw new InvalidRequestException("Request body must not be null");
        }
        requestDto.validate();

        final String requestId;
        final boolean shouldPublish;

        if (rideCreationService != null) {
            RideCreationResult result = rideCreationService.resolveOrCreate(idempotencyKey, requestDto);
            requestId     = result.requestId();
            shouldPublish = result.shouldPublish();
        } else {
            requestId     = requestDto.resolveId();
            shouldPublish = true;
        }

        if (shouldPublish) {
            publish(requestId, requestDto);
        } else {
            log.debug("Idempotent retry detected for key={}; skipping duplicate Kafka publish", idempotencyKey);
        }

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(new RideAcceptedResponse(
                        requestId,
                        requestId,
                        "Ride request accepted and queued for matching"));
    }

    /**
     * Backward-compatible overload for callers omitting the {@code Idempotency-Key} header.
     */
    public ResponseEntity<RideAcceptedResponse> requestRide(RideRequestDto requestDto) {
        return requestRide(null, requestDto);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

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
