package com.aagosh.dispatch.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchMetricsTest {

    private MeterRegistry meterRegistry;
    private DispatchMetrics dispatchMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        dispatchMetrics = new DispatchMetrics(meterRegistry);
    }

    @Test
    @DisplayName("1. Constructor rejects null MeterRegistry")
    void constructorRejectsNullRegistry() {
        assertThrows(NullPointerException.class, () -> new DispatchMetrics(null));
    }

    @Test
    @DisplayName("2. Pre-registers core counters, timers, and summaries")
    void preRegistersCoreMeters() {
        assertNotNull(meterRegistry.find(DispatchMetrics.METRIC_INBOUND_ARRIVALS).tag("action", DispatchMetrics.ACTION_AUTO).counter());
        assertNotNull(meterRegistry.find(DispatchMetrics.METRIC_INBOUND_ARRIVALS).tag("action", DispatchMetrics.ACTION_RIDE_REQUEST).counter());
        assertNotNull(meterRegistry.find(DispatchMetrics.METRIC_DISPATCH_OUTCOMES).tag("outcome", DispatchMetrics.OUTCOME_MATCHED).counter());
        assertNotNull(meterRegistry.find(DispatchMetrics.METRIC_DISPATCH_OUTCOMES).tag("outcome", DispatchMetrics.OUTCOME_NO_DRIVERS).counter());
        assertNotNull(meterRegistry.find(DispatchMetrics.METRIC_MATCHING_DURATION).timer());
        assertNotNull(meterRegistry.find(DispatchMetrics.METRIC_PERSISTENCE_DURATION).timer());
        assertNotNull(meterRegistry.find(DispatchMetrics.METRIC_BATCH_SIZE).summary());
        assertNotNull(meterRegistry.find(DispatchMetrics.METRIC_REDIS_CACHE).tag("result", DispatchMetrics.CACHE_HIT).counter());
        assertNotNull(meterRegistry.find(DispatchMetrics.METRIC_DUPLICATES_SKIPPED).tag("type", DispatchMetrics.DUP_IN_MEMORY).counter());
    }

    @Test
    @DisplayName("3. recordInboundArrival increments arrival counters correctly")
    void recordInboundArrivalIncrementsCounter() {
        dispatchMetrics.recordInboundArrival(DispatchMetrics.ACTION_AUTO, 5);
        dispatchMetrics.recordInboundArrival(DispatchMetrics.ACTION_AUTO, 3);
        dispatchMetrics.recordInboundArrival(DispatchMetrics.ACTION_RIDE_REQUEST, 10);

        Counter autoCounter = meterRegistry.find(DispatchMetrics.METRIC_INBOUND_ARRIVALS)
                .tag("action", DispatchMetrics.ACTION_AUTO).counter();
        Counter rideCounter = meterRegistry.find(DispatchMetrics.METRIC_INBOUND_ARRIVALS)
                .tag("action", DispatchMetrics.ACTION_RIDE_REQUEST).counter();

        assertNotNull(autoCounter);
        assertNotNull(rideCounter);
        assertEquals(8.0, autoCounter.count());
        assertEquals(10.0, rideCounter.count());
    }

    @Test
    @DisplayName("4. recordInboundArrival ignores zero or negative counts")
    void recordInboundArrivalIgnoresNonPositiveCounts() {
        dispatchMetrics.recordInboundArrival(DispatchMetrics.ACTION_AUTO, 0);
        dispatchMetrics.recordInboundArrival(DispatchMetrics.ACTION_AUTO, -5);

        Counter autoCounter = meterRegistry.find(DispatchMetrics.METRIC_INBOUND_ARRIVALS)
                .tag("action", DispatchMetrics.ACTION_AUTO).counter();
        assertNotNull(autoCounter);
        assertEquals(0.0, autoCounter.count());
    }

    @Test
    @DisplayName("5. recordDispatchOutcome routes MATCHED, NO_DRIVERS, and unexpected outcomes properly")
    void recordDispatchOutcomeRoutesOutcomes() {
        dispatchMetrics.recordDispatchOutcome("MATCHED", 4);
        dispatchMetrics.recordDispatchOutcome("NO_DRIVERS", 12);
        dispatchMetrics.recordDispatchOutcome("NO_DRIVER", 2); // Legacy alias routes to NO_DRIVERS
        dispatchMetrics.recordDispatchOutcome("UNKNOWN_OUTCOME", 3); // Guard routes to OTHER

        Counter matched = meterRegistry.find(DispatchMetrics.METRIC_DISPATCH_OUTCOMES)
                .tag("outcome", DispatchMetrics.OUTCOME_MATCHED).counter();
        Counter noDrivers = meterRegistry.find(DispatchMetrics.METRIC_DISPATCH_OUTCOMES)
                .tag("outcome", DispatchMetrics.OUTCOME_NO_DRIVERS).counter();
        Counter other = meterRegistry.find(DispatchMetrics.METRIC_DISPATCH_OUTCOMES)
                .tag("outcome", "OTHER").counter();

        assertNotNull(matched);
        assertNotNull(noDrivers);
        assertNotNull(other);
        assertEquals(4.0, matched.count());
        assertEquals(14.0, noDrivers.count());
        assertEquals(3.0, other.count());
    }

    @Test
    @DisplayName("6. recordMatchingDuration records time and invocation count")
    void recordMatchingDurationRecordsTime() {
        dispatchMetrics.recordMatchingDuration(Duration.ofMillis(25));
        dispatchMetrics.recordMatchingDuration(15_000_000L); // 15ms in nanos

        Timer timer = meterRegistry.find(DispatchMetrics.METRIC_MATCHING_DURATION).timer();
        assertNotNull(timer);
        assertEquals(2, timer.count());
        assertEquals(40.0, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.5);
    }

    @Test
    @DisplayName("7. recordPersistenceDuration records batch transaction time")
    void recordPersistenceDurationRecordsTime() {
        dispatchMetrics.recordPersistenceDuration(Duration.ofMillis(12));
        dispatchMetrics.recordPersistenceDuration(8_000_000L); // 8ms in nanos

        Timer timer = meterRegistry.find(DispatchMetrics.METRIC_PERSISTENCE_DURATION).timer();
        assertNotNull(timer);
        assertEquals(2, timer.count());
        assertEquals(20.0, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.5);
    }

    @Test
    @DisplayName("8. recordBatchSize records batch record count in distribution summary")
    void recordBatchSizeRecordsDistribution() {
        dispatchMetrics.recordBatchSize(1);
        dispatchMetrics.recordBatchSize(5);
        dispatchMetrics.recordBatchSize(10);
        dispatchMetrics.recordBatchSize(0); // Ignored

        DistributionSummary summary = meterRegistry.find(DispatchMetrics.METRIC_BATCH_SIZE).summary();
        assertNotNull(summary);
        assertEquals(3, summary.count());
        assertEquals(16.0, summary.totalAmount(), 0.001);
        assertEquals(10.0, summary.max(), 0.001);
    }

    @Test
    @DisplayName("9. recordRedisCacheRequest increments cache hit, miss, and error counters")
    void recordRedisCacheRequestIncrementsCounters() {
        dispatchMetrics.recordRedisCacheRequest(DispatchMetrics.CACHE_HIT);
        dispatchMetrics.recordRedisCacheRequest(DispatchMetrics.CACHE_HIT);
        dispatchMetrics.recordRedisCacheRequest(DispatchMetrics.CACHE_MISS);
        dispatchMetrics.recordRedisCacheRequest(DispatchMetrics.CACHE_ERROR);

        Counter hit = meterRegistry.find(DispatchMetrics.METRIC_REDIS_CACHE)
                .tag("result", DispatchMetrics.CACHE_HIT).counter();
        Counter miss = meterRegistry.find(DispatchMetrics.METRIC_REDIS_CACHE)
                .tag("result", DispatchMetrics.CACHE_MISS).counter();
        Counter err = meterRegistry.find(DispatchMetrics.METRIC_REDIS_CACHE)
                .tag("result", DispatchMetrics.CACHE_ERROR).counter();

        assertNotNull(hit);
        assertNotNull(miss);
        assertNotNull(err);
        assertEquals(2.0, hit.count());
        assertEquals(1.0, miss.count());
        assertEquals(1.0, err.count());
    }

    @Test
    @DisplayName("10. recordDuplicateSkipped increments in-memory and durable duplicate counters")
    void recordDuplicateSkippedIncrementsCounters() {
        dispatchMetrics.recordDuplicateSkipped(DispatchMetrics.DUP_IN_MEMORY);
        dispatchMetrics.recordDuplicateSkipped(DispatchMetrics.DUP_DURABLE);
        dispatchMetrics.recordDuplicateSkipped(DispatchMetrics.DUP_DURABLE);

        Counter inMem = meterRegistry.find(DispatchMetrics.METRIC_DUPLICATES_SKIPPED)
                .tag("type", DispatchMetrics.DUP_IN_MEMORY).counter();
        Counter durable = meterRegistry.find(DispatchMetrics.METRIC_DUPLICATES_SKIPPED)
                .tag("type", DispatchMetrics.DUP_DURABLE).counter();

        assertNotNull(inMem);
        assertNotNull(durable);
        assertEquals(1.0, inMem.count());
        assertEquals(2.0, durable.count());
    }

    @Test
    @DisplayName("11. registerDriverStateGauge dynamically reflects live value from supplier")
    void registerDriverStateGaugeReflectsLiveValues() {
        AtomicInteger availableDrivers = new AtomicInteger(50);
        dispatchMetrics.registerDriverStateGauge("AVAILABLE", availableDrivers::get);

        Gauge gauge = meterRegistry.find(DispatchMetrics.METRIC_DRIVERS_COUNT)
                .tag("state", "AVAILABLE").gauge();

        assertNotNull(gauge);
        assertEquals(50.0, gauge.value());

        availableDrivers.set(42);
        assertEquals(42.0, gauge.value());
    }

    @Test
    @DisplayName("12. recordBatchProcessingDuration records duration accurately")
    void recordBatchProcessingDurationRecordsDuration() {
        dispatchMetrics.recordBatchProcessingDuration(Duration.ofMillis(75));
        dispatchMetrics.recordBatchProcessingDuration(25_000_000L); // 25ms in nanos

        Timer timer = meterRegistry.find(DispatchMetrics.METRIC_BATCH_PROCESSING_DURATION).timer();
        assertNotNull(timer);
        assertEquals(2, timer.count());
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 99.0);
    }

    @Test
    @DisplayName("13. recordConsumerFailure increments consumer failure counters by stage")
    void recordConsumerFailureIncrementsCounters() {
        dispatchMetrics.recordConsumerFailure(DispatchMetrics.STAGE_PRE_MATCH_LOOKUP);
        dispatchMetrics.recordConsumerFailure(DispatchMetrics.STAGE_PROCESSING);
        dispatchMetrics.recordConsumerFailure(DispatchMetrics.STAGE_PROCESSING);

        Counter lookup = meterRegistry.find(DispatchMetrics.METRIC_CONSUMER_FAILURES)
                .tag("stage", DispatchMetrics.STAGE_PRE_MATCH_LOOKUP).counter();
        Counter proc = meterRegistry.find(DispatchMetrics.METRIC_CONSUMER_FAILURES)
                .tag("stage", DispatchMetrics.STAGE_PROCESSING).counter();

        assertNotNull(lookup);
        assertNotNull(proc);
        assertEquals(1.0, lookup.count());
        assertEquals(2.0, proc.count());
    }

    @Test
    @DisplayName("14. recordPersistenceFailure increments persistence failure counters by operation")
    void recordPersistenceFailureIncrementsCounters() {
        dispatchMetrics.recordPersistenceFailure(DispatchMetrics.OP_BATCH);
        dispatchMetrics.recordPersistenceFailure(DispatchMetrics.OP_SINGLE);

        Counter batch = meterRegistry.find(DispatchMetrics.METRIC_PERSISTENCE_FAILURES)
                .tag("operation", DispatchMetrics.OP_BATCH).counter();
        Counter single = meterRegistry.find(DispatchMetrics.METRIC_PERSISTENCE_FAILURES)
                .tag("operation", DispatchMetrics.OP_SINGLE).counter();

        assertNotNull(batch);
        assertNotNull(single);
        assertEquals(1.0, batch.count());
        assertEquals(1.0, single.count());
    }

    @Test
    @DisplayName("15. recordPersistenceWrites increments persisted records counter by outcome")
    void recordPersistenceWritesIncrementsCounters() {
        dispatchMetrics.recordPersistenceWrites(DispatchMetrics.WRITE_MATCHED, 5);
        dispatchMetrics.recordPersistenceWrites(DispatchMetrics.WRITE_NO_DRIVERS, 10);
        dispatchMetrics.recordPersistenceWrites(DispatchMetrics.WRITE_MATCHED, 0); // ignored

        Counter matched = meterRegistry.find(DispatchMetrics.METRIC_PERSISTENCE_WRITES)
                .tag("outcome", DispatchMetrics.WRITE_MATCHED).counter();
        Counter noDrivers = meterRegistry.find(DispatchMetrics.METRIC_PERSISTENCE_WRITES)
                .tag("outcome", DispatchMetrics.WRITE_NO_DRIVERS).counter();

        assertNotNull(matched);
        assertNotNull(noDrivers);
        assertEquals(5.0, matched.count());
        assertEquals(10.0, noDrivers.count());
    }

    @Test
    @DisplayName("16. registerDriverFreshnessGauge dynamically reflects live freshness value")
    void registerDriverFreshnessGaugeReflectsLiveValues() {
        AtomicInteger freshDrivers = new AtomicInteger(100);
        dispatchMetrics.registerDriverFreshnessGauge("fresh", freshDrivers::get);

        Gauge gauge = meterRegistry.find(DispatchMetrics.METRIC_DRIVERS_FRESHNESS)
                .tag("status", "fresh").gauge();

        assertNotNull(gauge);
        assertEquals(100.0, gauge.value());

        freshDrivers.set(95);
        assertEquals(95.0, gauge.value());
    }
}
