package com.aagosh.dispatch.api;

import com.aagosh.dispatch.config.DriverFreshnessConfig;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.persistence.AssignmentPersistenceService;
import com.aagosh.dispatch.persistence.RideRequestEntity;
import com.aagosh.dispatch.persistence.RideRequestRepository;
import com.aagosh.dispatch.tracing.CandidateOutcome;
import com.aagosh.dispatch.tracing.DispatchTrace;
import com.aagosh.dispatch.tracing.DispatchTraceConfig;
import com.aagosh.dispatch.tracing.DispatchTraceStore;
import com.aagosh.dispatch.tracing.SpatialStage;
import com.aagosh.dispatch.tracing.TraceCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused controller unit tests for {@link DispatchTraceController} and its integration with {@link GlobalExceptionHandler}.
 *
 * <p>Validates:
 * <ul>
 *   <li>HTTP 200 with full trace payload for existing traces.</li>
 *   <li>HTTP 404 with standardized error response when trace is missing.</li>
 *   <li>HTTP 404 with standardized error response when trace has expired via TTL.</li>
 *   <li>HTTP 400 with standardized error response when requestId is malformed or blank.</li>
 *   <li>Request ID isolation ensuring no cross-request data leakage.</li>
 *   <li>Non-interference with existing {@code GET /api/requests/{requestId}} request-status endpoint.</li>
 * </ul>
 */
class DispatchTraceControllerTest {

    private static class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone;

