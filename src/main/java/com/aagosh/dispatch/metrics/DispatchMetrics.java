package com.aagosh.dispatch.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Centralized, type-safe registry and recorder for all domain and operational dispatch metrics.
 *
 * <h2>Design & Safeguards</h2>
 * <ul>
 *   <li><strong>Pre-registered meters:</strong> All standard counters, timers, and distribution summaries
 *       are initialized in the constructor to eliminate map lookup and metric allocation overhead in hot loops.</li>
 *   <li><strong>Strict cardinality bounds:</strong> Metric tags are restricted to small, finite enumerations
 *       ({@code action}, {@code outcome}, {@code result}, {@code type}, {@code state}). High-cardinality values
 *       such as request IDs, driver IDs, or geographic coordinates must <em>never</em> be used as tags.</li>
 *   <li><strong>Naming standards:</strong> Metric names follow standard hierarchical dot notation (e.g.,
 *       {@code dispatch.outcomes}, {@code dispatch.matching.duration}), which the Prometheus registry converts
 *       to canonical snake_case with appropriate suffixes ({@code dispatch_outcomes_total},
 *       {@code dispatch_matching_duration_seconds}).</li>
 * </ul>
 */
@Component
public class DispatchMetrics {

    // Metric names
    public static final String METRIC_INBOUND_ARRIVALS = "dispatch.inbound.arrivals";
    public static final String METRIC_DISPATCH_OUTCOMES = "dispatch.outcomes";
    public static final String METRIC_MATCHING_DURATION = "dispatch.matching.duration";
    public static final String METRIC_BATCH_PROCESSING_DURATION = "dispatch.batch.processing.duration";
    public static final String METRIC_PERSISTENCE_DURATION = "dispatch.persistence.batch.duration";
    public static final String METRIC_BATCH_SIZE = "dispatch.batch.size";
    public static final String METRIC_REDIS_CACHE = "dispatch.redis.cache.requests";
    public static final String METRIC_DUPLICATES_SKIPPED = "dispatch.duplicates.skipped";
    public static final String METRIC_DRIVERS_COUNT = "dispatch.drivers.count";
    public static final String METRIC_DRIVERS_FRESHNESS = "dispatch.drivers.freshness";
    public static final String METRIC_CONSUMER_FAILURES = "dispatch.consumer.failures";
    public static final String METRIC_PERSISTENCE_FAILURES = "dispatch.persistence.failures";
    public static final String METRIC_PERSISTENCE_WRITES = "dispatch.persistence.records.persisted";

    // Standard tag values
    public static final String OUTCOME_MATCHED = "MATCHED";
    public static final String OUTCOME_NO_DRIVERS = "NO_DRIVERS";

    public static final String ACTION_AUTO = "AUTO";
    public static final String ACTION_REGISTER = "REGISTER";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_RIDE_REQUEST = "RIDE_REQUEST";

    public static final String CACHE_HIT = "hit";
    public static final String CACHE_MISS = "miss";
    public static final String CACHE_ERROR = "error";

    public static final String DUP_IN_MEMORY = "in_memory";
    public static final String DUP_DURABLE = "durable";

    public static final String STAGE_PRE_MATCH_LOOKUP = "pre_match_lookup";
    public static final String STAGE_PROCESSING = "processing";
    public static final String STAGE_DESERIALIZATION = "deserialization";
    public static final String STAGE_INVALID_PAYLOAD = "invalid_payload";

    public static final String OP_BATCH = "batch";
    public static final String OP_SINGLE = "single";

    public static final String WRITE_MATCHED = "matched";
    public static final String WRITE_NO_DRIVERS = "no_drivers";
    public static final String WRITE_PENDING = "pending";

    private final MeterRegistry meterRegistry;

    // Pre-registered counters
    private final Map<String, Counter> inboundArrivalCounters = new ConcurrentHashMap<>();
    private final Counter matchedOutcomeCounter;
    private final Counter noDriversOutcomeCounter;
    private final Map<String, Counter> redisCacheCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> duplicateSkippedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> consumerFailureCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> persistenceFailureCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> persistenceWriteCounters = new ConcurrentHashMap<>();

    // Pre-registered timers & summaries
    private final Timer matchingTimer;
    private final Timer batchProcessingTimer;
    private final Timer persistenceTimer;
    private final DistributionSummary batchSizeSummary;

    @Autowired
    public DispatchMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");

