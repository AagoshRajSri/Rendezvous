package com.aagosh.dispatch.api;

import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.matching.CostFunction;
import com.aagosh.dispatch.matching.DispatchCostConfig;
import com.aagosh.dispatch.matching.DispatchCostFunction;
import com.aagosh.dispatch.matching.GreedyMatcher;
import com.aagosh.dispatch.messaging.DriverPingConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
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
 * Additional controller and concurrency tests covering:
 * <ul>
 *   <li>HTTP 500 generic exception path in {@link GlobalExceptionHandler}</li>
 *   <li>GreedyMatcher 16-thread claim contention (single driver, exactly one winner)</li>
 *   <li>DriverPingConsumer AUTO-mode concurrent registration race for the same driver ID</li>
 * </ul>
 */
class AdditionalControllerTest {

    private DriverRegistry driverRegistry;

    @BeforeEach
    void setUp() {
        driverRegistry = new DriverRegistry();
    }

    // -------------------------------------------------------------------------
    // 1. GlobalExceptionHandler — HTTP 500 generic exception path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Unhandled runtime exception surfaces as HTTP 500")
    @SuppressWarnings("unchecked")
    void unhandledRuntimeExceptionReturnsHttp500() throws Exception {
        KafkaTemplate<String, RideRequestDto> explosiveKafka = mock(KafkaTemplate.class);
        when(explosiveKafka.send(any(), any(), any()))
                .thenThrow(new NullPointerException("Unexpected runtime null"));

        RideController controller = new RideController(explosiveKafka);
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
    @DisplayName("Concurrent matching via GreedyMatcher: exactly one winner for a single driver")
    void greedyMatcherConcurrentRequestsYieldExactlyOneWinner() throws Exception {
        Driver driver = new Driver("D_GREEDY", new GeoPoint(17.3850, 78.4867), new DriverState());
        driverRegistry.register(driver);

        GreedyMatcher matcher = new GreedyMatcher();
        DispatchCostConfig config = DispatchCostConfig.DEFAULT;

        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<List<Assignment>>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String reqId = "REQ-G-" + i;
            futures.add(pool.submit(() -> {
                barrier.await();
                RideRequest domainReq = new RideRequest(reqId, new GeoPoint(17.3855, 78.4870));
                List<Driver> snapshot = driverRegistry.snapshot();
                CostFunction costFn = new DispatchCostFunction(config, List.of(domainReq));
                return matcher.match(List.of(domainReq), snapshot, costFn);
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        int winners = 0;
        String winningRequestId = null;
        for (Future<List<Assignment>> f : futures) {
            List<Assignment> assignments = f.get();
            if (!assignments.isEmpty()) {
                winners++;
                winningRequestId = assignments.get(0).request().id();
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
    @DisplayName("Concurrent AUTO driver ping events for same unregistered driver: safely registers once")
    void concurrentAutoRegistrationInConsumer() throws Exception {
        DriverPingConsumer consumer = new DriverPingConsumer(driverRegistry);
        Acknowledgment ack = mock(Acknowledgment.class);

        int threads = 16;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final double lat = 17.3850 + (i * 0.0001);
            futures.add(pool.submit(() -> {
                barrier.await();
                DriverPingRequest req = new DriverPingRequest("D_AUTO_RACE", lat, 78.4867, PingAction.AUTO);
                ConsumerRecord<String, DriverPingRequest> record =
                        new ConsumerRecord<>(KafkaTopics.DRIVER_PINGS, 0, 0, "D_AUTO_RACE", req);
                consumer.onDriverPing(record, ack);
                return null;
            }));
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        for (Future<Void> f : futures) {
            f.get(); // Ensure no exceptions thrown
        }

        // Driver exists exactly once in registry
        assertTrue(driverRegistry.findById("D_AUTO_RACE").isPresent());
        assertEquals(1, driverRegistry.snapshot().size());
        assertEquals(MatchStatus.AVAILABLE, driverRegistry.findById("D_AUTO_RACE").get().state().getStatus());
    }
}