        MutableClock(Instant start) {
            this.current = start;
            this.zone = ZoneId.of("UTC");
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private MutableClock clock;
    private DispatchTraceStore traceStore;
    private DispatchTraceController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-19T02:00:00Z"));
        DispatchTraceConfig config = new DispatchTraceConfig(50, 10, 100, Duration.ofMinutes(10), true);
        traceStore = new DispatchTraceStore(config, clock);
        controller = new DispatchTraceController(traceStore);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private DispatchTrace sampleTrace(String requestId, String winnerDriverId, boolean matched) {
        return new DispatchTrace(
                requestId,
                "AuctionMatcher",
                12.9716,
                77.5946,
                Instant.parse("2026-10-19T02:00:00Z"),
                2_500_000L,
                List.of(new SpatialStage(0, "QUAD_TREE_SEARCH", 16, 12.90, 77.50, 13.00, 77.60, 12, 4, 3)),
                List.of(
                        new TraceCandidate("DRV-1", 12.9720, 77.5950, 0.5, 1.2, 0.4, 0.1, 1.7, 0, CandidateOutcome.MATCHED, null),
                        new TraceCandidate("DRV-2", 12.9800, 77.6000, 1.8, 3.5, 0.8, 0.3, 4.6, 1, CandidateOutcome.OUTCOMPETED, "Higher total cost")
                ),
                winnerDriverId,
                matched,
                150,
                "Matched with driver DRV-1 (total cost: 1.70)"
        );
    }

    // -------------------------------------------------------------------------
    // Successful Retrieval (HTTP 200)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/requests/{requestId}/trace - Successful Retrieval")
    class SuccessfulRetrieval {

        @Test
        @DisplayName("Returns 200 OK with complete trace payload when trace exists")
        void returns200WithCompleteTracePayload() throws Exception {
            DispatchTrace trace = sampleTrace("REQ-100", "DRV-1", true);
            traceStore.put(trace);

            mockMvc.perform(get("/api/requests/REQ-100/trace"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-100"))
                    .andExpect(jsonPath("$.algorithm").value("AuctionMatcher"))
                    .andExpect(jsonPath("$.pickupLatitude").value(12.9716))
                    .andExpect(jsonPath("$.pickupLongitude").value(77.5946))
                    .andExpect(jsonPath("$.startedAt").value("2026-10-19T02:00:00Z"))
                    .andExpect(jsonPath("$.durationNanos").value(2_500_000L))
                    .andExpect(jsonPath("$.winnerDriverId").value("DRV-1"))
                    .andExpect(jsonPath("$.matched").value(true))
                    .andExpect(jsonPath("$.totalFleetSize").value(150))
                    .andExpect(jsonPath("$.decisionSummary").value("Matched with driver DRV-1 (total cost: 1.70)"))
                    // Stages verification
                    .andExpect(jsonPath("$.stages[0].stageIndex").value(0))
                    .andExpect(jsonPath("$.stages[0].stageType").value("QUAD_TREE_SEARCH"))
                    .andExpect(jsonPath("$.stages[0].windowSize").value(16))
                    .andExpect(jsonPath("$.stages[0].visitedNodeCount").value(12))
                    .andExpect(jsonPath("$.stages[0].prunedNodeCount").value(4))
                    .andExpect(jsonPath("$.stages[0].candidatesDiscovered").value(3))
                    // Candidates verification
                    .andExpect(jsonPath("$.candidates[0].driverId").value("DRV-1"))
                    .andExpect(jsonPath("$.candidates[0].distanceKm").value(0.5))
                    .andExpect(jsonPath("$.candidates[0].etaCost").value(1.2))
                    .andExpect(jsonPath("$.candidates[0].densityCost").value(0.4))
                    .andExpect(jsonPath("$.candidates[0].opportunityCost").value(0.1))
                    .andExpect(jsonPath("$.candidates[0].totalCost").value(1.7))
                    .andExpect(jsonPath("$.candidates[0].rank").value(0))
                    .andExpect(jsonPath("$.candidates[0].outcome").value("MATCHED"))
                    .andExpect(jsonPath("$.candidates[0].rejectionReason").doesNotExist())
                    .andExpect(jsonPath("$.candidates[1].driverId").value("DRV-2"))
                    .andExpect(jsonPath("$.candidates[1].outcome").value("OUTCOMPETED"))
                    .andExpect(jsonPath("$.candidates[1].rejectionReason").value("Higher total cost"));
        }

        @Test
        @DisplayName("Returns 200 OK via /requests/{requestId}/trace route alias")
        void returns200ViaRouteAlias() throws Exception {
            DispatchTrace trace = sampleTrace("REQ-ALIAS", "DRV-1", true);
            traceStore.put(trace);

            mockMvc.perform(get("/requests/REQ-ALIAS/trace"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-ALIAS"))
                    .andExpect(jsonPath("$.winnerDriverId").value("DRV-1"));
        }

        @Test
        @DisplayName("Returns 200 OK for unmatched trace with null winner and unmatched candidates")
        void returns200ForUnmatchedTrace() throws Exception {
            DispatchTrace unmatchedTrace = new DispatchTrace(
                    "REQ-NO-DRIVER",
                    "GreedyMatcher",
                    12.9716,
                    77.5946,
                    Instant.parse("2026-10-19T02:00:00Z"),
                    1_200_000L,
                    List.of(new SpatialStage(0, "QUAD_TREE_SEARCH", 16, null, null, null, null, 5, 0, 1)),
                    List.of(new TraceCandidate("DRV-OCCUPIED", 12.9720, 77.5950, 0.5, 1.2, 0.0, 0.0, 1.2, 0, CandidateOutcome.UNAVAILABLE, "Driver is not available")),
                    null,
                    false,
                    10,
                    "No eligible driver claimed"
            );
            traceStore.put(unmatchedTrace);

            mockMvc.perform(get("/api/requests/REQ-NO-DRIVER/trace"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-NO-DRIVER"))
                    .andExpect(jsonPath("$.matched").value(false))
                    .andExpect(jsonPath("$.winnerDriverId").doesNotExist())
                    .andExpect(jsonPath("$.candidates[0].outcome").value("UNAVAILABLE"));
        }
    }

    // -------------------------------------------------------------------------
    // Missing & Expired Traces (HTTP 404)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/requests/{requestId}/trace - Missing and Expired Traces")
    class MissingAndExpiredTraces {

        @Test
        @DisplayName("Returns 404 Not Found when trace does not exist in store")
        void returns404WhenTraceMissing() throws Exception {
            mockMvc.perform(get("/api/requests/REQ-UNKNOWN/trace"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"))
                    .andExpect(jsonPath("$.message").value("No dispatch trace found for requestId: REQ-UNKNOWN"));
        }

        @Test
        @DisplayName("Returns 404 Not Found when trace has expired past configured TTL")
        void returns404WhenTraceExpired() throws Exception {
            DispatchTrace trace = sampleTrace("REQ-EXPIRED", "DRV-1", true);
            traceStore.put(trace);

            // Verify it is accessible initially
            mockMvc.perform(get("/api/requests/REQ-EXPIRED/trace"))
                    .andExpect(status().isOk());

            // Advance clock past the 10-minute TTL (10 minutes + 1 second)
            clock.advance(Duration.ofMinutes(10).plusSeconds(1));

            // Verify subsequent retrieval returns 404 Not Found
            mockMvc.perform(get("/api/requests/REQ-EXPIRED/trace"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"))
                    .andExpect(jsonPath("$.message").value("No dispatch trace found for requestId: REQ-EXPIRED"));
        }

        @Test
        @DisplayName("Returns 404 Not Found when tracing is disabled globally")
        void returns404WhenTracingDisabled() throws Exception {
            DispatchTraceConfig disabledConfig = new DispatchTraceConfig(50, 10, 100, Duration.ofMinutes(10), false);
            DispatchTraceStore disabledStore = new DispatchTraceStore(disabledConfig, clock);
            DispatchTraceController disabledController = new DispatchTraceController(disabledStore);
            MockMvc localMvc = MockMvcBuilders.standaloneSetup(disabledController)
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();

            localMvc.perform(get("/api/requests/REQ-DISABLED/trace"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"))
                    .andExpect(jsonPath("$.message").value("No dispatch trace found for requestId: REQ-DISABLED"));
        }
    }

    // -------------------------------------------------------------------------
    // Request ID Validation & Malformed Input (HTTP 400)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/requests/{requestId}/trace - Validation & Malformed IDs")
    class ValidationAndMalformedIds {

        @Test
        @DisplayName("Returns 400 Bad Request when requestId is whitespace (%20)")
        void returns400WhenRequestIdIsWhitespace() throws Exception {
            mockMvc.perform(get("/api/requests/{requestId}/trace", " "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message").value("requestId must not be null or blank"));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when requestId contains multiple spaces (%20%20)")
        void returns400WhenRequestIdIsMultipleSpaces() throws Exception {
            mockMvc.perform(get("/api/requests/{requestId}/trace", "  "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message").value("requestId must not be null or blank"));
        }

        @Test
        @DisplayName("Returns 400 Bad Request when requestId contains tab whitespace (%09)")
        void returns400WhenRequestIdIsTabWhitespace() throws Exception {
            mockMvc.perform(get("/api/requests/{requestId}/trace", "\t"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message").value("requestId must not be null or blank"));
        }

        @Test
        @DisplayName("Throws InvalidRequestException when requestId is null or blank in direct call")
        void throwsInvalidRequestExceptionForNullOrBlankDirectCall() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    InvalidRequestException.class,
                    () -> controller.getTrace(null));

            org.junit.jupiter.api.Assertions.assertThrows(
                    InvalidRequestException.class,
                    () -> controller.getTrace(""));

            org.junit.jupiter.api.Assertions.assertThrows(
                    InvalidRequestException.class,
                    () -> controller.getTrace("   "));
        }
    }

    // -------------------------------------------------------------------------
    // Special Characters Preservation (+ and %)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/requests/{requestId}/trace - Special Characters Preservation")
    class SpecialCharactersPreservation {

        @Test
        @DisplayName("Retrieves trace with plus sign when encoded as REQ%2B100")
        void retrievesTraceWithPlusSign() throws Exception {
            DispatchTrace trace = sampleTrace("REQ+100", "DRV-1", true);
            traceStore.put(trace);

            mockMvc.perform(get("/api/requests/{requestId}/trace", "REQ+100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ+100"))
                    .andExpect(jsonPath("$.winnerDriverId").value("DRV-1"));
        }

        @Test
        @DisplayName("Retrieves trace with literal percent sign when encoded as REQ%2599")
        void retrievesTraceWithLiteralPercentSign() throws Exception {
            DispatchTrace trace = sampleTrace("REQ%99", "DRV-2", true);
            traceStore.put(trace);

            mockMvc.perform(get("/api/requests/{requestId}/trace", "REQ%99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ%99"))
                    .andExpect(jsonPath("$.winnerDriverId").value("DRV-2"));
        }
    }

    // -------------------------------------------------------------------------
    // Request ID Isolation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/requests/{requestId}/trace - Request ID Isolation")
    class RequestIsolation {

        @Test
        @DisplayName("Preserves strict isolation between distinct request IDs")
        void preservesRequestIdIsolation() throws Exception {
            DispatchTrace traceA = sampleTrace("REQ-ALPHA", "DRV-ALPHA", true);
            DispatchTrace traceB = sampleTrace("REQ-BETA", "DRV-BETA", true);
            traceStore.put(traceA);
            traceStore.put(traceB);

            // Query REQ-ALPHA
            mockMvc.perform(get("/api/requests/REQ-ALPHA/trace"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-ALPHA"))
                    .andExpect(jsonPath("$.winnerDriverId").value("DRV-ALPHA"));

            // Query REQ-BETA
            mockMvc.perform(get("/api/requests/REQ-BETA/trace"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-BETA"))
                    .andExpect(jsonPath("$.winnerDriverId").value("DRV-BETA"));

            // Query unrequested REQ-GAMMA does not return ALPHA or BETA
            mockMvc.perform(get("/api/requests/REQ-GAMMA/trace"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("No dispatch trace found for requestId: REQ-GAMMA"));
        }
    }

    // -------------------------------------------------------------------------
    // Non-interference with Existing Request Endpoints
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Non-interference with existing request-status endpoints")
    class RouteCoexistence {

        private RideRequestRepository rideRequestRepository;
        private AssignmentPersistenceService assignmentPersistenceService;
        private MockMvc combinedMockMvc;

        @BeforeEach
        void setUpCombined() {
            rideRequestRepository = mock(RideRequestRepository.class);
            assignmentPersistenceService = mock(AssignmentPersistenceService.class);
            RideController rideController = mock(RideController.class);
            DriverController driverController = mock(DriverController.class);
            DriverRegistry driverRegistry = new DriverRegistry();
            DriverFreshnessConfig freshnessConfig = DriverFreshnessConfig.DEFAULT;

            RequestStatusController requestStatusController = new RequestStatusController(
                    rideRequestRepository,
                    assignmentPersistenceService,
                    rideController,
                    driverController,
                    driverRegistry,
                    freshnessConfig
            );

            combinedMockMvc = MockMvcBuilders.standaloneSetup(controller, requestStatusController)
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();
        }

        @Test
        @DisplayName("GET /api/requests/{requestId} routes to RequestStatusController without collision")
        void getRequestStatus_routesCorrectly() throws Exception {
            RideRequestEntity entity = new RideRequestEntity("REQ-STATUS-1", 12.9716, 77.5946, "PENDING");
            when(rideRequestRepository.findByRequestId("REQ-STATUS-1")).thenReturn(Optional.of(entity));

            combinedMockMvc.perform(get("/api/requests/REQ-STATUS-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-STATUS-1"))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        @DisplayName("GET /api/requests/{requestId}/trace routes to DispatchTraceController without collision")
        void getRequestTrace_routesCorrectly() throws Exception {
            traceStore.put(sampleTrace("REQ-TRACE-1", "DRV-1", true));

            combinedMockMvc.perform(get("/api/requests/REQ-TRACE-1/trace"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestId").value("REQ-TRACE-1"))
                    .andExpect(jsonPath("$.algorithm").value("AuctionMatcher"));
        }

        @Test
        @DisplayName("Existing request status returns 404 for unknown request while trace endpoint returns its own 404")
        void bothEndpointsReturnDistinct404Messages() throws Exception {
            when(rideRequestRepository.findByRequestId("REQ-NONE")).thenReturn(Optional.empty());

            // Status endpoint 404 message
            combinedMockMvc.perform(get("/api/requests/REQ-NONE"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("No ride request found for requestId: REQ-NONE"));

            // Trace endpoint 404 message
            combinedMockMvc.perform(get("/api/requests/REQ-NONE/trace"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("No dispatch trace found for requestId: REQ-NONE"));
        }
    }
}
