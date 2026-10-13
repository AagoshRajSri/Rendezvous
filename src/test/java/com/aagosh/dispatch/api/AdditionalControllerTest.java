package com.aagosh.dispatch.api;

import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.matching.DispatchCostConfig;
import com.aagosh.dispatch.matching.GreedyMatcher;
import com.aagosh.dispatch.matching.Matcher;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Additional controller-layer tests covering previously untested paths.
 *
 * <ul>
 *   <li>HTTP 500 generic exception path in {@link GlobalExceptionHandler}</li>
 *   <li>GreedyMatcher 16-thread claim contention (single driver, one winner)</li>
 *   <li>AUTO-mode concurrent registration storm for the same driver ID</li>
 * </ul>
 */
class AdditionalControllerTest {

    private DriverRegistry driverRegistry;
    private MockMvc rideMvc;

    @BeforeEach
    void setUp() {
        driverRegistry = new DriverRegistry();
    }

    // -------------------------------------------------------------------------
    // 1. GlobalExceptionHandler — HTTP 500 generic exception path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Unhandled runtime exception from Matcher surfaces as HTTP 500")
    void matcherThrowingRuntimeExceptionReturnsHttp500() throws Exception {
        Matcher explosiveMatcher = mock(Matcher.class);
        when(explosiveMatcher.match(any(), any(), any()))
                .thenThrow(new RuntimeException("Simulated internal failure"));

        RideController controller = new RideController(
                explosiveMatcher, driverRegistry, DispatchCostConfig.DEFAULT);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "R-ERR", "latitude": 17.0, "longitude": 78.0}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An internal error occurred"));
    }

    // -------------------------------------------------------------------------
    // 2. GreedyMatcher — 16-thread claim contention on a single driver
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent /request via GreedyMatcher: exactly one winner for a single driver")
    void greedyMatcherConcurrentRequestsYieldExactlyOneWinner() throws Exception {
        Driver driver = new Driver("D_GREEDY", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(driver);

        RideController controller = new RideController(
                new GreedyMatcher(), driverRegistry, DispatchCostConfig.DEFAULT);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String reqId = "REQ-G-" + i;
            futures.add(pool.submit(() -> {
                barrier.await();
                return mvc.perform(post("/request")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(String.format(
                                        "{\"requestId\":\"%s\",\"latitude\":17.3855,\"longitude\":78.4870}",
                                        reqId)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        int winners = 0;
        String winningRequestId = null;
        for (Future<String> f : futures) {
            String body = f.get();
            boolean matched = com.jayway.jsonpath.JsonPath.read(body, "$.matched");
            if (matched) {
                winners++;
                winningRequestId = com.jayway.jsonpath.JsonPath.read(body, "$.requestId");
            }
        }

        assertEquals(1, winners,
                "GreedyMatcher must allow exactly one winner out of " + threads + " concurrent claims");

        Driver finalDriver = driverRegistry.findById("D_GREEDY").orElseThrow();
        assertEquals(MatchStatus.CLAIMED, finalDriver.state().getStatus());
        assertEquals(winningRequestId, finalDriver.state().getClaimedByRequestId());
    }

    // -------------------------------------------------------------------------
    // 3. AUTO-mode concurrent registration fallback — same driver ID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent AUTO /ping for same unregistered driver: exactly 1×201, rest 200")
    void concurrentAutoRegistrationFallbackForSameDriverId() throws Exception {
        DriverController controller = new DriverController(driverRegistry);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final double lat = 17.3850 + (i * 0.0001);
            futures.add(pool.submit(() -> {
                barrier.await();
                return mvc.perform(post("/ping")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(String.format(
                                        "{\"driverId\":\"D_AUTO_RACE\",\"latitude\":%f,\"longitude\":78.4867}",
                                        lat)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        int count201 = 0;
        int count200 = 0;
        for (Future<Integer> f : futures) {
            int status = f.get();
            if (status == 201) count201++;
            else if (status == 200) count200++;
        }

        assertEquals(1, count201,
                "Exactly one AUTO request must succeed registration (201)");
        assertEquals(threads - 1, count200,
                "All other AUTO requests must fall back to location update (200)");

        // Driver exists exactly once
        assertTrue(driverRegistry.findById("D_AUTO_RACE").isPresent());
        assertEquals(1, driverRegistry.snapshot().size());
    }
}
