package com.aagosh.dispatch.messaging;

import com.aagosh.dispatch.api.DriverPingRequest;
import com.aagosh.dispatch.api.PingAction;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DriverPingConsumerTest {

    private DriverRegistry driverRegistry;
    private DriverPingConsumer consumer;
    private Acknowledgment acknowledgment;

    @BeforeEach
    void setUp() {
        driverRegistry = new DriverRegistry();
        consumer = new DriverPingConsumer(driverRegistry);
        acknowledgment = mock(Acknowledgment.class);
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
        Driver driver = new Driver("D2", new GeoPoint(17.0, 78.0), new DriverState());
        driverRegistry.register(driver);
        assertTrue(driver.state().tryClaim("REQ-999"));
        assertEquals(MatchStatus.CLAIMED, driver.state().getStatus());

        DriverPingRequest updateRequest = new DriverPingRequest("D2", 17.5, 78.5, PingAction.UPDATE);
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
        DriverPingRequest ping1 = new DriverPingRequest("D3", 17.1, 78.1, PingAction.AUTO);
        consumer.onDriverPing(record("D3", ping1), acknowledgment);
        assertTrue(driverRegistry.findById("D3").isPresent());
        assertEquals(17.1, driverRegistry.findById("D3").get().location().latitude());

        // Second ping: updates location
        DriverPingRequest ping2 = new DriverPingRequest("D3", 17.2, 78.2, PingAction.AUTO);
        consumer.onDriverPing(record("D3", ping2), acknowledgment);
        assertEquals(17.2, driverRegistry.findById("D3").get().location().latitude());
    }

    @Test
    @DisplayName("4. Duplicate REGISTER event for already-registered driver is safely ignored and acknowledged")
    void duplicateRegisterEventIsIdempotent() {
        Driver driver = new Driver("D4", new GeoPoint(17.0, 78.0), new DriverState());
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
}
