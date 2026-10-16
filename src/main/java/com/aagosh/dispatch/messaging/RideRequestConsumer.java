package com.aagosh.dispatch.messaging;

import com.aagosh.dispatch.api.RideRequestDto;
import com.aagosh.dispatch.config.DriverFreshnessConfig;
import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.matching.CostFunction;
import com.aagosh.dispatch.matching.DispatchCostConfig;
import com.aagosh.dispatch.matching.DispatchCostFunction;
import com.aagosh.dispatch.matching.Matcher;
import com.aagosh.dispatch.persistence.AssignmentEntity;
import com.aagosh.dispatch.persistence.AssignmentPersistenceService;
import com.aagosh.dispatch.persistence.PersistOutcome;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.serializer.SerializationUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Kafka consumer for ride dispatch request events.
 *
 * <h2>Topic</h2>
 * <p>Listens on {@value KafkaTopics#RIDE_REQUESTS}. The consumer group is configured via
 * {@code spring.kafka.consumer.group-id} in {@code application.properties}.
 *
 * <h2>Processing flow</h2>
 * <ol>
 *   <li>Deserialize the {@link RideRequestDto} from the Kafka record.</li>
 *   <li>Re-validate to guard against events published outside the REST layer.</li>
 *   <li>Check the in-memory idempotency set (fast path, avoids DB round-trip).</li>
 *   <li>Persist a {@code PENDING} ride-request row. A duplicate-key outcome means
 *       the request was previously committed — skip matching.</li>
 *   <li>Build a {@link RideRequest} domain object and take a driver snapshot.</li>
 *   <li>Invoke the configured {@link Matcher} via its {@link Matcher#match} method.</li>
 *   <li>If an assignment is present, persist it. On database failure, release the
 *       in-memory driver claim via {@link com.aagosh.dispatch.domain.DriverState#releaseClaim}.</li>
 *   <li>If no assignment is found, update the ride-request status to {@code NO_DRIVER}.</li>
 *   <li>Mark the request ID as processed in the in-memory cache and acknowledge the offset.</li>
 * </ol>
 *
 * <h2>In-memory idempotency</h2>
 * <p>Kafka delivers messages at least once. A redelivered ride-request event could
 * cause the same request to be matched twice, assigning two drivers to one ride or
 * attempting to claim an already-claimed driver for a different request.
 *
 * <p>To prevent this within a single running application instance, a bounded
 * {@link LinkedHashMap} LRU cache tracks in-flight and successfully processed
 * {@code requestId} values:
 * <ul>
 *   <li><strong>Atomic check-and-reserve:</strong> {@link #tryMarkProcessed(String)}
 *       atomically tests and records the ID before matching begins. Concurrent deliveries
 *       of the same ID cannot both enter matching.</li>
 *   <li><strong>Continuous LRU eviction:</strong> The cache is bounded to
 *       {@value #MAX_IDEMPOTENCY_ENTRIES} entries using access-ordered eviction
 *       ({@code removeEldestEntry}). Exceeding capacity evicts only the oldest entry,
 *       maintaining unbroken duplicate protection for recent requests.</li>
 *   <li><strong>Failure recovery:</strong> If matching throws an unhandled exception,
 *       {@link #releaseInFlight(String)} removes the ID so that Kafka error handler retries
 *       are not falsely dropped as duplicates.</li>
 *   <li><strong>Durable idempotency:</strong> The {@link AssignmentPersistenceService}
 *       persists the ride-request row before matching, providing crash-safe duplicate
 *       detection via a PostgreSQL UNIQUE constraint on {@code request_id}.</li>
 * </ul>
 *
 * <h2>Acknowledgement</h2>
 * <p>Offset acknowledgement is called after successful processing or deliberate skip.
 * If the method throws, the error handler in {@code KafkaConfig} retries up to twice
 * before skipping the record.
 *
 * <h2>Claim atomicity and compensation</h2>
 * <p>Driver claims are performed by the injected {@link Matcher} via
 * {@code DriverState.tryClaim()}. If the subsequent database write fails,
 * {@code DriverState.releaseClaim(requestId)} is called to release the in-memory
 * claim, returning the driver to AVAILABLE. The method is ownership-safe: it only
 * releases if the driver is still claimed by exactly this request.
 */
@Component
public class RideRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(RideRequestConsumer.class);

    /**
     * Maximum number of request IDs held in the in-memory idempotency cache.
     *
     * <p>When this limit is exceeded, the least-recently-accessed entry is evicted
     * continuously, maintaining deduplication protection for all recent entries.
     */
    static final int MAX_IDEMPOTENCY_ENTRIES = 10_000;

    private final Matcher matcher;
    private final DriverRegistry driverRegistry;
    private final DriverFreshnessConfig freshnessConfig;
    private final DispatchCostConfig costConfig;
    private final AssignmentPersistenceService persistenceService;
    private final AdaptiveWindowController windowController;

    /**
     * Dedicated monitor lock guarding all accesses to both in-flight and processed request caches.
     */
    private final Object idempotencyLock = new Object();

    /**
     * In-flight request IDs currently undergoing matching.
     * Stored outside the LRU cache so an in-flight request can NEVER be evicted
     * before matching completes, preventing concurrent duplicate entry.
     */
    private final Set<String> inFlightRequests = new HashSet<>();

    /**
     * Bounded in-memory LRU cache of successfully processed {@code requestId} values.
     * Access-ordered with continuous FIFO/LRU eviction: when size exceeds
     * {@value #MAX_IDEMPOTENCY_ENTRIES}, only the oldest completed entry is dropped.
     */
    private final Map<String, Boolean> processedRequests =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_IDEMPOTENCY_ENTRIES;
                }
            };

    public RideRequestConsumer(Matcher matcher,
                               DriverRegistry driverRegistry,
                               DriverFreshnessConfig freshnessConfig,
                               DispatchCostConfig costConfig,
                               AssignmentPersistenceService persistenceService) {
        this(matcher, driverRegistry, freshnessConfig, costConfig, persistenceService, Optional.empty());
    }

    public RideRequestConsumer(Matcher matcher,
                               DriverRegistry driverRegistry,
                               DriverFreshnessConfig freshnessConfig,
                               DispatchCostConfig costConfig,
                               AssignmentPersistenceService persistenceService,
                               AdaptiveWindowController windowController) {
        this(matcher, driverRegistry, freshnessConfig, costConfig, persistenceService, Optional.ofNullable(windowController));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RideRequestConsumer(Matcher matcher,
                               DriverRegistry driverRegistry,
                               DriverFreshnessConfig freshnessConfig,
                               DispatchCostConfig costConfig,
                               AssignmentPersistenceService persistenceService,
                               Optional<AdaptiveWindowController> windowController) {
        this.matcher            = Objects.requireNonNull(matcher, "matcher");
        this.driverRegistry     = Objects.requireNonNull(driverRegistry, "driverRegistry");
        this.freshnessConfig    = Objects.requireNonNull(freshnessConfig, "freshnessConfig");
        this.costConfig         = Objects.requireNonNull(costConfig, "costConfig");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
        this.windowController   = (windowController != null && windowController.isPresent())
                ? windowController.get()
                : new AdaptiveWindowController();
    }

    /**
     * Returns the underlying adaptive window controller for telemetry inspection.
     *
     * @return the controller instance
     */
    public AdaptiveWindowController getWindowController() {
        return windowController;
    }

    /**
     * Backward-compatible single-record entry point that delegates to the batch handler.
     *
     * @param record the Kafka record; payload is a {@link RideRequestDto}
     * @param ack    manual acknowledgement handle; called on success or deliberate skip
     */
     public void onRideRequest(ConsumerRecord<String, RideRequestDto> record, Acknowledgment ack) {
         onRideRequests(record != null ? List.of(record) : List.of(), ack);
     }

    /**
     * Processes a batch of ride request events from the {@code ride-requests} topic.
     *
     * <p><strong>Lifecycle guarantees:</strong>
     * <ul>
     *   <li><strong>Thread-safety:</strong> Operates entirely on the Kafka consumer thread; no sleeps or
     *       artificial blocking inside the listener.</li>
     *   <li><strong>Order preservation:</strong> Records are delivered per-partition; offsets are committed
     *       monotonically only after PostgreSQL commits.</li>
     *   <li><strong>Durable idempotency:</strong> Malformed, unparseable (poison pills), or duplicate records
     *       are safely filtered without aborting the batch.</li>
     *   <li><strong>Atomic persistence & compensation:</strong> Uses {@link AssignmentPersistenceService#persistBatch}
     *       to atomically persist matched and unmatched outcomes. On failure, {@link AssignmentPersistenceService#reconcileAndCompensateClaims}
     *       safely reconciles ambiguous outcomes before releasing driver claims.</li>
     * </ul>
     *
     * @param records the list of Kafka records from the current poll batch
     * @param ack     manual acknowledgement handle; called strictly after database commit
     */
    @KafkaListener(
            topics = KafkaTopics.RIDE_REQUESTS,
            groupId = "${spring.kafka.consumer.group-id:dispatch-service}",
            containerFactory = "batchKafkaListenerContainerFactory"
    )
    public void onRideRequests(List<ConsumerRecord<String, RideRequestDto>> records, Acknowledgment ack) {
        if (records == null || records.isEmpty()) {
            if (ack != null) {
                ack.acknowledge();
            }
            return;
        }

        // Passive controller observation: track batch size and arrival rate metrics.
        // Must never delay, alter Kafka pacing, or fail request processing.
        try {
            windowController.recordArrivals(records.size());
        } catch (Exception ex) {
            log.warn("Failed to record batch telemetry in AdaptiveWindowController: {}", ex.getMessage());
        }

        List<RideRequest> validBatchRequests = new ArrayList<>();
        List<String> newlyReservedInFlight = new ArrayList<>();

        for (ConsumerRecord<String, RideRequestDto> record : records) {
            RideRequestDto event = record.value();

            // Guard: null or unparseable payload (e.g. poison pill from ErrorHandlingDeserializer)
            if (event == null) {
                String errorDetail = extractDeserializationError(record);
                log.warn("Received null or unparseable ride request record at topic={} partition={} offset={} key={}{}; skipping",
                        record.topic(), record.partition(), record.offset(), record.key(),
                        errorDetail != null ? " (" + errorDetail + ")" : "");
                continue;
            }

            // Guard: invalid payload
            String requestId;
            try {
                event.validate();
                requestId = event.resolveId();
            } catch (Exception ex) {
                log.error("Invalid ride request event at topic={} partition={} offset={} key={}: {}; skipping",
                        record.topic(), record.partition(), record.offset(), record.key(), ex.getMessage());
                continue;
            }

            // In-memory idempotency check and in-flight reservation (fast path)
            if (!reserveInFlight(requestId)) {
                log.info("Duplicate ride request {} already processed or in-flight; skipping re-match",
                        requestId);
                continue;
            }
            newlyReservedInFlight.add(requestId);

            // Check durable request state before matching
            Optional<String> durableStatus = persistenceService.getRequestStatus(requestId);
            if (durableStatus.isPresent()) {
                String status = durableStatus.get();
                if (AssignmentPersistenceService.STATUS_MATCHED.equals(status)
                        || AssignmentPersistenceService.STATUS_NO_DRIVERS.equals(status)
                        || "NO_DRIVER".equals(status)) {
                    log.info("Ride request {} already durably finalized with status {}; acknowledging duplicate",
                            requestId, status);
                    markProcessed(requestId);
                    newlyReservedInFlight.remove(requestId);
                    continue;
                }
            }

            GeoPoint pickup = new GeoPoint(event.latitude(), event.longitude());
            validBatchRequests.add(new RideRequest(requestId, pickup));
        }

        // If no fresh requests require matching (e.g., batch only contained poison pills, invalid payloads, or duplicates)
        if (validBatchRequests.isEmpty()) {
            if (ack != null) {
                ack.acknowledge();
            }
            return;
        }

        List<Assignment> assignments = List.of();
        try {
            // Driver matching: snapshot fresh drivers and evaluate spatial cost function across the entire batch
            List<Driver> drivers = driverRegistry.snapshotFresh(freshnessConfig);
            CostFunction costFunction = new DispatchCostFunction(costConfig, validBatchRequests);

            assignments = matcher.match(validBatchRequests, drivers, costFunction);

            // Step 5: Atomic batch persistence
            persistenceService.persistBatch(validBatchRequests, assignments);

            // Step 6: On confirmed commit, mark in-flight requests as processed and acknowledge Kafka
            for (String reqId : newlyReservedInFlight) {
                markProcessed(reqId);
            }

            log.info("Batch processed successfully: {} requests ({} matched, {} no-drivers); committing offset",
                    validBatchRequests.size(), assignments.size(), validBatchRequests.size() - assignments.size());

            if (ack != null) {
                ack.acknowledge();
            }

        } catch (Exception ex) {
            log.error("Failed to process batch of {} requests: {}",
                    validBatchRequests.size(), ex.getMessage(), ex);

            // Reconcile ambiguous durable state and compensate driver claims
            persistenceService.reconcileAndCompensateClaims(assignments);

            // Check if all requests in the batch were actually durably finalized despite the error
            boolean allFinalized = true;
            for (RideRequest req : validBatchRequests) {
                boolean reqFinalized = false;
                try {
                    reqFinalized = persistenceService.isRequestFinalized(req.id());
                } catch (Exception queryEx) {
                    log.warn("Failed to check durable finalization for request {}: {}; assuming unfinalized",
                            req.id(), queryEx.getMessage());
                    reqFinalized = false;
                }
                if (!reqFinalized) {
                    allFinalized = false;
                    break;
                }
            }

            if (allFinalized) {
                // Ambiguous commit confirmed for all requests: mark processed and acknowledge
                for (String reqId : newlyReservedInFlight) {
                    markProcessed(reqId);
                }
                if (ack != null) {
                    ack.acknowledge();
                }
                log.info("Batch persistence failed but all {} requests were reconciled as durably finalized; acknowledged duplicate batch",
                        validBatchRequests.size());
                return;
            }

            // Rollback confirmed for at least one record: release in-flight reservations
            // for unfinalized requests so Kafka error handler retries can re-attempt
            for (RideRequest req : validBatchRequests) {
                boolean reqFinalized = false;
                try {
                    reqFinalized = persistenceService.isRequestFinalized(req.id());
                } catch (Exception queryEx) {
                    reqFinalized = false;
                }
                if (reqFinalized) {
                    markProcessed(req.id());
                } else {
                    releaseInFlight(req.id());
                }
            }

            // Propagate exception so Kafka DefaultErrorHandler seeks back to uncommitted offset
            throw ex;
        }
    }

    // -------------------------------------------------------------------------
    // Idempotency helpers
    // -------------------------------------------------------------------------

    /**
     * Atomically checks whether {@code requestId} is currently in-flight or already processed,
     * and reserves it in the in-flight set if absent.
     *
     * @param requestId the request ID to check and reserve
     * @return {@code true} if newly reserved and matching should proceed;
     *         {@code false} if already present (duplicate)
     */
    boolean reserveInFlight(String requestId) {
        synchronized (idempotencyLock) {
            if (inFlightRequests.contains(requestId) || processedRequests.containsKey(requestId)) {
                return false;
            }
            inFlightRequests.add(requestId);
            return true;
        }
    }

    /**
     * Transitions a request ID from in-flight to processed upon a successful match
     * or deliberate terminal outcome (e.g. no driver available).
     */
    void markProcessed(String requestId) {
        synchronized (idempotencyLock) {
            inFlightRequests.remove(requestId);
            processedRequests.put(requestId, Boolean.TRUE);
        }
    }

    /**
     * Releases an in-flight reservation on processing failure without marking it processed,
     * allowing Kafka error handler retries to re-attempt processing.
     */
    void releaseInFlight(String requestId) {
        synchronized (idempotencyLock) {
            inFlightRequests.remove(requestId);
        }
    }

    /**
     * Records a request ID directly as processed in the LRU cache.
     * Exposed package-private for testing LRU eviction.
     */
    boolean recordProcessed(String requestId) {
        synchronized (idempotencyLock) {
            if (inFlightRequests.contains(requestId) || processedRequests.containsKey(requestId)) {
                return false;
            }
            processedRequests.put(requestId, Boolean.TRUE);
            return true;
        }
    }

    /**
     * Returns {@code true} if the given request ID has been recorded as processed.
     * Exposed package-private for testing.
     */
    boolean isProcessed(String requestId) {
        synchronized (idempotencyLock) {
            return processedRequests.containsKey(requestId);
        }
    }

    /**
     * Returns {@code true} if the given request ID is currently in-flight.
     * Exposed package-private for testing.
     */
    boolean isInFlight(String requestId) {
        synchronized (idempotencyLock) {
            return inFlightRequests.contains(requestId);
        }
    }

    /**
     * Returns the current number of tracked request IDs in the processed LRU cache.
     * Exposed package-private for testing.
     */
    int processedCacheSize() {
        synchronized (idempotencyLock) {
            return processedRequests.size();
        }
    }

    private String extractDeserializationError(ConsumerRecord<?, ?> record) {
        if (record.headers() == null) {
            return null;
        }
        Header header = record.headers().lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER);
        return header != null ? "deserialization failed in ErrorHandlingDeserializer" : null;
    }
}
