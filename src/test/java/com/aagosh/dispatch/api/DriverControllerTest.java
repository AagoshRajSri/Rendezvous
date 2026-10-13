package com.aagosh.dispatch.api;

import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DriverControllerTest {

    private DriverRegistry driverRegistry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        driverRegistry = new DriverRegistry();
        DriverController controller = new DriverController(driverRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("1. POST /ping registers new driver when action is AUTO or omitted")
    void validDriverRegistrationViaPing() throws Exception {
        String json = """
                {
                    "driverId": "D1",
                    "latitude": 17.3850,
                    "longitude": 78.4867
                }
                """;

        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.driverId").value("D1"))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.latitude").value(17.3850))
                .andExpect(jsonPath("$.longitude").value(78.4867))
                .andExpect(jsonPath("$.matchStatus").value("AVAILABLE"));

        assertTrue(driverRegistry.findById("D1").isPresent());
        assertEquals(17.3850, driverRegistry.findById("D1").get().location().latitude());
    }

    @Test
    @DisplayName("2. POST /register explicitly registers driver with HTTP 201 Created")
    void validDriverRegistrationViaRegisterEndpoint() throws Exception {
        String json = """
                {
                    "driverId": "D_REG",
                    "latitude": 17.4400,
                    "longitude": 78.3489
                }
                """;

        mockMvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.driverId").value("D_REG"))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.matchStatus").value("AVAILABLE"));

        assertTrue(driverRegistry.findById("D_REG").isPresent());
    }

    @Test
    @DisplayName("3. POST /ping updates location and preserves existing DriverState and claim")
    void validLocationUpdatePreservesDriverState() throws Exception {
        // Register driver and simulate an in-progress dispatch claim
        Driver initial = new Driver("D_CLAIMED", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(initial);
        assertTrue(initial.state().tryClaim("REQ-888"));
        assertEquals(MatchStatus.CLAIMED, initial.state().getStatus());

        String pingJson = """
                {
                    "driverId": "D_CLAIMED",
                    "latitude": 17.3900,
                    "longitude": 78.4900
                }
                """;

        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pingJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value("D_CLAIMED"))
                .andExpect(jsonPath("$.status").value("UPDATED"))
                .andExpect(jsonPath("$.latitude").value(17.3900))
                .andExpect(jsonPath("$.longitude").value(78.4900))
                .andExpect(jsonPath("$.matchStatus").value("CLAIMED"));

        Driver updated = driverRegistry.findById("D_CLAIMED").orElseThrow();
        assertEquals(17.3900, updated.location().latitude());
        assertEquals(78.4900, updated.location().longitude());
        // Crucial guarantee: state object and claim status were preserved across location telemetry
        assertEquals(MatchStatus.CLAIMED, updated.state().getStatus());
        assertEquals("REQ-888", updated.state().getClaimedByRequestId());
    }

    @Test
    @DisplayName("4. Explicit registration of duplicate driver returns HTTP 409 Conflict")
    void duplicateRegistrationReturnsConflict() throws Exception {
        driverRegistry.register(new Driver("D_EXISTS", new GeoPoint(17.0, 78.0), new DriverState()));

        String duplicateJson = """
                {
                    "driverId": "D_EXISTS",
                    "latitude": 17.1,
                    "longitude": 78.1,
                    "action": "REGISTER"
                }
                """;

        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Driver already registered: D_EXISTS"));
    }

    @Test
    @DisplayName("5. Explicit location update for unknown driver returns HTTP 404 Not Found")
    void unknownDriverUpdateReturnsNotFound() throws Exception {
        String updateJson = """
                {
                    "driverId": "D_UNKNOWN",
                    "latitude": 17.1,
                    "longitude": 78.1,
                    "action": "UPDATE"
                }
                """;

        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("No driver registered with ID: D_UNKNOWN"));
    }

    @Test
    @DisplayName("6. Invalid coordinates reject with HTTP 400 Bad Request")
    void invalidCoordinatesRejected() throws Exception {
        // Latitude > 90
        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"driverId": "D1", "latitude": 91.5, "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));

        // Latitude < -90
        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"driverId": "D1", "latitude": -95.0, "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest());

        // Longitude > 180
        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"driverId": "D1", "latitude": 17.0, "longitude": 181.0}
                                """))
                .andExpect(status().isBadRequest());

        // Longitude < -180
        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"driverId": "D1", "latitude": 17.0, "longitude": -185.0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("7. Malformed request input rejects with HTTP 400 Bad Request")
    void malformedInputRejected() throws Exception {
        // Blank driverId
        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"driverId": "   ", "latitude": 17.0, "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("driverId must not be null or blank"));

        // Missing driverId
        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude": 17.0, "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest());

        // Missing latitude
        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"driverId": "D1", "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest());

        // Malformed JSON syntax
        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or unreadable JSON request body"));
    }

    @Test
    @DisplayName("8. Concurrent /ping registration calls for the same unregistered driver yield exactly one 201 and remaining 200s")
    void concurrentPingRegistrationStorm() throws Exception {
        int numThreads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        List<Future<Integer>> statusFutures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final double lat = 17.3850 + (i * 0.0001);
            statusFutures.add(executor.submit(() -> {
                barrier.await();
                return mockMvc.perform(post("/ping")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(String.format("{\"driverId\":\"D_STORM\",\"latitude\":%f,\"longitude\":78.4867}", lat)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            }));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        int count201 = 0;
        int count200 = 0;
        for (Future<Integer> f : statusFutures) {
            int status = f.get();
            if (status == 201) count201++;
            else if (status == 200) count200++;
        }

        assertEquals(1, count201, "Exactly one request must report registration with HTTP 201");
        assertEquals(numThreads - 1, count200, "All other requests must report location update with HTTP 200");

        // Driver must exist exactly once in the registry
        assertTrue(driverRegistry.findById("D_STORM").isPresent());
        assertEquals(1, driverRegistry.snapshot().size());
    }

    @Test
    @DisplayName("9. Concurrent /ping updates on an already claimed driver never reset its claim state")
    void concurrentPingOnClaimedDriverPreservesClaimState() throws Exception {
        Driver claimedDriver = new Driver("D_PRECLAIMED", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(claimedDriver);
        assertTrue(claimedDriver.state().tryClaim("JOB-42"));

        int numThreads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);
        List<Future<Integer>> statusFutures = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final double lat = 17.3850 + (i * 0.0001);
            statusFutures.add(executor.submit(() -> {
                barrier.await();
                return mockMvc.perform(post("/ping")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(String.format("{\"driverId\":\"D_PRECLAIMED\",\"latitude\":%f,\"longitude\":78.4867}", lat)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            }));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        for (Future<Integer> f : statusFutures) {
            assertEquals(200, f.get());
        }

        // Must still be CLAIMED by JOB-42
        Driver finalDriver = driverRegistry.findById("D_PRECLAIMED").orElseThrow();
        assertEquals(MatchStatus.CLAIMED, finalDriver.state().getStatus());
        assertEquals("JOB-42", finalDriver.state().getClaimedByRequestId());
    }
}
