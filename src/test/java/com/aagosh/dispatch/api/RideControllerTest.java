package com.aagosh.dispatch.api;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RideControllerTest {

    private DriverRegistry driverRegistry;
    private Matcher mockMatcher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        driverRegistry = new DriverRegistry();
        mockMatcher = mock(Matcher.class);
        RideController controller = new RideController(mockMatcher, driverRegistry, DispatchCostConfig.DEFAULT);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("1. Valid ride request delegates to injected Matcher and returns assignment")
    void validRideRequestReachesInjectedMatcher() throws Exception {
        Driver driver = new Driver("D100", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(driver);
        driver.state().tryClaim("REQ-1");

        RideRequest expectedDomainRequest = new RideRequest("REQ-1", new GeoPoint(17.3860, 78.4870));
        Assignment assignment = new Assignment(expectedDomainRequest, driver, 0.052);

        when(mockMatcher.match(any(), any(), any()))
                .thenReturn(List.of(assignment));

        String requestJson = """
                {
                    "requestId": "REQ-1",
                    "latitude": 17.3860,
                    "longitude": 78.4870
                }
                """;

        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-1"))
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.driverId").value("D100"))
                .andExpect(jsonPath("$.driverLatitude").value(17.3850))
                .andExpect(jsonPath("$.driverLongitude").value(78.4867))
                .andExpect(jsonPath("$.driverStatus").value("CLAIMED"))
                .andExpect(jsonPath("$.cost").value(0.052))
                .andExpect(jsonPath("$.message").value("Driver assigned successfully"));

        // Verify boundary: Matcher was invoked with the exact domain RideRequest and registered driver snapshot
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RideRequest>> reqCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Driver>> driverCaptor = ArgumentCaptor.forClass(List.class);

        verify(mockMatcher).match(reqCaptor.capture(), driverCaptor.capture(), any(CostFunction.class));

        assertEquals(1, reqCaptor.getValue().size());
        assertEquals("REQ-1", reqCaptor.getValue().get(0).id());
        assertEquals(1, driverCaptor.getValue().size());
        assertEquals("D100", driverCaptor.getValue().get(0).id());
    }

    @Test
    @DisplayName("2. No-match outcome returns matched: false with informative message")
    void noMatchOutcomeReturnsStructuredPayload() throws Exception {
        when(mockMatcher.match(any(), any(), any()))
                .thenReturn(List.of());

        String requestJson = """
                {
                    "requestId": "REQ-NO-FLEET",
                    "latitude": 17.3860,
                    "longitude": 78.4870
                }
                """;

        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-NO-FLEET"))
                .andExpect(jsonPath("$.matched").value(false))
                .andExpect(jsonPath("$.driverId").doesNotExist())
                .andExpect(jsonPath("$.message").value("No available driver could be matched"));
    }

    @Test
    @DisplayName("3. End-to-end integration: Real AuctionMatcher with real DriverRegistry")
    void endToEndMatchingWithRealAuctionMatcher() throws Exception {
        Driver d1 = new Driver("D_REAL", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(d1);

        // Real AuctionMatcher injected
        RideController realController = new RideController(new AuctionMatcher(), driverRegistry);
        MockMvc realMvc = MockMvcBuilders.standaloneSetup(realController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        String requestJson = """
                {
                    "requestId": "REQ-REAL",
                    "latitude": 17.3860,
                    "longitude": 78.4870
                }
                """;

        realMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-REAL"))
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.driverId").value("D_REAL"))
                .andExpect(jsonPath("$.driverStatus").value("CLAIMED"))
                .andExpect(jsonPath("$.cost").isNumber());

        // Verify driver in registry is now CLAIMED by REQ-REAL
        Driver stored = driverRegistry.findById("D_REAL").orElseThrow();
        assertEquals(MatchStatus.CLAIMED, stored.state().getStatus());
        assertEquals("REQ-REAL", stored.state().getClaimedByRequestId());
    }

    @Test
    @DisplayName("4. Invalid pickup coordinates reject with HTTP 400 Bad Request")
    void invalidPickupCoordinatesRejected() throws Exception {
        // Latitude > 90
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "R1", "latitude": 95.0, "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));

        // Latitude < -90
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "R1", "latitude": -91.0, "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest());

        // Longitude > 180
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "R1", "latitude": 17.0, "longitude": 185.0}
                                """))
                .andExpect(status().isBadRequest());

        // Longitude < -180
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "R1", "latitude": 17.0, "longitude": -185.0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("5. Malformed ride requests reject with HTTP 400 Bad Request")
    void malformedRideRequestsRejected() throws Exception {
        // Blank requestId
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "   ", "latitude": 17.0, "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("requestId must not be null or blank"));

        // Missing requestId
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude": 17.0, "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest());

        // Missing latitude
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "R1", "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest());

        // Malformed JSON syntax
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or unreadable JSON request body"));
    }

    @Test
    @DisplayName("6. Unclaimed driver assignment from non-compliant matcher is safely rejected with matched: false")
    void unclaimedDriverAssignmentIsRejected() throws Exception {
        Driver unclaimedDriver = new Driver("D_UNCLAIMED", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(unclaimedDriver);

        RideRequest domainRequest = new RideRequest("REQ-UNCLAIMED", new GeoPoint(17.3860, 78.4870));
        // Non-compliant matcher returns an assignment without atomically claiming the driver
        Assignment bogusAssignment = new Assignment(domainRequest, unclaimedDriver, 0.05);

        when(mockMatcher.match(any(), any(), any()))
                .thenReturn(List.of(bogusAssignment));

        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "REQ-UNCLAIMED", "latitude": 17.3860, "longitude": 78.4870}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-UNCLAIMED"))
                .andExpect(jsonPath("$.matched").value(false))
                .andExpect(jsonPath("$.message").value("No available driver could be matched"));
    }

    @Test
    @DisplayName("7. Concurrent /request calls competing for a single driver yield exactly one winner")
    void concurrentRideRequestsCompeteForSingleDriver() throws Exception {
        Driver driver = new Driver("D_CONCURRENT", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(driver);

        RideController realController = new RideController(new AuctionMatcher(), driverRegistry);
        MockMvc realMvc = MockMvcBuilders.standaloneSetup(realController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        int numThreads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final String reqId = "REQ-CONC-" + i;
            futures.add(executor.submit(() -> {
                barrier.await();
                return realMvc.perform(post("/request")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(String.format("{\"requestId\":\"%s\",\"latitude\":17.3850,\"longitude\":78.4867}", reqId)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
            }));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        int matchedCount = 0;
        int unmatchedCount = 0;
        String winningRequestId = null;

        for (Future<String> future : futures) {
            String responseBody = future.get();
            boolean matched = com.jayway.jsonpath.JsonPath.read(responseBody, "$.matched");
            if (matched) {
                matchedCount++;
                winningRequestId = com.jayway.jsonpath.JsonPath.read(responseBody, "$.requestId");
                String driverId = com.jayway.jsonpath.JsonPath.read(responseBody, "$.driverId");
                assertEquals("D_CONCURRENT", driverId);
            } else {
                unmatchedCount++;
                String msg = com.jayway.jsonpath.JsonPath.read(responseBody, "$.message");
                assertEquals("No available driver could be matched", msg);
            }
        }

        assertEquals(1, matchedCount, "Exactly one request must be matched to the single available driver");
        assertEquals(numThreads - 1, unmatchedCount, "All other requests must report no-match");
        assertNotNull(winningRequestId);

        // Verify driver state in registry: driver is CLAIMED by the winning request
        Driver finalDriver = driverRegistry.findById("D_CONCURRENT").orElseThrow();
        assertEquals(MatchStatus.CLAIMED, finalDriver.state().getStatus());
        assertEquals(winningRequestId, finalDriver.state().getClaimedByRequestId());
    }
}