        // 1. Inbound arrivals pre-registration
        inboundArrivalCounters.put(ACTION_AUTO, registerArrivalCounter(ACTION_AUTO));
        inboundArrivalCounters.put(ACTION_REGISTER, registerArrivalCounter(ACTION_REGISTER));
        inboundArrivalCounters.put(ACTION_UPDATE, registerArrivalCounter(ACTION_UPDATE));
        inboundArrivalCounters.put(ACTION_RIDE_REQUEST, registerArrivalCounter(ACTION_RIDE_REQUEST));

        // 2. Dispatch outcomes
        this.matchedOutcomeCounter = Counter.builder(METRIC_DISPATCH_OUTCOMES)
                .tag("outcome", OUTCOME_MATCHED)
                .description("Total ride requests matched to an available driver")
                .register(meterRegistry);

        this.noDriversOutcomeCounter = Counter.builder(METRIC_DISPATCH_OUTCOMES)
                .tag("outcome", OUTCOME_NO_DRIVERS)
                .description("Total ride requests finalized with no drivers available")
                .register(meterRegistry);

        // 3. Timers with standard latency percentiles
        this.matchingTimer = Timer.builder(METRIC_MATCHING_DURATION)
                .description("Duration spent evaluating driver matching algorithms")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.batchProcessingTimer = Timer.builder(METRIC_BATCH_PROCESSING_DURATION)
                .description("Total end-to-end duration spent processing a Kafka ride request batch")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        this.persistenceTimer = Timer.builder(METRIC_PERSISTENCE_DURATION)
                .description("Duration of batch persistence transactions in PostgreSQL")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        // 4. Batch size distribution summary
        this.batchSizeSummary = DistributionSummary.builder(METRIC_BATCH_SIZE)
                .description("Observed batch sizes delivered to dispatch consumer")
                .baseUnit("records")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        // 5. Redis cache requests
        redisCacheCounters.put(CACHE_HIT, registerRedisCounter(CACHE_HIT));
        redisCacheCounters.put(CACHE_MISS, registerRedisCounter(CACHE_MISS));
        redisCacheCounters.put(CACHE_ERROR, registerRedisCounter(CACHE_ERROR));

        // 6. Duplicates skipped
        duplicateSkippedCounters.put(DUP_IN_MEMORY, registerDuplicateCounter(DUP_IN_MEMORY));
        duplicateSkippedCounters.put(DUP_DURABLE, registerDuplicateCounter(DUP_DURABLE));

        // 7. Failures
        consumerFailureCounters.put(STAGE_PRE_MATCH_LOOKUP, registerConsumerFailureCounter(STAGE_PRE_MATCH_LOOKUP));
        consumerFailureCounters.put(STAGE_PROCESSING, registerConsumerFailureCounter(STAGE_PROCESSING));
        consumerFailureCounters.put(STAGE_DESERIALIZATION, registerConsumerFailureCounter(STAGE_DESERIALIZATION));
        consumerFailureCounters.put(STAGE_INVALID_PAYLOAD, registerConsumerFailureCounter(STAGE_INVALID_PAYLOAD));

        persistenceFailureCounters.put(OP_BATCH, registerPersistenceFailureCounter(OP_BATCH));
        persistenceFailureCounters.put(OP_SINGLE, registerPersistenceFailureCounter(OP_SINGLE));

