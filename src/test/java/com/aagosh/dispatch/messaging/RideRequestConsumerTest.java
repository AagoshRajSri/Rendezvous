package com.aagosh.dispatch.messaging;

import com.aagosh.dispatch.api.RideRequestDto;
import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.matching.AuctionMatcher;
import com.aagosh.dispatch.matching.CostFunction;
import com.aagosh.dispatch.matching.DispatchCostConfig;
import com.aagosh.dispatch.matching.Matcher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RideRequestConsumerTest {

    private DriverRegistry driverRegistry;
    private Matcher mockMatcher;
    private DispatchCostConfig costConfig;
    private RideRequestConsumer consumer;
    private Acknowledgment acknowledgment;

    @BeforeEach
    void setUp() {
        driverRegistry = new DriverRegistry();
        mockMatcher = mock(Matcher.class);
        costConfig = DispatchCostConfig.DEFAULT;
        consumer = new RideRequestConsumer(mockMatcher, driverRegistry, costConfig);
        acknowledgment = mock(Acknowledgment.class);
    }

    private ConsumerRecord<String, RideRequestDto> record(String key, RideRequestDto value) {
        return new ConsumerRecord<>(KafkaTopics.RIDE_REQUESTS, 0, 200L, key, value);
    }

    @Test
    @DisplayName("1. Valid ride request delegates to injected Matcher with domain RideRequest and driver snapshot")
    void validRideRequestDelegatesToMatcher() {
        Driver driver = new Driver("D100", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(driver);
        driver.state().tryClaim("REQ-1");

        RideRequest domainRequest = new RideRequest("REQ-1", new GeoPoint(17.3860, 78.4870));
        Assignment assignment = new Assignment(domainRequest, driver, 0.05);

        when(mockMatcher.match(any(), any(), any())).thenReturn(List.of(assignment));

        RideRequestDto dto = new RideRequestDto("REQ-1", null, 17.3860, 78.4870);
        consumer.onRideRequest(record("REQ-1", dto), acknowledgment);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RideRequest>> reqCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Driver>> driverCaptor = ArgumentCaptor.forClass(List.class);

        verify(mockMatcher).match(reqCaptor.capture(), driverCaptor.capture(), any(CostFunction.class));

        assertEquals(1, reqCaptor.getValue().size());
        assertEquals("REQ-1", reqCaptor.getValue().get(0).id());
        assertEquals(1, driverCaptor.getValue().size());
        assertEquals("D100", driverCaptor.getValue().get(0).id());

        verify(acknowledgment).acknowledge();
        assertTrue(consumer.isProcessed("REQ-1"));
    }

    @Test
    @DisplayName("2. No-match outcome is logged and acknowledged without throwing")
    void noMatchOutcomeAcknowledged() {
        when(mockMatcher.match(any(), any(), any())).thenReturn(List.of());

        RideRequestDto dto = new RideRequestDto("REQ-NO-FLEET", null, 17.3860, 78.4870);
        consumer.onRideRequest(record("REQ-NO-FLEET", dto), acknowledgment);

        verify(acknowledgment).acknowledge();
        assertTrue(consumer.isProcessed("REQ-NO-FLEET"));
    }

    @Test
    @DisplayName("3. Duplicate ride request with same requestId is acknowledged without re-matching")
    void duplicateRideRequestFilteredByIdempotencySet() {
        when(mockMatcher.match(any(), any(), any())).thenReturn(List.of());

        RideRequestDto dto = new RideRequestDto("REQ-DUP", null, 17.3860, 78.4870);

        // First delivery
        consumer.onRideRequest(record("REQ-DUP", dto), acknowledgment);
        verify(mockMatcher, times(1)).match(any(), any(), any());
        verify(acknowledgment, times(1)).acknowledge();

        // Second delivery (duplicate)
        consumer.onRideRequest(record("REQ-DUP", dto), acknowledgment);
        // Matcher should NOT be invoked a second time
        verify(mockMatcher, times(1)).match(any(), any(), any());
        // Acknowledged so the partition is not blocked
        verify(acknowledgment, times(2)).acknowledge();
    }

    @Test
    @DisplayName("4. End-to-end matching with real AuctionMatcher commits driver claim in registry")
    void endToEndMatchingWithRealAuctionMatcher() {
        Driver d1 = new Driver("D_REAL", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(d1);

        RideRequestConsumer realConsumer = new RideRequestConsumer(
                new AuctionMatcher(), driverRegistry, costConfig);

        RideRequestDto dto = new RideRequestDto("REQ-REAL", null, 17.3860, 78.4870);
        realConsumer.onRideRequest(record("REQ-REAL", dto), acknowledgment);

        verify(acknowledgment).acknowledge();

        Driver stored = driverRegistry.findById("D_REAL").orElseThrow();
        assertEquals(MatchStatus.CLAIMED, stored.state().getStatus());
        assertEquals("REQ-REAL", stored.state().getClaimedByRequestId());
    }

    @Test
    @DisplayName("5. Processing failure throws exception without acknowledging, allowing error handler retry")
    void processingFailureThrowsAndDoesNotAcknowledge() {
        when(mockMatcher.match(any(), any(), any()))
                .thenThrow(new RuntimeException("Transient matching error"));

        RideRequestDto dto = new RideRequestDto("REQ-ERR", null, 17.3860, 78.4870);

        assertThrows(RuntimeException.class, () ->
                consumer.onRideRequest(record("REQ-ERR", dto), acknowledgment));

        // Offset must NOT be acknowledged so Kafka error handler can retry
        verify(acknowledgment, never()).acknowledge();
        assertFalse(consumer.isProcessed("REQ-ERR"));
    }

    @Test
    @DisplayName("6. Null or invalid payload is skipped and acknowledged to prevent partition stall")
    void nullOrInvalidPayloadSkippedAndAcknowledged() {
        // Null payload
        consumer.onRideRequest(record("KEY", null), acknowledgment);

        // Invalid coordinates
        RideRequestDto invalid = new RideRequestDto("REQ-INV", null, 95.0, 78.0);
        consumer.onRideRequest(record("REQ-INV", invalid), acknowledgment);

        verify(mockMatcher, never()).match(any(), any(), any());
    }

    @Test
    @DisplayName("7. Concurrent duplicate deliveries for the same requestId execute Matcher exactly once")
    void concurrentDuplicateDeliveriesMatchExactlyOnce() throws InterruptedException {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger ackCount = new AtomicInteger();

        // Introduce a slight delay in matching to maximize concurrent window
        when(mockMatcher.match(any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(30);
            return List.of();
        });

        RideRequestDto dto = new RideRequestDto("REQ-CONCURRENT", null, 17.3860, 78.4870);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                Acknowledgment perThreadAck = mock(Acknowledgment.class);
                org.mockito.Mockito.doAnswer(inv -> {
                    ackCount.incrementAndGet();
                    return null;
                }).when(perThreadAck).acknowledge();

                readyLatch.countDown();
                try {
                    startLatch.await();
                    consumer.onRideRequest(record("REQ-CONCURRENT", dto), perThreadAck);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // release all threads simultaneously
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All concurrent tasks should complete within timeout");
        // Matcher MUST be called exactly once despite 8 concurrent delivery attempts
        verify(mockMatcher, times(1)).match(any(), any(), any());
        // All threads must acknowledge (1 on success, 7 on duplicate skip)
        assertEquals(threadCount, ackCount.get());
        assertTrue(consumer.isProcessed("REQ-CONCURRENT"));
    }

    @Test
    @DisplayName("8. Incremental LRU eviction drops only the oldest entry when capacity is exceeded")
    void incrementalLruEvictionDropsOldestEntryWithoutBulkClear() {
        int capacity = RideRequestConsumer.MAX_IDEMPOTENCY_ENTRIES;

        // Insert capacity entries: ID-0 to ID-9999
        for (int i = 0; i < capacity; i++) {
            assertTrue(consumer.recordProcessed("REQ-" + i));
        }
        assertEquals(capacity, consumer.processedCacheSize());
        assertTrue(consumer.isProcessed("REQ-0"));
        assertTrue(consumer.isProcessed("REQ-" + (capacity - 1)));

        // Insert the 10,001st entry: ID-10000
        assertTrue(consumer.recordProcessed("REQ-" + capacity));

        // Capacity is preserved at 10,000 (did NOT clear to 1)
        assertEquals(capacity, consumer.processedCacheSize());
        // Only the oldest entry (REQ-0) was evicted
        assertFalse(consumer.isProcessed("REQ-0"), "Oldest entry should be evicted");
        // Recent entries remain fully protected from duplicates
        assertTrue(consumer.isProcessed("REQ-1"), "Subsequent entries must remain protected");
        assertTrue(consumer.isProcessed("REQ-" + capacity), "Newest entry must be present");
    }

    @Test
    @DisplayName("9. Processing failure evicts request ID from cache allowing retry to succeed")
    void processingFailureEvictsIdempotencyReservationAllowingRetry() {
        RideRequestDto dto = new RideRequestDto("REQ-RETRY", null, 17.3860, 78.4870);

        when(mockMatcher.match(any(), any(), any()))
                .thenThrow(new RuntimeException("Transient failure"))
                .thenReturn(List.of());

        // First delivery: fails
        assertThrows(RuntimeException.class, () ->
                consumer.onRideRequest(record("REQ-RETRY", dto), acknowledgment));

        // Because it failed, it must NOT be marked processed (allowing Kafka redelivery)
        assertFalse(consumer.isProcessed("REQ-RETRY"));
        verify(acknowledgment, never()).acknowledge();

        // Second delivery (simulating Kafka retry): succeeds
        consumer.onRideRequest(record("REQ-RETRY", dto), acknowledgment);

        // Now it is marked processed and acknowledged
        assertTrue(consumer.isProcessed("REQ-RETRY"));
        verify(acknowledgment, times(1)).acknowledge();
        verify(mockMatcher, times(2)).match(any(), any(), any());
    }

    @Test
    @DisplayName("10. An in-flight request cannot be evicted from protection even if 10,000 other requests arrive")
    void inFlightRequestCannotBeEvictedByConcurrentTraffic() {
        // Reserve REQ-INFLIGHT
        assertTrue(consumer.reserveInFlight("REQ-INFLIGHT"));
        assertTrue(consumer.isInFlight("REQ-INFLIGHT"));

        // Simulate 10,000 subsequent requests completing while REQ-INFLIGHT is still in-flight
        int capacity = RideRequestConsumer.MAX_IDEMPOTENCY_ENTRIES;
        for (int i = 0; i < capacity + 5; i++) {
            consumer.recordProcessed("REQ-OTHER-" + i);
        }
        assertEquals(capacity, consumer.processedCacheSize());

        // REQ-INFLIGHT is still in-flight and cannot be duplicated
        assertFalse(consumer.reserveInFlight("REQ-INFLIGHT"),
                "Duplicate of an in-flight request must be rejected even after 10,000 other requests");

        // When matching finishes, transition to processed
        consumer.markProcessed("REQ-INFLIGHT");
        assertFalse(consumer.isInFlight("REQ-INFLIGHT"));
        assertTrue(consumer.isProcessed("REQ-INFLIGHT"));
    }
}
