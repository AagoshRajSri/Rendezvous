package com.aagosh.dispatch.api;

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RideController rideController = new RideController(rideKafkaTemplate);
        DriverController driverController = new DriverController(driverKafkaTemplate);
        RequestStatusController controller = new RequestStatusController(
                rideRequestRepository,
                assignmentPersistenceService,
                rideController,
                driverController
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