        // 8. Persistence writes
        persistenceWriteCounters.put(WRITE_MATCHED, registerPersistenceWriteCounter(WRITE_MATCHED));
        persistenceWriteCounters.put(WRITE_NO_DRIVERS, registerPersistenceWriteCounter(WRITE_NO_DRIVERS));
        persistenceWriteCounters.put(WRITE_PENDING, registerPersistenceWriteCounter(WRITE_PENDING));
    }

    private Counter registerArrivalCounter(String action) {
        return Counter.builder(METRIC_INBOUND_ARRIVALS)
                .tag("action", action)
                .description("Total inbound events received by action type")
                .register(meterRegistry);
    }

    private Counter registerRedisCounter(String result) {
        return Counter.builder(METRIC_REDIS_CACHE)
                .tag("result", result)
                .description("Total queries to Redis location cache by outcome")
                .register(meterRegistry);
    }

    private Counter registerDuplicateCounter(String type) {
        return Counter.builder(METRIC_DUPLICATES_SKIPPED)
                .tag("type", type)
                .description("Total duplicate requests acknowledged without re-matching")
                .register(meterRegistry);
    }

    private Counter registerConsumerFailureCounter(String stage) {
        return Counter.builder(METRIC_CONSUMER_FAILURES)
                .tag("stage", stage)
                .description("Total consumer processing failures by stage")
                .register(meterRegistry);
    }

    private Counter registerPersistenceFailureCounter(String operation) {
        return Counter.builder(METRIC_PERSISTENCE_FAILURES)
                .tag("operation", operation)
                .description("Total database persistence failures by operation type")
                .register(meterRegistry);
    }

    private Counter registerPersistenceWriteCounter(String outcome) {
        return Counter.builder(METRIC_PERSISTENCE_WRITES)
                .tag("outcome", outcome)
                .description("Total durable records persisted by outcome")
                .register(meterRegistry);
    }

    // -------------------------------------------------------------------------
    // Recording API
    // -------------------------------------------------------------------------

    public void recordInboundArrival(String action, int count) {
        if (count <= 0) return;
        Counter counter = inboundArrivalCounters.computeIfAbsent(action, this::registerArrivalCounter);
        counter.increment(count);
    }

    public void recordDispatchOutcome(String outcome, int count) {
        if (count <= 0) return;
        if (OUTCOME_MATCHED.equalsIgnoreCase(outcome)) {
            matchedOutcomeCounter.increment(count);
        } else if (OUTCOME_NO_DRIVERS.equalsIgnoreCase(outcome) || "NO_DRIVER".equalsIgnoreCase(outcome)) {
            noDriversOutcomeCounter.increment(count);
        } else {
            // Guard against unbounded outcome tags by routing unknown outcomes to other
            meterRegistry.counter(METRIC_DISPATCH_OUTCOMES, "outcome", "OTHER").increment(count);
        }
    }

    public void recordMatchingDuration(Duration duration) {
        if (duration != null && !duration.isNegative()) {
            matchingTimer.record(duration);
        }
    }

    public void recordMatchingDuration(long durationNanos) {
        if (durationNanos >= 0) {
            matchingTimer.record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    public void recordPersistenceDuration(Duration duration) {
        if (duration != null && !duration.isNegative()) {
            persistenceTimer.record(duration);
        }
    }

    public void recordPersistenceDuration(long durationNanos) {
        if (durationNanos >= 0) {
            persistenceTimer.record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    public void recordBatchSize(int batchSize) {
        if (batchSize > 0) {
            batchSizeSummary.record(batchSize);
        }
    }

    public void recordRedisCacheRequest(String result) {
        Counter counter = redisCacheCounters.computeIfAbsent(result, this::registerRedisCounter);
        counter.increment();
    }

    public void recordDuplicateSkipped(String type) {
        Counter counter = duplicateSkippedCounters.computeIfAbsent(type, this::registerDuplicateCounter);
        counter.increment();
    }

    public void recordBatchProcessingDuration(Duration duration) {
        if (duration != null && !duration.isNegative()) {
            batchProcessingTimer.record(duration);
        }
    }

    public void recordBatchProcessingDuration(long durationNanos) {
        if (durationNanos >= 0) {
            batchProcessingTimer.record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    public void recordConsumerFailure(String stage) {
        Counter counter = consumerFailureCounters.computeIfAbsent(stage, this::registerConsumerFailureCounter);
        counter.increment();
    }

    public void recordPersistenceFailure(String operation) {
        Counter counter = persistenceFailureCounters.computeIfAbsent(operation, this::registerPersistenceFailureCounter);
        counter.increment();
    }

    public void recordPersistenceWrites(String outcome, int count) {
        if (count <= 0) return;
        Counter counter = persistenceWriteCounters.computeIfAbsent(outcome, this::registerPersistenceWriteCounter);
        counter.increment(count);
    }

    /**
     * Registers a dynamic gauge for fleet driver states (e.g. AVAILABLE, CLAIMED, ALL).
     *
     * @param state         state label (e.g., "AVAILABLE", "CLAIMED", "TOTAL")
     * @param countSupplier functional provider of the current gauge value
     */
    public void registerDriverStateGauge(String state, Supplier<Number> countSupplier) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(countSupplier, "countSupplier");

        Gauge.builder(METRIC_DRIVERS_COUNT, countSupplier)
                .tag("state", state)
                .description("Current registered driver fleet count by state")
                .register(meterRegistry);
    }

    /**
     * Registers a dynamic gauge for fleet driver freshness (e.g. fresh, stale).
     *
     * @param status        freshness status label (e.g. "fresh", "stale")
     * @param countSupplier functional provider of the current gauge value
     */
    public void registerDriverFreshnessGauge(String status, Supplier<Number> countSupplier) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(countSupplier, "countSupplier");

        Gauge.builder(METRIC_DRIVERS_FRESHNESS, countSupplier)
                .tag("status", status)
                .description("Current registered driver count by freshness")
                .register(meterRegistry);
    }

    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
}
