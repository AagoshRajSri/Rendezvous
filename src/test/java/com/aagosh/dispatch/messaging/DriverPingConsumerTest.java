package com.aagosh.dispatch.messaging;

import com.aagosh.dispatch.api.DriverPingRequest;
import com.aagosh.dispatch.api.PingAction;
import com.aagosh.dispatch.cache.RedisLocationCache;
import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DriverPingConsumerTest {

    private DriverRegistry        driverRegistry;
    private RedisLocationCache    redisLocationCache;
    private DriverPingConsumer    consumer;
    private Acknowledgment        acknowledgment;

    @BeforeEach
    void setUp() {
        driverRegistry     = new DriverRegistry();
        redisLocationCache = mock(RedisLocationCache.class);
        consumer           = new DriverPingConsumer(driverRegistry, redisLocationCache);
        acknowledgment     = mock(Acknowledgment.class);
    }

    private ConsumerRecord<String, DriverPingRequest> record(String key, DriverPingRequest value) {
        return new ConsumerRecord<>(KafkaTopics.DRIVER_PINGS, 0, 100L, key, value);
    }

    @Test
    @DisplayName("1. REGISTER event registers driver and commits offset")
    void registerEventRegistersDriver() {
        DriverPingRequest request = new DriverPingRequest("D1", 17.3850, 78.4867, PingAction.REGISTER);
        consumer.onDriverPing(record("D1", request), acknowledgment);

        assertTrue(driverRegistry.findById("D1").isPresent());
        Driver registered = driverRegistry.findById("D1").get();
        assertEquals(17.3850, registered.location().latitude());
        assertEquals(78.4867, registered.location().longitude());
        assertEquals(MatchStatus.AVAILABLE, registered.state().getStatus());

        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("2. UPDATE event updates location and preserves claim state")
    void updateEventPreservesClaimState() {
        Driver driver = new Driver("D2", new GeoPoint(17.0, 78.0), new DriverState(), 1000L);
        driverRegistry.register(driver);
        assertTrue(driver.state().tryClaim("REQ-999"));
        assertEquals(MatchStatus.CLAIMED, driver.state().getStatus());

        // Use a later timestamp so the update is accepted
        DriverPingRequest updateRequest = new DriverPingRequest("D2", 17.5, 78.5, PingAction.UPDATE, 2000L);
        consumer.onDriverPing(record("D2", updateRequest), acknowledgment);

        Driver updated = driverRegistry.findById("D2").orElseThrow();
        assertEquals(17.5, updated.location().latitude());
        assertEquals(78.5, updated.location().longitude());
        // Critical guarantee: claim state must NOT be reset by a location update
        assertEquals(MatchStatus.CLAIMED, updated.state().getStatus());
        assertEquals("REQ-999", updated.state().getClaimedByRequestId());

        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("3. AUTO event registers when driver absent, updates when present")
    void autoEventRegistersThenUpdates() {
        // First ping: registers
        DriverPingRequest ping1 = new DriverPingRequest("D3", 17.1, 78.1, PingAction.AUTO, 1000L);
        consumer.onDriverPing(record("D3", ping1), acknowledgment);
        assertTrue(driverRegistry.findById("D3").isPresent());
        assertEquals(17.1, driverRegistry.findById("D3").get().location().latitude());

        // Second ping: updates location (with later timestamp)
        DriverPingRequest ping2 = new DriverPingRequest("D3", 17.2, 78.2, PingAction.AUTO, 2000L);
        consumer.onDriverPing(record("D3", ping2), acknowledgment);
        assertEquals(17.2, driverRegistry.findById("D3").get().location().latitude());
    }

    @Test
    @DisplayName("4. Duplicate REGISTER event for already-registered driver is safely ignored and acknowledged")
    void duplicateRegisterEventIsIdempotent() {
        Driver driver = new Driver("D4", new GeoPoint(17.0, 78.0), new DriverState(), 1000L);
        driverRegistry.register(driver);
        driver.state().tryClaim("REQ-KEEP");

        DriverPingRequest duplicateRegister = new DriverPingRequest("D4", 17.9, 78.9, PingAction.REGISTER);
        consumer.onDriverPing(record("D4", duplicateRegister), acknowledgment);

        // State and location are preserved
        Driver stored = driverRegistry.findById("D4").orElseThrow();
        assertEquals(MatchStatus.CLAIMED, stored.state().getStatus());
        assertEquals("REQ-KEEP", stored.state().getClaimedByRequestId());

        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("5. UPDATE event for unknown driver is acknowledged without crashing")
    void updateUnknownDriverAcknowledgedWithoutException() {
        DriverPingRequest updateUnknown = new DriverPingRequest("D_UNKNOWN", 17.0, 78.0, PingAction.UPDATE);
        consumer.onDriverPing(record("D_UNKNOWN", updateUnknown), acknowledgment);

        assertTrue(driverRegistry.findById("D_UNKNOWN").isEmpty());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("6. Null or invalid event payload is skipped and acknowledged to prevent partition stall")
    void nullOrInvalidPayloadSkippedAndAcknowledged() {
        // Null payload
        consumer.onDriverPing(record("NULL_KEY", null), acknowledgment);

        // Invalid payload (invalid coordinates)
        DriverPingRequest invalidReq = new DriverPingRequest("D_INV", 95.0, 78.0, PingAction.AUTO);
        consumer.onDriverPing(record("D_INV", invalidReq), acknowledgment);

        assertTrue(driverRegistry.findById("D_INV").isEmpty());
    }

    @Test
    @DisplayName("7. Stale UPDATE event (older timestamp) is rejected and does not write to Redis")
    void staleUpdateIsRejectedAndDoesNotWriteToRedis() {
        // Register driver with timestamp=2000
        Driver driver = new Driver("D5", new GeoPoint(17.5, 78.5), new DriverState(), 2000L);
        driverRegistry.register(driver);

        // Send an update with an older timestamp
        DriverPingRequest staleUpdate = new DriverPingRequest("D5", 17.0, 78.0, PingAction.UPDATE, 1000L);
        consumer.onDriverPing(record("D5", staleUpdate), acknowledgment);

        // Registry must retain the newer location
        Driver stored = driverRegistry.findById("D5").orElseThrow();
        assertEquals(17.5, stored.location().latitude());
        assertEquals(78.5, stored.location().longitude());

        // Redis must NOT be written for a rejected stale update
        verify(redisLocationCache, never()).updateLocation(anyString(), anyDouble(), anyDouble(), anyLong());

        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("8. resolveTimestamp prefers capturedAt over Kafka broker timestamp")
    void resolveTimestampPrefersCapturedAt() {
        DriverPingRequest withCapturedAt = new DriverPingRequest("DX", 17.0, 78.0, PingAction.AUTO, 5000L);
        long resolved = DriverPingConsumer.resolveTimestamp(withCapturedAt, 9999L);
        assertEquals(5000L, resolved, "capturedAt must take precedence over Kafka broker timestamp");
    }

    @Test
    @DisplayName("9. resolveTimestamp falls back to Kafka broker timestamp when capturedAt is absent")
    void resolveTimestampFallsBackToKafkaTimestamp() {
        DriverPingRequest noCapturedAt = new DriverPingRequest("DX", 17.0, 78.0, PingAction.AUTO, null);
        long resolved = DriverPingConsumer.resolveTimestamp(noCapturedAt, 7777L);
        assertEquals(7777L, resolved, "Kafka broker timestamp must be used when capturedAt is absent");
    }

    @Test
    @DisplayName("10. resolveTimestamp returns 0 when both capturedAt and broker timestamp are absent or non-positive")
    void resolveTimestampReturnsZeroWhenNeitherPresentOrNonPositive() {
        DriverPingRequest noTs = new DriverPingRequest("DX", 17.0, 78.0, PingAction.AUTO, null);
        assertEquals(0L, DriverPingConsumer.resolveTimestamp(noTs, 0L));
        assertEquals(0L, DriverPingConsumer.resolveTimestamp(noTs, -1L));

        DriverPingRequest nonPosTs = new DriverPingRequest("DX", 17.0, 78.0, PingAction.AUTO, 0L);
        assertEquals(0L, DriverPingConsumer.resolveTimestamp(nonPosTs, 0L));
        assertEquals(555L, DriverPingConsumer.resolveTimestamp(nonPosTs, 555L),
                "Falls back to Kafka timestamp when capturedAt is non-positive");
    }

    @Test
    @DisplayName("11. Redis is not written when Redis throws an exception (failure is swallowed)")
    void redisExceptionDoesNotFailKafkaProcessing() {
        // Make Redis throw on any call
        org.mockito.Mockito.doThrow(new RuntimeException("Redis unavailable"))
                .when(redisLocationCache).updateLocation(anyString(), anyDouble(), anyDouble(), anyLong());

        DriverPingRequest request = new DriverPingRequest("D6", 17.0, 78.0, PingAction.AUTO, 1000L);
        // Must NOT throw even though Redis is down
        consumer.onDriverPing(record("D6", request), acknowledgment);

        // Driver is still registered locally
        assertTrue(driverRegistry.findById("D6").isPresent());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("12. Unversioned events update local registry while driver is unversioned, but cannot overwrite versioned driver; Redis is skipped for unversioned events")
    void unversionedAndTimestampedUpdateInteraction() {
        String driverId = "D_UNVERSIONED";

        // Step 1: Register unversioned driver (no capturedAt, kafkaTimestamp = 0)
        ConsumerRecord<String, DriverPingRequest> regRecord =
                new ConsumerRecord<>("driver-pings", 0, 1L, 0L,
                        org.apache.kafka.common.record.TimestampType.NO_TIMESTAMP_TYPE,
                        0, 0, driverId,
                        new DriverPingRequest(driverId, 17.0, 78.0, PingAction.REGISTER, null),
                        new org.apache.kafka.common.header.internals.RecordHeaders(),
                        Optional.empty());
        consumer.onDriverPing(regRecord, acknowledgment);

        Driver regDriver = driverRegistry.findById(driverId).orElseThrow();
        assertEquals(17.0, regDriver.location().latitude());
        assertEquals(0L, regDriver.updatedAt(), "Registered driver must be unversioned (0L)");
        // Redis location cache is NOT written for unversioned ping (ts <= 0)
        verify(redisLocationCache, never()).updateLocation(anyString(), anyDouble(), anyDouble(), anyLong());

        // Step 2: Unversioned update succeeds while driver remains unversioned
        ConsumerRecord<String, DriverPingRequest> update1 =
                new ConsumerRecord<>("driver-pings", 0, 2L, 0L,
                        org.apache.kafka.common.record.TimestampType.NO_TIMESTAMP_TYPE,
                        0, 0, driverId,
                        new DriverPingRequest(driverId, 17.1, 78.1, PingAction.UPDATE, null),
                        new org.apache.kafka.common.header.internals.RecordHeaders(),
                        Optional.empty());
        consumer.onDriverPing(update1, acknowledgment);

        Driver updatedDriver1 = driverRegistry.findById(driverId).orElseThrow();
        assertEquals(17.1, updatedDriver1.location().latitude(), "Unversioned update must succeed for unversioned driver");
        assertEquals(0L, updatedDriver1.updatedAt());
        verify(redisLocationCache, never()).updateLocation(anyString(), anyDouble(), anyDouble(), anyLong());

        // Step 3: Timestamped update promotes driver to versioned and writes to Redis
        ConsumerRecord<String, DriverPingRequest> updateTs =
                new ConsumerRecord<>("driver-pings", 0, 3L, 0L,
                        org.apache.kafka.common.record.TimestampType.NO_TIMESTAMP_TYPE,
                        0, 0, driverId,
                        new DriverPingRequest(driverId, 17.2, 78.2, PingAction.UPDATE, 1000L),
                        new org.apache.kafka.common.header.internals.RecordHeaders(),
                        Optional.empty());
        consumer.onDriverPing(updateTs, acknowledgment);

        Driver versionedDriver = driverRegistry.findById(driverId).orElseThrow();
        assertEquals(17.2, versionedDriver.location().latitude());
        assertEquals(1000L, versionedDriver.updatedAt(), "Driver must be promoted to versioned (1000L)");
        verify(redisLocationCache).updateLocation(driverId, 17.2, 78.2, 1000L);

        // Step 4: Subsequent unversioned update CANNOT overwrite versioned driver
        ConsumerRecord<String, DriverPingRequest> updateUnversionedStale =
                new ConsumerRecord<>("driver-pings", 0, 4L, 0L,
                        org.apache.kafka.common.record.TimestampType.NO_TIMESTAMP_TYPE,
                        0, 0, driverId,
                        new DriverPingRequest(driverId, 17.9, 78.9, PingAction.UPDATE, null),
                        new org.apache.kafka.common.header.internals.RecordHeaders(),
                        Optional.empty());
        consumer.onDriverPing(updateUnversionedStale, acknowledgment);

        Driver finalDriver = driverRegistry.findById(driverId).orElseThrow();
        assertEquals(17.2, finalDriver.location().latitude(), "Unversioned event cannot overwrite versioned driver");
        assertEquals(1000L, finalDriver.updatedAt());
    }

    @Test
    @DisplayName("13. Delayed legacy events without capturedAt rely on broker timestamp; out-of-order broker timestamps are rejected")
    void delayedLegacyEventsRelyOnBrokerTimestampOrdering() {
        String driverId = "D_LEGACY_ORDER";

        // First message reaches broker with broker timestamp 2000L
        ConsumerRecord<String, DriverPingRequest> msg1 =
                new ConsumerRecord<>("driver-pings", 0, 1L, 2000L,
                        org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                        0, 0, driverId,
                        new DriverPingRequest(driverId, 18.0, 78.0, PingAction.AUTO, null),
                        new org.apache.kafka.common.header.internals.RecordHeaders(),
                        Optional.empty());
        consumer.onDriverPing(msg1, acknowledgment);

        Driver driver1 = driverRegistry.findById(driverId).orElseThrow();
        assertEquals(18.0, driver1.location().latitude());
        assertEquals(2000L, driver1.updatedAt());

        // Delayed message from earlier with broker timestamp 1000L arrives second
        ConsumerRecord<String, DriverPingRequest> delayedMsg =
                new ConsumerRecord<>("driver-pings", 0, 2L, 1000L,
                        org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                        0, 0, driverId,
                        new DriverPingRequest(driverId, 17.0, 78.0, PingAction.AUTO, null),
                        new org.apache.kafka.common.header.internals.RecordHeaders(),
                        Optional.empty());
        consumer.onDriverPing(delayedMsg, acknowledgment);

        // Registry retains fresher location (ts=2000L); delayed update is rejected
        Driver driverFinal = driverRegistry.findById(driverId).orElseThrow();
        assertEquals(18.0, driverFinal.location().latitude(), "Delayed update with older broker timestamp must be rejected");
        assertEquals(2000L, driverFinal.updatedAt());
    }
}
