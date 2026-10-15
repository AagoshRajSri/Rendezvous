package com.aagosh.dispatch.api;

import com.aagosh.dispatch.cache.RedisLocationCache;
import com.aagosh.dispatch.config.DriverFreshnessConfig;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.persistence.AssignmentEntity;
import com.aagosh.dispatch.persistence.AssignmentPersistenceService;
import com.aagosh.dispatch.persistence.RideRequestEntity;
import com.aagosh.dispatch.persistence.RideRequestRepository;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link RequestStatusController} (no Spring context; MockMvc standalone).
 *
 * <p>Tests cover the GET /api/requests/{requestId} status endpoint and the
 * POST /api/request and POST /api/register route aliases.
 *
 * <p>Tests that require PostgreSQL or Kafka are infrastructure-dependent and are
 * excluded from this unit test class. They belong in integration test classes
 * annotated with @Testcontainers.
 */
class RequestStatusControllerTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, RideRequestDto> rideKafkaTemplate = mock(KafkaTemplate.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, DriverPingRequest> driverKafkaTemplate = mock(KafkaTemplate.class);
    private final RideRequestRepository rideRequestRepository = mock(RideRequestRepository.class);
    private final AssignmentPersistenceService assignmentPersistenceService = mock(AssignmentPersistenceService.class);
    private final DriverRegistry driverRegistry = new DriverRegistry();
    private final RedisLocationCache redisLocationCache = mock(RedisLocationCache.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(redisLocationCache.getLocation(anyString())).thenReturn(RedisLocationCache.LocationResult.Miss.INSTANCE);
        RideController rideController = new RideController(rideKafkaTemplate);
        DriverController driverController = new DriverController(
                driverKafkaTemplate, driverRegistry, redisLocationCache, DriverFreshnessConfig.DEFAULT);
        RequestStatusController controller = new RequestStatusController(
                rideRequestRepository,
                assignmentPersistenceService,
                rideController,
                driverController,
                driverRegistry,
                DriverFreshnessConfig.DEFAULT
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // -------------------------------------------------------------------------
    // GET /api/requests/{requestId}
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/requests/{requestId}")
    class StatusQuery {

        @Test
        @DisplayName("Returns 404 when no ride_requests row exists for the requestId")
        void returns404WhenNotFound() throws Exception {
            when(rideRequestRepository.findByRequestId("UNKNOWN")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/requests/UNKNOWN"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"));
        }

        @Test
        @DisplayName("Returns PENDING status with null driverId when request is not yet matched")
        void returnsPendingWithNullDriverId() throws Exception {
            RideRequestEntity entity = buildRequestEntity("REQ-1", "PENDING");
            when(rideRequestRepository.findByRequestId("REQ-1")).thenReturn(Optional.of(entity));

            mockMvc.perform(get("/api/requests/REQ-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-1"))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.driverId").doesNotExist());
        }

        @Test
        @DisplayName("Returns MATCHED status with driverId populated from assignments table")
        void returnsMatchedWithDriverId() throws Exception {
            RideRequestEntity entity = buildRequestEntity("REQ-2", "MATCHED");
            when(rideRequestRepository.findByRequestId("REQ-2")).thenReturn(Optional.of(entity));

            AssignmentEntity assignment = new AssignmentEntity("REQ-2", "DRIVER-42", 1.5);
            when(assignmentPersistenceService.findAssignment("REQ-2"))
                    .thenReturn(Optional.of(assignment));

            mockMvc.perform(get("/api/requests/REQ-2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-2"))
                    .andExpect(jsonPath("$.status").value("MATCHED"))
                    .andExpect(jsonPath("$.driverId").value("DRIVER-42"));
        }

        @Test
        @DisplayName("Returns MATCHED status with null driverId when assignment row is missing (reconciliation lag)")
        void returnsMatchedWithNullDriverIdWhenAssignmentMissing() throws Exception {
            RideRequestEntity entity = buildRequestEntity("REQ-3", "MATCHED");
            when(rideRequestRepository.findByRequestId("REQ-3")).thenReturn(Optional.of(entity));
            when(assignmentPersistenceService.findAssignment("REQ-3")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/requests/REQ-3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-3"))
                    .andExpect(jsonPath("$.status").value("MATCHED"))
                    .andExpect(jsonPath("$.driverId").doesNotExist());
        }

        @Test
        @DisplayName("Returns NO_DRIVERS terminal status with null driverId")
        void returnsNoDrivers() throws Exception {
            RideRequestEntity entity = buildRequestEntity("REQ-4", "NO_DRIVERS");
            when(rideRequestRepository.findByRequestId("REQ-4")).thenReturn(Optional.of(entity));

            mockMvc.perform(get("/api/requests/REQ-4"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-4"))
                    .andExpect(jsonPath("$.status").value("NO_DRIVERS"))
                    .andExpect(jsonPath("$.driverId").doesNotExist());
        }

        @Test
        @DisplayName("Returns MATCHED status with driver telemetry when driver is registered and fresh")
        void returnsMatchedWithDriverTelemetryWhenFresh() throws Exception {
            long now = System.currentTimeMillis();
            Driver driver = new Driver("D-FRESH", new GeoPoint(12.9716, 77.5946), new DriverState(), now - 10_000L);
            driverRegistry.register(driver);

            RideRequestEntity entity = buildRequestEntity("REQ-FRESH", "MATCHED");
            when(rideRequestRepository.findByRequestId("REQ-FRESH")).thenReturn(Optional.of(entity));

            AssignmentEntity assignment = new AssignmentEntity("REQ-FRESH", "D-FRESH", 1.5);
            when(assignmentPersistenceService.findAssignment("REQ-FRESH"))
                    .thenReturn(Optional.of(assignment));

            mockMvc.perform(get("/api/requests/REQ-FRESH"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-FRESH"))
                    .andExpect(jsonPath("$.status").value("MATCHED"))
                    .andExpect(jsonPath("$.driverId").value("D-FRESH"))
                    .andExpect(jsonPath("$.driverLatitude").value(12.9716))
                    .andExpect(jsonPath("$.driverLongitude").value(77.5946))
                    .andExpect(jsonPath("$.driverLocationAt").isNotEmpty());
        }

        @Test
        @DisplayName("Returns MATCHED status with null telemetry when driver is registered but stale")
        void returnsMatchedWithoutTelemetryWhenDriverStale() throws Exception {
            long now = System.currentTimeMillis();
            Driver driver = new Driver("D-STALE", new GeoPoint(12.9716, 77.5946), new DriverState(), now - 120_000L);
            driverRegistry.register(driver);

            RideRequestEntity entity = buildRequestEntity("REQ-STALE", "MATCHED");
            when(rideRequestRepository.findByRequestId("REQ-STALE")).thenReturn(Optional.of(entity));

            AssignmentEntity assignment = new AssignmentEntity("REQ-STALE", "D-STALE", 1.5);
            when(assignmentPersistenceService.findAssignment("REQ-STALE"))
                    .thenReturn(Optional.of(assignment));

            mockMvc.perform(get("/api/requests/REQ-STALE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-STALE"))
                    .andExpect(jsonPath("$.status").value("MATCHED"))
                    .andExpect(jsonPath("$.driverId").value("D-STALE"))
                    .andExpect(jsonPath("$.driverLatitude").doesNotExist())
                    .andExpect(jsonPath("$.driverLongitude").doesNotExist())
                    .andExpect(jsonPath("$.driverLocationAt").doesNotExist());
        }

        @Test
        @DisplayName("Returns MATCHED status with null telemetry when driver has updatedAt=0")
        void returnsMatchedWithoutTelemetryWhenDriverZeroTimestamp() throws Exception {
            Driver driver = new Driver("D-ZERO", new GeoPoint(12.9716, 77.5946), new DriverState(), 0L);
            driverRegistry.register(driver);

            RideRequestEntity entity = buildRequestEntity("REQ-ZERO", "MATCHED");
            when(rideRequestRepository.findByRequestId("REQ-ZERO")).thenReturn(Optional.of(entity));

            AssignmentEntity assignment = new AssignmentEntity("REQ-ZERO", "D-ZERO", 1.5);
            when(assignmentPersistenceService.findAssignment("REQ-ZERO"))
                    .thenReturn(Optional.of(assignment));

            mockMvc.perform(get("/api/requests/REQ-ZERO"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-ZERO"))
                    .andExpect(jsonPath("$.status").value("MATCHED"))
                    .andExpect(jsonPath("$.driverId").value("D-ZERO"))
                    .andExpect(jsonPath("$.driverLatitude").doesNotExist())
                    .andExpect(jsonPath("$.driverLongitude").doesNotExist())
                    .andExpect(jsonPath("$.driverLocationAt").doesNotExist());
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/request alias
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/request alias")
    class RequestAlias {

        @Test
        @DisplayName("Delegates to RideController and returns 202 Accepted")
        void delegatesToRideControllerAndReturns202() throws Exception {
            setupSuccessfulRideSend();

            mockMvc.perform(post("/api/request")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId": "REQ-A", "latitude": 51.5, "longitude": -0.1}
                                    """))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.requestId").value("REQ-A"))
                    .andExpect(jsonPath("$.message").value("Ride request accepted and queued for matching"));
        }

        @Test
        @DisplayName("Validates payload and returns 400 Bad Request for missing requestId")
        void returns400ForMissingRequestId() throws Exception {
            mockMvc.perform(post("/api/request")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"latitude": 51.5, "longitude": -0.1}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/register alias
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/register alias")
    class RegisterAlias {

        @Test
        @DisplayName("Delegates to DriverController and returns 202 Accepted")
        void delegatesToDriverControllerAndReturns202() throws Exception {
            setupSuccessfulDriverSend();

            mockMvc.perform(post("/api/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"driverId": "DRIVER-1", "latitude": 51.5, "longitude": -0.1, "action": "REGISTER"}
                                    """))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.driverId").value("DRIVER-1"))
                    .andExpect(jsonPath("$.message").value("Driver registration accepted and queued for processing"));
        }

        @Test
        @DisplayName("Returns 400 Bad Request for missing driverId")
        void returns400ForMissingDriverId() throws Exception {
            mockMvc.perform(post("/api/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"latitude": 51.5, "longitude": -0.1}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/drivers/{driverId}/location alias
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/drivers/{driverId}/location alias")
    class DriverLocationAlias {

        @Test
        @DisplayName("Delegates to DriverController and returns 200 OK when driver is fresh")
        void delegatesToDriverControllerAndReturns200() throws Exception {
            long now = System.currentTimeMillis();
            Driver driver = new Driver("D-LOC-1", new GeoPoint(51.5, -0.1), new DriverState(), now - 5_000L);
            driverRegistry.register(driver);

            mockMvc.perform(get("/api/drivers/D-LOC-1/location"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.driverId").value("D-LOC-1"))
                    .andExpect(jsonPath("$.fresh").value(true))
                    .andExpect(jsonPath("$.latitude").value(51.5))
                    .andExpect(jsonPath("$.longitude").value(-0.1))
                    .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        }

        @Test
        @DisplayName("Delegates to DriverController and returns 404 for unknown driver")
        void delegatesToDriverControllerAndReturns404() throws Exception {
            mockMvc.perform(get("/api/drivers/UNKNOWN/location"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RideRequestEntity buildRequestEntity(String requestId, String status) {
        RideRequestEntity entity = new RideRequestEntity(requestId, 51.5, -0.1, status);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private void setupSuccessfulRideSend() {
        SendResult<String, RideRequestDto> sendResult = mock(SendResult.class);
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("ride-requests", 0), 0, 0, 0, 0, 0);
        when(sendResult.getRecordMetadata()).thenReturn(metadata);
        when(rideKafkaTemplate.send(anyString(), anyString(), any(RideRequestDto.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    @SuppressWarnings("unchecked")
    private void setupSuccessfulDriverSend() {
        SendResult<String, DriverPingRequest> sendResult = mock(SendResult.class);
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("driver-pings", 0), 0, 0, 0, 0, 0);
        when(sendResult.getRecordMetadata()).thenReturn(metadata);
        when(driverKafkaTemplate.send(anyString(), anyString(), any(DriverPingRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }
}
