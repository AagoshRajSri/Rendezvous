package com.aagosh.dispatch.messaging;

import com.aagosh.dispatch.api.RideRequestDto;
import com.aagosh.dispatch.config.DriverFreshnessConfig;
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
import com.aagosh.dispatch.persistence.AssignmentEntity;
import com.aagosh.dispatch.persistence.AssignmentPersistenceService;
import com.aagosh.dispatch.persistence.PersistOutcome;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
    private AssignmentPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        driverRegistry     = new DriverRegistry();
        mockMatcher        = mock(Matcher.class);
        costConfig         = DispatchCostConfig.DEFAULT;
        persistenceService = mock(AssignmentPersistenceService.class);

        // Default: request is new, persistence succeeds
        when(persistenceService.findFinalizedRequestIdsIn(any())).thenReturn(Set.of());
        when(persistenceService.getRequestStatus(any())).thenReturn(Optional.empty());
        doNothing().when(persistenceService).persistMatchedAssignment(any(), any());
        doNothing().when(persistenceService).persistNoDrivers(any());
        when(persistenceService.persistBatch(any(), any()))
                .thenReturn(new AssignmentPersistenceService.BatchPersistResult(1, 0, 0));
        when(persistenceService.reconcileAndCompensateClaims(any()))
                .thenCallRealMethod();
        when(persistenceService.findAssignment(any())).thenReturn(Optional.empty());
        when(persistenceService.isRequestFinalized(any())).thenReturn(false);

        consumer      = new RideRequestConsumer(mockMatcher, driverRegistry, DriverFreshnessConfig.DEFAULT, costConfig, persistenceService);
        acknowledgment = mock(Acknowledgment.class);
    }

    private ConsumerRecord<String, RideRequestDto> record(String key, RideRequestDto value) {
        return new ConsumerRecord<>(KafkaTopics.RIDE_REQUESTS, 0, 200L, key, value);
    }

    private ConsumerRecord<String, RideRequestDto> record(String key, RideRequestDto value, long offset) {
        return new ConsumerRecord<>(KafkaTopics.RIDE_REQUESTS, 0, offset, key, value);
    }

    /**
     * Registers a driver with a fresh timestamp so {@code snapshotFresh} includes it.
     * Without a positive, recent timestamp the driver would be excluded from production matching.
     */
    private Driver freshDriver(String id, double lat, double lon) {
        long nowMs = System.currentTimeMillis();
        Driver d = new Driver(id, new GeoPoint(lat, lon), new DriverState(), nowMs);
        driverRegistry.register(d);
        return d;
    }

    @Test
    @DisplayName("1. Valid ride request delegates to injected Matcher with domain RideRequest and driver snapshot")
    void validRideRequestDelegatesToMatcher() {
        Driver driver = freshDriver("D100", 17.3850, 78.4867);
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
        Driver d1 = freshDriver("D_REAL", 17.3850, 78.4867);

        RideRequestConsumer realConsumer = new RideRequestConsumer(
                new AuctionMatcher(), driverRegistry, DriverFreshnessConfig.DEFAULT, costConfig, persistenceService);

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

    @Test
    @DisplayName("11. Durable duplicate with MATCHED status in database is acknowledged without rematching")
    void durableDuplicateWithMatchedStatusSkipsMatching() {
        when(persistenceService.findFinalizedRequestIdsIn(any()))
                .thenReturn(Set.of("REQ-DB-MATCHED"));
        when(persistenceService.getRequestStatus("REQ-DB-MATCHED"))
                .thenReturn(Optional.of(AssignmentPersistenceService.STATUS_MATCHED));

        RideRequestDto dto = new RideRequestDto("REQ-DB-MATCHED", null, 17.3860, 78.4870);
        consumer.onRideRequest(record("REQ-DB-MATCHED", dto), acknowledgment);

        verify(mockMatcher, never()).match(any(), any(), any());
        verify(acknowledgment).acknowledge();
        assertTrue(consumer.isProcessed("REQ-DB-MATCHED"));
    }

    @Test
    @DisplayName("12. Durable duplicate with NO_DRIVERS status in database is acknowledged without rematching")
    void durableDuplicateWithNoDriversStatusSkipsMatching() {
        when(persistenceService.findFinalizedRequestIdsIn(any()))
                .thenReturn(Set.of("REQ-DB-NODRIVER"));
        when(persistenceService.getRequestStatus("REQ-DB-NODRIVER"))
                .thenReturn(Optional.of(AssignmentPersistenceService.STATUS_NO_DRIVERS));

        RideRequestDto dto = new RideRequestDto("REQ-DB-NODRIVER", null, 17.3860, 78.4870);
        consumer.onRideRequest(record("REQ-DB-NODRIVER", dto), acknowledgment);

        verify(mockMatcher, never()).match(any(), any(), any());
        verify(acknowledgment).acknowledge();
        assertTrue(consumer.isProcessed("REQ-DB-NODRIVER"));
    }

    @Test
    @DisplayName("13. Assignment persistence failure with confirmed rollback releases driver claim and does not acknowledge")
    void assignmentPersistenceFailureConfirmedRollbackReleasesClaim() {
        Driver driver = new Driver("D-FAIL", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(driver);
        driver.state().tryClaim("REQ-PERSIST-FAIL");

        RideRequest domainRequest = new RideRequest("REQ-PERSIST-FAIL", new GeoPoint(17.3860, 78.4870));
        Assignment assignment = new Assignment(domainRequest, driver, 0.1);

        when(mockMatcher.match(any(), any(), any())).thenReturn(List.of(assignment));
        doThrow(new RuntimeException("Database error")).when(persistenceService).persistBatch(any(), any());
        // Rollback confirmed: assignment does not exist in DB
        when(persistenceService.findAssignment("REQ-PERSIST-FAIL")).thenReturn(Optional.empty());
        when(persistenceService.isRequestFinalized("REQ-PERSIST-FAIL")).thenReturn(false);

        RideRequestDto dto = new RideRequestDto("REQ-PERSIST-FAIL", null, 17.3860, 78.4870);

        assertThrows(RuntimeException.class, () ->
                consumer.onRideRequest(record("REQ-PERSIST-FAIL", dto), acknowledgment));

        // Driver claim must be released so driver is available for retry
        assertEquals(MatchStatus.AVAILABLE, driver.state().getStatus());
        verify(acknowledgment, never()).acknowledge();
        assertFalse(consumer.isProcessed("REQ-PERSIST-FAIL"));
    }

    @Test
    @DisplayName("14. Assignment persistence failure reconciled as committed acknowledges as idempotent success")
    void assignmentPersistenceFailureReconciledAsCommittedAcknowledges() {
        Driver driver = new Driver("D-COMMITTED", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(driver);
        driver.state().tryClaim("REQ-AMBIG");

        RideRequest domainRequest = new RideRequest("REQ-AMBIG", new GeoPoint(17.3860, 78.4870));
        Assignment assignment = new Assignment(domainRequest, driver, 0.1);

        when(mockMatcher.match(any(), any(), any())).thenReturn(List.of(assignment));
        // Exception during persistence (e.g. connection timeout after commit)
        doThrow(new RuntimeException("Connection timeout on ack")).when(persistenceService).persistBatch(any(), any());
        // Reconciliation discovers the row was actually committed with our driver
        AssignmentEntity committed = new AssignmentEntity("REQ-AMBIG", "D-COMMITTED", 0.1);
        when(persistenceService.findAssignment("REQ-AMBIG")).thenReturn(Optional.of(committed));
        when(persistenceService.isRequestFinalized("REQ-AMBIG")).thenReturn(true);

        RideRequestDto dto = new RideRequestDto("REQ-AMBIG", null, 17.3860, 78.4870);
        consumer.onRideRequest(record("REQ-AMBIG", dto), acknowledgment);

        // Driver claim must NOT be released
        assertEquals(MatchStatus.CLAIMED, driver.state().getStatus());
        // Acknowledged as idempotent success
        verify(acknowledgment).acknowledge();
        assertTrue(consumer.isProcessed("REQ-AMBIG"));
    }

    @Test
    @DisplayName("15. Competing concurrent delivery winning with different driver releases our driver claim")
    void competingConcurrentDeliveryReleasesOurClaim() {
        Driver ourDriver = new Driver("D-OURS", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(ourDriver);
        ourDriver.state().tryClaim("REQ-COMPETE");

        RideRequest domainRequest = new RideRequest("REQ-COMPETE", new GeoPoint(17.3860, 78.4870));
        Assignment assignment = new Assignment(domainRequest, ourDriver, 0.1);

        when(mockMatcher.match(any(), any(), any())).thenReturn(List.of(assignment));
        doThrow(new RuntimeException("Unique constraint violation")).when(persistenceService).persistBatch(any(), any());
        // Reconciliation discovers another driver won
        AssignmentEntity otherCommitted = new AssignmentEntity("REQ-COMPETE", "D-COMPETITOR", 0.05);
        when(persistenceService.findAssignment("REQ-COMPETE")).thenReturn(Optional.of(otherCommitted));
        when(persistenceService.isRequestFinalized("REQ-COMPETE")).thenReturn(true);

        RideRequestDto dto = new RideRequestDto("REQ-COMPETE", null, 17.3860, 78.4870);
        consumer.onRideRequest(record("REQ-COMPETE", dto), acknowledgment);

        // Our driver claim must be released so our driver is not leaked
        assertEquals(MatchStatus.AVAILABLE, ourDriver.state().getStatus());
        // Kafka offset is acknowledged because the request is durably satisfied
        verify(acknowledgment).acknowledge();
        assertTrue(consumer.isProcessed("REQ-COMPETE"));
    }

    @Test
    @DisplayName("16. DriverState.releaseClaim cannot release a driver claimed by a different request")
    void releaseClaimCannotReleaseDifferentRequest() {
        DriverState state = new DriverState();
        assertTrue(state.tryClaim("REQ-OWNER"));

        // Wrong request attempt
        assertFalse(state.releaseClaim("REQ-OTHER"));
        assertEquals(MatchStatus.CLAIMED, state.getStatus());
        assertEquals("REQ-OWNER", state.getClaimedByRequestId());

        // Correct request attempt
        assertTrue(state.releaseClaim("REQ-OWNER"));
        assertEquals(MatchStatus.AVAILABLE, state.getStatus());
    }

    @Test
    @DisplayName("17. Multi-record batch matches all valid requests simultaneously and persists atomically")
    void multiRecordBatchMatchesAndPersistsAtomically() {
        Driver d1 = freshDriver("D-BATCH-1", 17.3850, 78.4867);
        Driver d2 = freshDriver("D-BATCH-2", 17.3860, 78.4870);

        RideRequestDto dto1 = new RideRequestDto("REQ-B1", null, 17.3851, 78.4868);
        RideRequestDto dto2 = new RideRequestDto("REQ-B2", null, 17.3861, 78.4871);

        RideRequest req1 = new RideRequest("REQ-B1", new GeoPoint(17.3851, 78.4868));
        RideRequest req2 = new RideRequest("REQ-B2", new GeoPoint(17.3861, 78.4871));
        Assignment a1 = new Assignment(req1, d1, 0.05);
        Assignment a2 = new Assignment(req2, d2, 0.08);

        when(mockMatcher.match(any(), any(), any())).thenReturn(List.of(a1, a2));
        when(persistenceService.persistBatch(any(), any()))
                .thenReturn(new AssignmentPersistenceService.BatchPersistResult(2, 0, 0));

        List<ConsumerRecord<String, RideRequestDto>> batch = List.of(
                record("REQ-B1", dto1, 100L),
                record("REQ-B2", dto2, 101L)
        );

        consumer.onRideRequests(batch, acknowledgment);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RideRequest>> reqListCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockMatcher, times(1)).match(reqListCaptor.capture(), any(), any());
        assertEquals(2, reqListCaptor.getValue().size());

        verify(persistenceService, times(1)).persistBatch(any(), any());
        verify(acknowledgment, times(1)).acknowledge();
        assertTrue(consumer.isProcessed("REQ-B1"));
        assertTrue(consumer.isProcessed("REQ-B2"));
    }

    @Test
    @DisplayName("18. Batch with poison pills and invalid records skips corrupt records without failing valid records")
    void batchWithPoisonPillsAndInvalidRecordsProcessesValidRecords() {
        Driver d1 = freshDriver("D-VALID", 17.3850, 78.4867);

        ConsumerRecord<String, RideRequestDto> poisonPill =
                new ConsumerRecord<>(KafkaTopics.RIDE_REQUESTS, 0, 10L, "CORRUPT", null);
        ConsumerRecord<String, RideRequestDto> invalidCoords =
                record("BAD-COORDS", new RideRequestDto("BAD-COORDS", null, 999.0, 999.0), 11L);
        ConsumerRecord<String, RideRequestDto> validRecord =
                record("GOOD-REQ", new RideRequestDto("GOOD-REQ", null, 17.3852, 78.4869), 12L);

        RideRequest validDomain = new RideRequest("GOOD-REQ", new GeoPoint(17.3852, 78.4869));
        Assignment assignment = new Assignment(validDomain, d1, 0.02);

        when(mockMatcher.match(any(), any(), any())).thenReturn(List.of(assignment));
        when(persistenceService.persistBatch(any(), any()))
                .thenReturn(new AssignmentPersistenceService.BatchPersistResult(1, 0, 0));

        consumer.onRideRequests(List.of(poisonPill, invalidCoords, validRecord), acknowledgment);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RideRequest>> reqCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockMatcher).match(reqCaptor.capture(), any(), any());
        // Only the valid record is matched
        assertEquals(1, reqCaptor.getValue().size());
        assertEquals("GOOD-REQ", reqCaptor.getValue().get(0).id());

        verify(persistenceService).persistBatch(any(), any());
        verify(acknowledgment).acknowledge();
        assertTrue(consumer.isProcessed("GOOD-REQ"));
    }

    @Test
    @DisplayName("19. Intra-batch duplicate request IDs are deduplicated to match only once")
    void intraBatchDuplicateRequestsDeduplicated() {
        Driver d1 = freshDriver("D-INTRA", 17.3850, 78.4867);

        RideRequestDto dto1 = new RideRequestDto("REQ-INTRA-DUP", null, 17.3855, 78.4865);
        RideRequestDto dto2 = new RideRequestDto("REQ-INTRA-DUP", null, 17.3855, 78.4865);

        RideRequest domain = new RideRequest("REQ-INTRA-DUP", new GeoPoint(17.3855, 78.4865));
        Assignment assignment = new Assignment(domain, d1, 0.03);

        when(mockMatcher.match(any(), any(), any())).thenReturn(List.of(assignment));

        consumer.onRideRequests(List.of(
                record("REQ-INTRA-DUP", dto1, 20L),
                record("REQ-INTRA-DUP", dto2, 21L)
        ), acknowledgment);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RideRequest>> reqCaptor = ArgumentCaptor.forClass(List.class);
        verify(mockMatcher).match(reqCaptor.capture(), any(), any());
        // Evaluated once despite arriving twice in same poll
        assertEquals(1, reqCaptor.getValue().size());
        assertEquals("REQ-INTRA-DUP", reqCaptor.getValue().get(0).id());
        verify(acknowledgment).acknowledge();
        assertTrue(consumer.isProcessed("REQ-INTRA-DUP"));
    }

    @Test
    @DisplayName("20. Multi-record batch failure with confirmed rollback does not commit offset and releases reservations")
    void multiRecordBatchFailureRollbackReleasesInFlightAndDoesNotAck() {
        Driver d1 = freshDriver("D-ROLL-1", 17.3850, 78.4867);
        Driver d2 = freshDriver("D-ROLL-2", 17.3860, 78.4870);

        RideRequestDto dto1 = new RideRequestDto("REQ-FAIL-1", null, 17.3851, 78.4868);
        RideRequestDto dto2 = new RideRequestDto("REQ-FAIL-2", null, 17.3861, 78.4871);

        RideRequest req1 = new RideRequest("REQ-FAIL-1", new GeoPoint(17.3851, 78.4868));
        RideRequest req2 = new RideRequest("REQ-FAIL-2", new GeoPoint(17.3861, 78.4871));
        Assignment a1 = new Assignment(req1, d1, 0.05);
        Assignment a2 = new Assignment(req2, d2, 0.08);

        when(mockMatcher.match(any(), any(), any())).thenReturn(List.of(a1, a2));
        doThrow(new RuntimeException("PostgreSQL transaction deadlock")).when(persistenceService).persistBatch(any(), any());
        // Both requests confirmed rolled back
        when(persistenceService.findAssignment(any())).thenReturn(Optional.empty());
        when(persistenceService.isRequestFinalized(any())).thenReturn(false);

        List<ConsumerRecord<String, RideRequestDto>> batch = List.of(
                record("REQ-FAIL-1", dto1, 50L),
                record("REQ-FAIL-2", dto2, 51L)
        );

        assertThrows(RuntimeException.class, () -> consumer.onRideRequests(batch, acknowledgment));

        // Neither offset is acknowledged
        verify(acknowledgment, never()).acknowledge();
        // Claims are released
        assertEquals(MatchStatus.AVAILABLE, d1.state().getStatus());
        assertEquals(MatchStatus.AVAILABLE, d2.state().getStatus());
        // In-flight reservations released so retry can proceed
        assertFalse(consumer.isInFlight("REQ-FAIL-1"));
        assertFalse(consumer.isInFlight("REQ-FAIL-2"));
        assertFalse(consumer.isProcessed("REQ-FAIL-1"));
        assertFalse(consumer.isProcessed("REQ-FAIL-2"));
    }

    @Test
    @DisplayName("21. Database lookup failure during reconciliation retains driver claim (does not treat failure as rollback)")
    void dbLookupFailureDuringReconciliationRetainsDriverClaim() {
        Driver d1 = freshDriver("D-LOOKUP-FAIL", 17.3850, 78.4867);
        d1.state().tryClaim("REQ-LOOKUP-ERR");

        RideRequestDto dto = new RideRequestDto("REQ-LOOKUP-ERR", null, 17.3850, 78.4867);
        RideRequest req = new RideRequest("REQ-LOOKUP-ERR", new GeoPoint(17.3850, 78.4867));
        Assignment assignment = new Assignment(req, d1, 0.04);

        when(mockMatcher.match(any(), any(), any())).thenReturn(List.of(assignment));
        doThrow(new RuntimeException("Connection timeout during commit")).when(persistenceService).persistBatch(any(), any());

        // When reconciling, DB lookup throws exception
        when(persistenceService.findAssignment("REQ-LOOKUP-ERR")).thenThrow(new RuntimeException("DB unreachable"));

        assertThrows(RuntimeException.class, () ->
                consumer.onRideRequests(List.of(record("REQ-LOOKUP-ERR", dto)), acknowledgment));

        // Driver claim must be RETAINED as a safety precaution, not released!
        assertEquals(MatchStatus.CLAIMED, d1.state().getStatus());
        verify(acknowledgment, never()).acknowledge();
        assertFalse(consumer.isProcessed("REQ-LOOKUP-ERR"));
    }

    @Test
    @DisplayName("22. Passive telemetry observation records batch arrivals and updates snapshot")
    void passiveTelemetryObservationRecordsBatchArrivalsAndUpdatesSnapshot() {
        List<ConsumerRecord<String, RideRequestDto>> records = List.of(
                record("REQ-TEL-1", new RideRequestDto("REQ-TEL-1", null, 17.3850, 78.4867)),
                record("REQ-TEL-2", new RideRequestDto("REQ-TEL-2", null, 17.3851, 78.4868)),
                record("REQ-TEL-3", new RideRequestDto("REQ-TEL-3", null, 17.3852, 78.4869))
        );

        consumer.onRideRequests(records, acknowledgment);

        AdaptiveWindowController.BatchTelemetrySnapshot snap =
                consumer.getWindowController().getTelemetrySnapshot();
        assertEquals(1, snap.totalBatches());
        assertEquals(3, snap.totalRequests());
        assertEquals(3, snap.lastBatchSize());
        assertEquals(3, snap.maxObservedBatchSize());
        assertEquals(3.0, snap.averageBatchSize(), 1e-6);
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("23. Telemetry observation failure in AdaptiveWindowController does not delay or abort request processing")
    void telemetryObservationFailureDoesNotDelayOrAbortRequestProcessing() {
        AdaptiveWindowController faultInjectingController = mock(AdaptiveWindowController.class);
        doThrow(new RuntimeException("Simulated telemetry failure"))
                .when(faultInjectingController).recordArrivals(anyInt());

        RideRequestConsumer isolatedConsumer = new RideRequestConsumer(
                mockMatcher, driverRegistry, DriverFreshnessConfig.DEFAULT, costConfig,
                persistenceService, faultInjectingController);

        ConsumerRecord<String, RideRequestDto> record =
                record("REQ-FAULT-ISO", new RideRequestDto("REQ-FAULT-ISO", null, 17.3850, 78.4867));

        isolatedConsumer.onRideRequests(List.of(record), acknowledgment);

        // Verification: match and persist occurred and acknowledgment succeeded despite controller exception
        verify(mockMatcher).match(any(), any(), any());
        verify(persistenceService).persistBatch(any(), any());
        verify(acknowledgment).acknowledge();
        assertTrue(isolatedConsumer.isProcessed("REQ-FAULT-ISO"));
    }

    @Test
    @DisplayName("24. Single sparse request records batch size 1 without artificial pacing delay")
    void singleSparseRequestRecordsBatchSizeOnePromptly() {
        ConsumerRecord<String, RideRequestDto> record =
                record("REQ-SPARSE-1", new RideRequestDto("REQ-SPARSE-1", null, 17.3850, 78.4867));

        consumer.onRideRequests(List.of(record), acknowledgment);

        AdaptiveWindowController.BatchTelemetrySnapshot snap =
                consumer.getWindowController().getTelemetrySnapshot();
        assertEquals(1, snap.totalBatches());
        assertEquals(1, snap.totalRequests());
        assertEquals(1, snap.lastBatchSize());
        assertEquals(1.0, snap.averageBatchSize(), 1e-6);
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("25. Sustained burst records batch size distribution in telemetry snapshot")
    void sustainedBurstRecordsBatchSizeDistribution() {
        // Send 5 batches of varying sizes: 10, 15, 20, 25, 30 = 100 requests total
        int[] batchSizes = {10, 15, 20, 25, 30};
        int expectedTotalRequests = 100;

        for (int i = 0; i < batchSizes.length; i++) {
            int size = batchSizes[i];
            List<ConsumerRecord<String, RideRequestDto>> batch = new ArrayList<>(size);
            for (int j = 0; j < size; j++) {
                String id = "REQ-BURST-" + i + "-" + j;
                batch.add(record(id, new RideRequestDto(id, null, 17.3850, 78.4867)));
            }
            consumer.onRideRequests(batch, acknowledgment);
        }

        AdaptiveWindowController.BatchTelemetrySnapshot snap =
                consumer.getWindowController().getTelemetrySnapshot();
        assertEquals(5, snap.totalBatches());
        assertEquals(expectedTotalRequests, snap.totalRequests());
        assertEquals(30, snap.lastBatchSize());
        assertEquals(30, snap.maxObservedBatchSize());
        assertEquals(20.0, snap.averageBatchSize(), 1e-6);
        assertTrue(snap.estimatedArrivalRate() > 0.0);
    }

    @Test
    @DisplayName("26. Mixed batch with poison pills and invalid records records full poll batch size in telemetry")
    void mixedBatchWithPoisonPillsRecordsFullPollBatchSizeInTelemetry() {
        // 1 unparseable null record (poison pill), 1 invalid coordinate, 1 valid record = 3 records in poll batch
        ConsumerRecord<String, RideRequestDto> poisonPill = record("KEY-POISON", null);
        ConsumerRecord<String, RideRequestDto> invalidRecord =
                record("REQ-INVALID", new RideRequestDto("REQ-INVALID", null, 999.0, 999.0));
        ConsumerRecord<String, RideRequestDto> validRecord =
                record("REQ-VALID", new RideRequestDto("REQ-VALID", null, 17.3850, 78.4867));

        consumer.onRideRequests(List.of(poisonPill, invalidRecord, validRecord), acknowledgment);

        AdaptiveWindowController.BatchTelemetrySnapshot snap =
                consumer.getWindowController().getTelemetrySnapshot();
        // The Kafka poll batch was 3 records
        assertEquals(1, snap.totalBatches());
        assertEquals(3, snap.totalRequests());
        assertEquals(3, snap.lastBatchSize());
        // Only the valid record was matched and persisted
        verify(persistenceService).persistBatch(argThat(list -> list.size() == 1), any());
        verify(acknowledgment).acknowledge();
        assertTrue(consumer.isProcessed("REQ-VALID"));
    }

    @Test
    @DisplayName("27. Database persistence failure records telemetry observation but does not commit offset")
    void dbPersistenceFailureRecordsTelemetryObservationWithoutCommittingOffset() {
        ConsumerRecord<String, RideRequestDto> rec1 =
                record("REQ-DB-FAIL-1", new RideRequestDto("REQ-DB-FAIL-1", null, 17.3850, 78.4867));
        ConsumerRecord<String, RideRequestDto> rec2 =
                record("REQ-DB-FAIL-2", new RideRequestDto("REQ-DB-FAIL-2", null, 17.3851, 78.4868));

        doThrow(new RuntimeException("Simulated PostgreSQL connection failure"))
                .when(persistenceService).persistBatch(any(), any());

        assertThrows(RuntimeException.class, () ->
                consumer.onRideRequests(List.of(rec1, rec2), acknowledgment));

        // Telemetry still observed the arrival
        AdaptiveWindowController.BatchTelemetrySnapshot snap =
                consumer.getWindowController().getTelemetrySnapshot();
        assertEquals(1, snap.totalBatches());
        assertEquals(2, snap.totalRequests());
        assertEquals(2, snap.lastBatchSize());

        // Offset MUST NOT be committed
        verify(acknowledgment, never()).acknowledge();
        assertFalse(consumer.isProcessed("REQ-DB-FAIL-1"));
        assertFalse(consumer.isProcessed("REQ-DB-FAIL-2"));
    }

    @Test
    @DisplayName("28. Mixed batch of new and already-finalized requests: only new requests reach the matcher")
    void mixedBatch_onlyNewRequestsReachMatcher() {
        ConsumerRecord<String, RideRequestDto> recNew1 =
                record("REQ-NEW-1", new RideRequestDto("REQ-NEW-1", null, 17.3850, 78.4867));
        ConsumerRecord<String, RideRequestDto> recFinalized =
                record("REQ-FINALIZED", new RideRequestDto("REQ-FINALIZED", null, 17.3851, 78.4868));
        ConsumerRecord<String, RideRequestDto> recNew2 =
                record("REQ-NEW-2", new RideRequestDto("REQ-NEW-2", null, 17.3852, 78.4869));

        when(persistenceService.findFinalizedRequestIdsIn(Set.of("REQ-NEW-1", "REQ-FINALIZED", "REQ-NEW-2")))
                .thenReturn(Set.of("REQ-FINALIZED"));

        consumer.onRideRequests(List.of(recNew1, recFinalized, recNew2), acknowledgment);

        // Matcher must receive only the 2 new requests
        verify(mockMatcher).match(argThat(list ->
                list.size() == 2
                && list.stream().anyMatch(r -> "REQ-NEW-1".equals(r.id()))
                && list.stream().anyMatch(r -> "REQ-NEW-2".equals(r.id()))
                && list.stream().noneMatch(r -> "REQ-FINALIZED".equals(r.id()))
        ), any(), any());

        // Finalized duplicate must be marked processed
        assertTrue(consumer.isProcessed("REQ-FINALIZED"));
        assertTrue(consumer.isProcessed("REQ-NEW-1"));
        assertTrue(consumer.isProcessed("REQ-NEW-2"));
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("29. Database lookup failure releases in-flight reservations and propagates exception without acknowledgement")
    void databaseLookupFailure_releasesInFlightAndDoesNotAcknowledge() {
        ConsumerRecord<String, RideRequestDto> rec =
                record("REQ-LOOKUP-FAIL", new RideRequestDto("REQ-LOOKUP-FAIL", null, 17.3850, 78.4867));

        when(persistenceService.findFinalizedRequestIdsIn(any()))
                .thenThrow(new RuntimeException("PostgreSQL connection timeout during batch lookup"));

        assertThrows(RuntimeException.class, () ->
                consumer.onRideRequests(List.of(rec), acknowledgment));

        // Offset must not be acknowledged
        verify(acknowledgment, never()).acknowledge();

        // In-flight reservation must be released (no leak) so Kafka error handler retry can re-attempt
        assertFalse(consumer.isInFlight("REQ-LOOKUP-FAIL"));
        assertFalse(consumer.isProcessed("REQ-LOOKUP-FAIL"));
    }

    @Test
    @DisplayName("30. Batch with duplicate request IDs filters in-memory duplicates before database lookup")
    void batchWithDuplicateRequestIds_filtersInMemoryDuplicatesBeforeDatabaseLookup() {
        ConsumerRecord<String, RideRequestDto> rec1 =
                record("REQ-DUP-ID", new RideRequestDto("REQ-DUP-ID", null, 17.3850, 78.4867));
        ConsumerRecord<String, RideRequestDto> rec2 =
                record("REQ-DUP-ID", new RideRequestDto("REQ-DUP-ID", null, 17.3850, 78.4867));

        consumer.onRideRequests(List.of(rec1, rec2), acknowledgment);

        // findFinalizedRequestIdsIn should be called with only 1 unique ID
        verify(persistenceService).findFinalizedRequestIdsIn(Set.of("REQ-DUP-ID"));
        verify(mockMatcher).match(argThat(list -> list.size() == 1), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("31. Empty batch acknowledges immediately without database lookup or matcher call")
    void emptyBatch_acknowledgesImmediatelyWithoutDatabaseLookup() {
        consumer.onRideRequests(List.of(), acknowledgment);

        verify(persistenceService, never()).findFinalizedRequestIdsIn(any());
        verify(mockMatcher, never()).match(any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("32. Batch lookup is invoked exactly once per eligible batch")
    void batchLookup_invokedExactlyOncePerEligibleBatch() {
        ConsumerRecord<String, RideRequestDto> rec1 =
                record("REQ-B1", new RideRequestDto("REQ-B1", null, 17.3850, 78.4867));
        ConsumerRecord<String, RideRequestDto> rec2 =
                record("REQ-B2", new RideRequestDto("REQ-B2", null, 17.3851, 78.4868));

        consumer.onRideRequests(List.of(rec1, rec2), acknowledgment);

        verify(persistenceService, times(1)).findFinalizedRequestIdsIn(Set.of("REQ-B1", "REQ-B2"));
        verify(mockMatcher, times(1)).match(any(), any(), any());
    }
}
