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
        this.matcher            = Objects.requireNonNull(matcher, "matcher");
        this.driverRegistry     = Objects.requireNonNull(driverRegistry, "driverRegistry");
        this.freshnessConfig    = Objects.requireNonNull(freshnessConfig, "freshnessConfig");
        this.costConfig         = Objects.requireNonNull(costConfig, "costConfig");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
    }

    /**
     * Processes a single ride request event from the {@code ride-requests} topic.
     *
     * @param record the Kafka record; payload is a {@link RideRequestDto}
     * @param ack    manual acknowledgement handle; called on success or deliberate skip
     */
    @KafkaListener(
            topics = KafkaTopics.RIDE_REQUESTS,
            groupId = "${spring.kafka.consumer.group-id:dispatch-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onRideRequest(ConsumerRecord<String, RideRequestDto> record, Acknowledgment ack) {
        RideRequestDto event = record.value();

        // ---- Guard: null or unparseable payload (e.g. poison pill from ErrorHandlingDeserializer) ----
        if (event == null) {
            String errorDetail = extractDeserializationError(record);
            log.warn("Received null or unparseable ride request record at topic={} partition={} offset={} key={}{}; acknowledging and skipping",
                    record.topic(), record.partition(), record.offset(), record.key(),
                    errorDetail != null ? " (" + errorDetail + ")" : "");
            ack.acknowledge();
            return;
        }

        // ---- Guard: invalid payload ----
        String requestId;
        try {
            event.validate();
            requestId = event.resolveId();
        } catch (Exception ex) {
            log.error("Invalid ride request event at topic={} partition={} offset={} key={}: {}",
                    record.topic(), record.partition(), record.offset(), record.key(), ex.getMessage());
            ack.acknowledge();
            return;
        }

        // ---- In-memory idempotency check and in-flight reservation (fast path) ----
        if (!reserveInFlight(requestId)) {
            log.info("Duplicate ride request {} already processed or in-flight; acknowledging without re-matching",
                    requestId);
            ack.acknowledge();
            return;
        }

        // ---- Matching & Persistence Lifecycle ----
        try {
            GeoPoint pickup = new GeoPoint(event.latitude(), event.longitude());
            RideRequest domainRequest = new RideRequest(requestId, pickup);

            // Step 2: Check durable request state before matching
            Optional<String> durableStatus = persistenceService.getRequestStatus(requestId);
            if (durableStatus.isPresent()) {
                String status = durableStatus.get();
                if (AssignmentPersistenceService.STATUS_MATCHED.equals(status)
                        || AssignmentPersistenceService.STATUS_NO_DRIVERS.equals(status)
                        || "NO_DRIVER".equals(status)) {
                    log.info("Ride request {} already durably finalized with status {}; acknowledging duplicate",
                            requestId, status);
                    markProcessed(requestId);
                    ack.acknowledge();
                    return;
                }
            }

            // Step 4: Driver matching — only consider fresh, verified-timestamp drivers
            List<Driver> drivers = driverRegistry.snapshotFresh(freshnessConfig);
            CostFunction costFunction = new DispatchCostFunction(costConfig, List.of(domainRequest));

            List<Assignment> assignments = matcher.match(
                    List.of(domainRequest), drivers, costFunction);

            if (assignments.isEmpty()) {
                // No driver available — record terminal outcome transactionally and acknowledge.
                try {
                    persistenceService.persistNoDrivers(domainRequest);
                } catch (Exception ex) {
                    if (persistenceService.isRequestFinalized(requestId)) {
                        log.info("Ride request {} was finalized concurrently; acknowledging duplicate", requestId);
                        markProcessed(requestId);
                        ack.acknowledge();
                        return;
                    }
                    releaseInFlight(requestId);
                    log.error("Failed to persist NO_DRIVERS for request {}: {}", requestId, ex.getMessage(), ex);
                    throw ex;
                }

                log.info("No driver available for ride request {}; recorded NO_DRIVERS", requestId);
                markProcessed(requestId);
                ack.acknowledge();
                return;
            }

            // Step 5: Assignment persistence with driver-claim compensation
            Assignment assignment = assignments.get(0);
            Driver matchedDriver = assignment.driver();

            try {
                persistenceService.persistMatchedAssignment(domainRequest, assignment);
            } catch (Exception ex) {
                log.error("Persistence failed for request {} with driver {}: {}",
                        requestId, matchedDriver.id(), ex.getMessage(), ex);

                // Ambiguous failure reconciliation:
                // Check if the assignment was actually committed to PostgreSQL despite the exception.
                Optional<AssignmentEntity> existingAssignment = persistenceService.findAssignment(requestId);
                if (existingAssignment.isPresent()) {
                    String persistedDriverId = existingAssignment.get().getDriverId();
                    if (persistedDriverId.equals(matchedDriver.id())) {
                        log.info("Assignment for request {} and driver {} committed; acknowledging duplicate",
                                requestId, matchedDriver.id());
                    } else {
                        // Another competing delivery won with a different driver: release our claim!
                        boolean released = matchedDriver.state().releaseClaim(requestId);
                        log.info("Request {} already assigned to driver {}; released our claim on driver {} (released={})",
                                requestId, persistedDriverId, matchedDriver.id(), released);
                    }
                    markProcessed(requestId);
                    ack.acknowledge();
                    return;
                }

                // Rollback confirmed: release the driver claim safely with ownership check
                boolean released = matchedDriver.state().releaseClaim(requestId);
                log.warn("Released driver claim for driver {} and request {} (claimReleased={})",
                        matchedDriver.id(), requestId, released);

                releaseInFlight(requestId);
                throw ex; // Propagate so Kafka error handler / retry can act
            }

            // Step 6: After confirmed commit, update in-memory idempotency state and acknowledge Kafka
            log.info("Ride request {} matched to driver {} (cost={}); transaction committed",
                    requestId,
                    assignment.driver().id(),
                    assignment.cost());
            markProcessed(requestId);
            ack.acknowledge();

        } catch (Exception ex) {
            // Processing failed (non-compensation path): release in-flight reservation so
            // Kafka error handler retries can re-attempt without false-duplicate suppression.
            // Note: if releaseInFlight was already called in a compensation branch above,
            // this call is a no-op (idempotent — the ID is simply absent from the set).
            releaseInFlight(requestId);
            log.error("Failed to process ride request {} at topic={} partition={} offset={} key={}: {}",
                    requestId, record.topic(), record.partition(), record.offset(), record.key(),
                    ex.getMessage(), ex);
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
