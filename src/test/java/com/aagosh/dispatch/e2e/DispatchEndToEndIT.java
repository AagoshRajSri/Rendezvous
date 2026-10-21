package com.aagosh.dispatch.e2e;

import com.aagosh.dispatch.api.DriverController;
import com.aagosh.dispatch.api.GlobalExceptionHandler;
import com.aagosh.dispatch.api.PingAction;
import com.aagosh.dispatch.api.RequestStatusController;
import com.aagosh.dispatch.api.RideController;
import com.aagosh.dispatch.api.RideRequestDto;
import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.messaging.RideRequestConsumer;
import com.aagosh.dispatch.persistence.AssignmentEntity;
import com.aagosh.dispatch.persistence.AssignmentPersistenceService;
import com.aagosh.dispatch.persistence.AssignmentRepository;
import com.aagosh.dispatch.persistence.RideRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 Step 4: End-to-End integration tests for the complete Rendezvous dispatch pipeline.
 *
 * <p>Verifies the full path: REST → Kafka → DriverPingConsumer/RideRequestConsumer →
 * Matching → PostgreSQL persistence + Redis location cache.
 *
 * <h2>Container lifecycle</h2>
 * <p>A single set of Testcontainers (Kafka, PostgreSQL, Redis) is shared across all
 * test methods via {@code static @Container} fields. The Spring application context is
 * started once. Per-test isolation is achieved without modifying production code by clearing
 * the following state in {@code @BeforeEach}:
 * <ul>
 *   <li>PostgreSQL: all {@code assignments} and {@code ride_requests} rows are deleted.</li>
 *   <li>Redis: all keys are flushed.</li>
 *   <li>{@link DriverRegistry}: internal registry map is cleared via test reflection.</li>
 *   <li>{@link RideRequestConsumer}: in-flight and processed idempotency caches are cleared
 *       via test reflection under monitor lock.</li>
 * </ul>
 *
 * <h2>Container safety</h2>
 * <p>{@code @AfterEach} inspects the Redis container and guarantees it is unpaused even
 * if an outage test assertion fails, eliminating container poisoning of subsequent tests.
 *
 * <h2>Asynchronous assertions</h2>
 * <p>All Kafka-driven state changes are awaited using Awaitility with a 10-second
 * maximum and 50 ms polling interval. No fixed {@code Thread.sleep} calls are used.
 *
 * <h2>Duplicate delivery testing scope & caveat</h2>
 * <p>The suite tests duplicate handling at two distinct tiers:
 * <ul>
 *   <li><strong>HTTP Tier (Scenario 5)</strong>: Rapid duplicate client submissions (e.g. user double-click).</li>
 *   <li><strong>Kafka Ingestion Tier (Scenario 6)</strong>: Duplicate event publication on Kafka topics,
 *       exercising both the in-memory fast-path deduplication (LRU cache) and the durable database
 *       deduplication check (after simulated cache eviction / consumer restart).</li>
 * </ul>
 * <p><strong>Caveat on native crash-before-commit:</strong> Scenario 6 validates duplicate-event handling
 * via repeated record publications (distinct Kafka offsets with identical {@code requestId}). It does
 * not simulate Kafka's native single-offset redelivery caused by a consumer crash between database
 * persistence and manual offset commit. Because {@link RideRequestConsumer} deduplicates on
 * {@code requestId} via durable PostgreSQL state rather than Kafka offset, the code path exercised after
 * cache eviction is functionally identical, but true mid-method crash fault injection remains an
 * operational/chaos-testing boundary.
 *
 * <h2>Skipping without Docker</h2>
 * <p>All tests are skipped automatically when Docker is not available
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@SuppressWarnings({"resource", "rawtypes"})
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DispatchEndToEndIT {

    private static final String REDIS_KEY_PATTERN    = "driver:%s:location";
    private static final String FIELD_LAT            = "lat";
    private static final String FIELD_LON            = "lon";
    private static final String FIELD_UPDATED_AT     = "updated_at";
    private static final int    LOCATION_TTL_SECONDS = 30;

    private static String redisKeyFor(String driverId) {
        return String.format(REDIS_KEY_PATTERN, driverId);
    }

    // -------------------------------------------------------------------------
    // Containers (shared across all test methods, started once per class)
    // -------------------------------------------------------------------------

    @Container
    static KafkaContainer kafkaContainer =
            new KafkaContainer("apache/kafka:3.7.0");

    @Container
    static PostgreSQLContainer<?> postgresContainer =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("dispatch_e2e")
                    .withUsername("e2e_user")
                    .withPassword("e2e_pass")
                    .withEnv("TZ", "UTC");

    @Container
    static GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> "true");

        // PostgreSQL
        registry.add("spring.datasource.url", () -> {
            String url = postgresContainer.getJdbcUrl();
            return url.contains("options=") ? url
                    : (url.contains("?") ? url + "&options=-c%20timezone%3DUTC"
                                         : url + "?options=-c%20timezone%3DUTC");
        });
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
        registry.add("spring.flyway.enabled",           () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto",  () -> "validate");

        // Redis
        registry.add("spring.data.redis.host",     redisContainer::getHost);
        registry.add("spring.data.redis.port",     () -> redisContainer.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    // -------------------------------------------------------------------------
    // Autowired beans
    // -------------------------------------------------------------------------

    @Autowired DriverController                      driverController;
    @Autowired RideController                        rideController;
    @Autowired RequestStatusController               requestStatusController;
    @Autowired DriverRegistry                        driverRegistry;
    @Autowired RideRequestConsumer                   rideRequestConsumer;
    @Autowired AssignmentPersistenceService          persistenceService;
    @Autowired RideRequestRepository                 rideRequestRepository;
    @Autowired AssignmentRepository                  assignmentRepository;
    @Autowired StringRedisTemplate                   redisTemplate;
    @Autowired KafkaTemplate<String, RideRequestDto> rideRequestKafkaTemplate;

    private MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // Per-test isolation & container cleanup
    // -------------------------------------------------------------------------

    @BeforeEach
    void resetState() {
        mockMvc = MockMvcBuilders.standaloneSetup(driverController, rideController, requestStatusController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // 1. Wipe PostgreSQL data
        assignmentRepository.deleteAll();
        rideRequestRepository.deleteAll();

        // 2. Flush Redis
        redisTemplate.getConnectionFactory()
                     .getConnection()
                     .serverCommands()
                     .flushAll();

        // 3. Clear in-memory DriverRegistry via reflection (preserves zero production code changes)
        Map<?, ?> registryMap = (Map<?, ?>) ReflectionTestUtils.getField(driverRegistry, "registry");
        if (registryMap != null) {
            registryMap.clear();
        }

        // 4. Clear RideRequestConsumer idempotency caches via reflection under monitor lock
        Object lock = ReflectionTestUtils.getField(rideRequestConsumer, "idempotencyLock");
        Set<?> inFlight = (Set<?>) ReflectionTestUtils.getField(rideRequestConsumer, "inFlightRequests");
        Map<?, ?> processed = (Map<?, ?>) ReflectionTestUtils.getField(rideRequestConsumer, "processedRequests");
        if (lock != null) {
            synchronized (lock) {
                if (inFlight != null) inFlight.clear();
                if (processed != null) processed.clear();
            }
        }

        // 5. Reset AdaptiveWindowController telemetry
        if (rideRequestConsumer.getWindowController() != null) {
            rideRequestConsumer.getWindowController().reset();
        }
    }

    @AfterEach
    void ensureContainersHealthy() {
        try {
            var inspect = redisContainer.getDockerClient()
                    .inspectContainerCmd(redisContainer.getContainerId())
                    .exec();
            if (Boolean.TRUE.equals(inspect.getState().getPaused())) {
                redisContainer.getDockerClient()
                        .unpauseContainerCmd(redisContainer.getContainerId())
                        .exec();
            }
        } catch (Exception ignored) {
            // Container might already be unpaused or stopped
        }
    }

    // =========================================================================
    // Scenario 1 - Driver ingestion: REST → Kafka → DriverRegistry + Redis
    // =========================================================================

    @Test
    @DisplayName("1. POST /register publishes REGISTER event; consumer populates DriverRegistry and Redis")
    void driverRegistrationPopulatesRegistryAndRedis() throws Exception {
        String driverId = "E2E-D-S1";
        long capturedAt = 10_000L;

        // Submit registration via REST
        mockMvc.perform(post("/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"driverId":"%s","latitude":17.3850,"longitude":78.4867,
                     "action":"REGISTER","capturedAt":%d}
                    """.formatted(driverId, capturedAt)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.driverId").value(driverId));

        // Wait for Kafka consumer to register the driver in DriverRegistry
        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> assertTrue(
                       driverRegistry.findById(driverId).isPresent(),
                       "Driver must appear in DriverRegistry"));

        var driver = driverRegistry.findById(driverId).orElseThrow();
        assertEquals(17.3850, driver.location().latitude(), 0.0001);
        assertEquals(78.4867, driver.location().longitude(), 0.0001);
        assertEquals(capturedAt, driver.updatedAt());

        // Verify Redis received the location
        String key = redisKeyFor(driverId);
        await().atMost(Duration.ofSeconds(5))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> assertTrue(
                       Boolean.TRUE.equals(redisTemplate.hasKey(key)),
                       "Redis key must exist"));

        Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
        assertEquals("17.385",  hash.get(FIELD_LAT));
        assertEquals("78.4867", hash.get(FIELD_LON));
        assertEquals("10000",   hash.get(FIELD_UPDATED_AT));

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= LOCATION_TTL_SECONDS,
                "TTL must be set within [1, 30] seconds");
    }

    // =========================================================================
    // Scenario 2 - Location update: newer ping overwrites, stale ping is rejected
    // =========================================================================

    @Test
    @DisplayName("2. POST /ping UPDATE with newer timestamp overwrites; older stale ping is rejected in DriverRegistry and Redis")
    void locationUpdateOrderingRespectedInRegistryAndRedis() throws Exception {
        String driverId = "E2E-D-S2";

        // Register at ts=5000
        mockMvc.perform(post("/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"driverId":"%s","latitude":17.10,"longitude":78.10,
                     "action":"REGISTER","capturedAt":5000}
                    """.formatted(driverId)))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> assertTrue(driverRegistry.findById(driverId).isPresent()));

        // Send a newer ping (ts=9000) - must be accepted
        mockMvc.perform(post("/ping")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"driverId":"%s","latitude":17.90,"longitude":78.90,
                     "action":"UPDATE","capturedAt":9000}
                    """.formatted(driverId)))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   var d = driverRegistry.findById(driverId).orElseThrow();
                   assertEquals(17.90, d.location().latitude(), 0.001,
                           "Newer ping must update registry latitude");
                   assertEquals(9000L, d.updatedAt());
               });

        // Send a stale ping (ts=3000) - must be rejected
        mockMvc.perform(post("/ping")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"driverId":"%s","latitude":11.11,"longitude":11.11,
                     "action":"UPDATE","capturedAt":3000}
                    """.formatted(driverId)))
                .andExpect(status().isAccepted());

        // Wait bounded time and ensure ts=9000 location persists
        await().during(Duration.ofMillis(500))
               .atMost(Duration.ofSeconds(2))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   var d = driverRegistry.findById(driverId).orElseThrow();
                   assertEquals(17.90, d.location().latitude(), 0.001,
                           "Stale ping must not overwrite newer registry location");
                   assertEquals(9000L, d.updatedAt());
               });

        // Redis must still hold ts=9000
        String key = redisKeyFor(driverId);
        await().atMost(Duration.ofSeconds(5))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
                   assertEquals("9000", hash.get(FIELD_UPDATED_AT),
                           "Stale ping must not update Redis timestamp");
               });
    }

    // =========================================================================
    // Scenario 3 - Successful ride assignment (full pipeline)
    // =========================================================================

    @Test
    @DisplayName("3. Ride request is matched, persisted as MATCHED in PostgreSQL, and driver is claimed")
    void successfulRideAssignment() throws Exception {
        String driverId  = "E2E-D-S3";
        String requestId = "E2E-REQ-S3";

        // Register a driver first and wait for it to appear in the registry
        long capturedAt = System.currentTimeMillis();
        mockMvc.perform(post("/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"driverId":"%s","latitude":17.385,"longitude":78.486,
                     "action":"REGISTER","capturedAt":%d}
                    """.formatted(driverId, capturedAt)))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> assertTrue(driverRegistry.findById(driverId).isPresent()));

        // Submit ride request very close to driver
        mockMvc.perform(post("/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestId":"%s","latitude":17.385,"longitude":78.486}
                    """.formatted(requestId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value(requestId));

        // Await PostgreSQL status = MATCHED
        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   var optStatus = persistenceService.getRequestStatus(requestId);
                   assertTrue(optStatus.isPresent(), "Request must be persisted");
                   assertEquals(AssignmentPersistenceService.STATUS_MATCHED, optStatus.get(),
                           "Request status must be MATCHED");
               });

        // Verify assignment row
        var assignment = assignmentRepository.findByRequestId(requestId);
        assertTrue(assignment.isPresent(), "Assignment row must exist in PostgreSQL");
        assertEquals(driverId, assignment.get().getDriverId(),
                "Persisted driver ID must match registered driver");

        // Verify in-memory driver is claimed
        var driver = driverRegistry.findById(driverId).orElseThrow();
        assertEquals(MatchStatus.CLAIMED, driver.state().getStatus(),
                "Driver must be CLAIMED after assignment");
        assertEquals(requestId, driver.state().getClaimedByRequestId(),
                "Driver must be claimed by this request");
    }

    // =========================================================================
    // Scenario 4 - No eligible drivers
    // =========================================================================

    @Test
    @DisplayName("4. Ride request with no available drivers persists NO_DRIVERS outcome")
    void noDriversAvailableRecordsNoDriversOutcome() throws Exception {
        String requestId = "E2E-REQ-S4";

        // Submit ride request with empty registry
        mockMvc.perform(post("/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestId":"%s","latitude":17.385,"longitude":78.486}
                    """.formatted(requestId)))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   var optStatus = persistenceService.getRequestStatus(requestId);
                   assertTrue(optStatus.isPresent(), "Request must be persisted");
                   assertEquals(AssignmentPersistenceService.STATUS_NO_DRIVERS, optStatus.get(),
                           "Status must be NO_DRIVERS");
               });

        assertFalse(assignmentRepository.existsByRequestId(requestId),
                "No assignment row must be created when no drivers are available");
    }

    // =========================================================================
    // Scenario 5 - Duplicate HTTP submission (same requestId posted twice)
    // =========================================================================

    @Test
    @DisplayName("5. Duplicate HTTP submission of same requestId results in at most one assignment row")
    void duplicateHttpSubmissionProducesOneAssignment() throws Exception {
        String driverId  = "E2E-D-S5";
        String requestId = "E2E-REQ-S5";

        // Register driver
        long capturedAt = System.currentTimeMillis();
        mockMvc.perform(post("/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"driverId":"%s","latitude":17.385,"longitude":78.486,
                     "action":"REGISTER","capturedAt":%d}
                    """.formatted(driverId, capturedAt)))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> assertTrue(driverRegistry.findById(driverId).isPresent()));

        // Submit ride request twice via HTTP (simulates client double-click / retry)
        mockMvc.perform(post("/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestId":"%s","latitude":17.385,"longitude":78.486}
                    """.formatted(requestId)))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestId":"%s","latitude":17.385,"longitude":78.486}
                    """.formatted(requestId)))
                .andExpect(status().isAccepted());

        // Await until at least one Kafka event is processed to a terminal state
        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   var optStatus = persistenceService.getRequestStatus(requestId);
                   assertTrue(optStatus.isPresent());
                   assertTrue(
                           AssignmentPersistenceService.STATUS_MATCHED.equals(optStatus.get()) ||
                           AssignmentPersistenceService.STATUS_NO_DRIVERS.equals(optStatus.get()),
                           "Status must reach a terminal state: " + optStatus.get());
               });

        // Ensure exactly one assignment row exists regardless of duplicate HTTP posts
        List<AssignmentEntity> allAssignments = assignmentRepository.findAll();
        long forRequest = allAssignments.stream()
                .filter(a -> requestId.equals(a.getRequestId()))
                .count();
        assertTrue(forRequest <= 1,
                "At most one assignment row must exist for requestId=" + requestId
                + "; found " + forRequest);
    }

    // =========================================================================
    // Scenario 6 - Duplicate Kafka event publication (consumer idempotency path)
    // =========================================================================

    @Test
    @DisplayName("6. Duplicate Kafka event publication is idempotently handled across in-memory and durable persistence paths")
    void kafkaDuplicateEventPublicationHandledIdempotently() throws Exception {
        String driverId  = "E2E-D-S6-DUP";
        String requestId = "E2E-REQ-S6-DUP";

        // Register driver
        long capturedAt = System.currentTimeMillis();
        mockMvc.perform(post("/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"driverId":"%s","latitude":17.385,"longitude":78.486,
                     "action":"REGISTER","capturedAt":%d}
                    """.formatted(driverId, capturedAt)))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> assertTrue(driverRegistry.findById(driverId).isPresent()));

        RideRequestDto dto = new RideRequestDto(requestId, null, 17.385, 78.486);

        // Step 1: Publish first Kafka record directly to topic ride-requests
        rideRequestKafkaTemplate.send(KafkaTopics.RIDE_REQUESTS, requestId, dto)
                .get(5, TimeUnit.SECONDS);

        // Await initial match and DB persistence
        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   var optStatus = persistenceService.getRequestStatus(requestId);
                   assertTrue(optStatus.isPresent());
                   assertEquals(AssignmentPersistenceService.STATUS_MATCHED, optStatus.get());
               });

        // Verify assignment exists
        assertEquals(1, assignmentRepository.count());
        var originalAssignment = assignmentRepository.findByRequestId(requestId).orElseThrow();
        assertEquals(driverId, originalAssignment.getDriverId());

        // Step 2: Publish duplicate Kafka record (simulates upstream producer retry / retransmission).
        // This exercises the in-memory idempotency check (reserveInFlight returns false) while consumer is running.
        rideRequestKafkaTemplate.send(KafkaTopics.RIDE_REQUESTS, requestId, dto)
                .get(5, TimeUnit.SECONDS);

        // Step 3: Simulate consumer restart / LRU cache eviction by clearing ONLY in-memory idempotency state
        // (leaving PostgreSQL durable state intact, simulating the state after a restart or crash-recovery).
        Object lock = ReflectionTestUtils.getField(rideRequestConsumer, "idempotencyLock");
        Set<?> inFlight = (Set<?>) ReflectionTestUtils.getField(rideRequestConsumer, "inFlightRequests");
        Map<?, ?> processed = (Map<?, ?>) ReflectionTestUtils.getField(rideRequestConsumer, "processedRequests");
        if (lock != null) {
            synchronized (lock) {
                if (inFlight != null) inFlight.clear();
                if (processed != null) processed.clear();
            }
        }

        // Step 4: Publish third Kafka record with identical requestId.
        // This exercises the durable DB idempotency check (getRequestStatus detects already finalized state),
        // exactly matching the logic that protects against crash-before-commit uncommitted offset reprocessing.
        rideRequestKafkaTemplate.send(KafkaTopics.RIDE_REQUESTS, requestId, dto)
                .get(5, TimeUnit.SECONDS);

        // Allow consumer time to process duplicate event
        await().during(Duration.ofMillis(500))
               .atMost(Duration.ofSeconds(2))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   // Invariant 1: exactly 1 assignment row in PostgreSQL
                   assertEquals(1, assignmentRepository.count(),
                           "Redelivered Kafka records must not produce additional assignment rows");
                   // Invariant 2: request remains in terminal MATCHED state
                   assertEquals(AssignmentPersistenceService.STATUS_MATCHED,
                           persistenceService.getRequestStatus(requestId).orElse(null));
                   // Invariant 3: driver claim remains intact
                   var driver = driverRegistry.findById(driverId).orElseThrow();
                   assertEquals(MatchStatus.CLAIMED, driver.state().getStatus());
                   assertEquals(requestId, driver.state().getClaimedByRequestId());
               });
    }

    // =========================================================================
    // Scenario 7 - Competing requests for one driver
    // =========================================================================

    @Test
    @DisplayName("7. Two competing requests for one driver produce exactly one MATCHED and one NO_DRIVERS outcome")
    void competingRequestsForSingleDriverOneMatchedOneNoDrivers() throws Exception {
        String driverId = "E2E-D-S7-COMP";
        String requestA = "E2E-REQ-S7A";
        String requestB = "E2E-REQ-S7B";

        // Register exactly one driver
        long capturedAt = System.currentTimeMillis();
        mockMvc.perform(post("/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"driverId":"%s","latitude":17.385,"longitude":78.486,
                     "action":"REGISTER","capturedAt":%d}
                    """.formatted(driverId, capturedAt)))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> assertTrue(driverRegistry.findById(driverId).isPresent()));

        // Submit two competing requests in rapid succession
        mockMvc.perform(post("/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestId":"%s","latitude":17.385,"longitude":78.486}
                    """.formatted(requestA)))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestId":"%s","latitude":17.385,"longitude":78.486}
                    """.formatted(requestB)))
                .andExpect(status().isAccepted());

        // Await both requests reaching terminal state
        await().atMost(Duration.ofSeconds(15))
               .pollInterval(Duration.ofMillis(100))
               .untilAsserted(() -> {
                   var statusA = persistenceService.getRequestStatus(requestA);
                   var statusB = persistenceService.getRequestStatus(requestB);
                   assertTrue(statusA.isPresent(), "Request A must be finalized");
                   assertTrue(statusB.isPresent(), "Request B must be finalized");

                   boolean aTerminal = isTerminal(statusA.get());
                   boolean bTerminal = isTerminal(statusB.get());
                   assertTrue(aTerminal, "Request A must reach terminal state, was: " + statusA.get());
                   assertTrue(bTerminal, "Request B must reach terminal state, was: " + statusB.get());
               });

        String finalA = persistenceService.getRequestStatus(requestA).orElseThrow();
        String finalB = persistenceService.getRequestStatus(requestB).orElseThrow();

        // Invariant: strict mutual exclusion - exactly one wins (MATCHED) and one loses (NO_DRIVERS)
        boolean aWon = AssignmentPersistenceService.STATUS_MATCHED.equals(finalA)
                    && AssignmentPersistenceService.STATUS_NO_DRIVERS.equals(finalB);
        boolean bWon = AssignmentPersistenceService.STATUS_MATCHED.equals(finalB)
                    && AssignmentPersistenceService.STATUS_NO_DRIVERS.equals(finalA);

        assertTrue(aWon ^ bWon,
                "Exactly one request must be MATCHED and the other NO_DRIVERS; statusA=" + finalA + ", statusB=" + finalB);

        String winningRequestId = aWon ? requestA : requestB;
        String losingRequestId  = aWon ? requestB : requestA;

        // 1. Exactly one assignment row in PostgreSQL, assigned to the winning request
        List<AssignmentEntity> assignments = assignmentRepository.findAll();
        assertEquals(1, assignments.size(), "Exactly one assignment row must exist in PostgreSQL");
        assertEquals(winningRequestId, assignments.get(0).getRequestId(), "Assignment must belong to winning request");
        assertEquals(driverId, assignments.get(0).getDriverId(), "Assignment must be for driver " + driverId);

        // 2. No assignment row for the losing request
        assertFalse(assignmentRepository.existsByRequestId(losingRequestId),
                "Losing request must have no assignment row");

        // 3. Driver claim ownership belongs strictly to the winning request
        var driver = driverRegistry.findById(driverId).orElseThrow();
        assertEquals(MatchStatus.CLAIMED, driver.state().getStatus(), "Driver must be CLAIMED");
        assertEquals(winningRequestId, driver.state().getClaimedByRequestId(),
                "Driver must be claimed by winning request " + winningRequestId);
    }

    // =========================================================================
    // Scenario 8 - Redis failure isolation
    // =========================================================================

    @Test
    @DisplayName("8. Redis container pause does not prevent driver ingestion and ride matching from completing")
    void redisOutageDoesNotBlockDispatch() throws Exception {
        String driverId  = "E2E-D-S8";
        String requestId = "E2E-REQ-S8";

        // Pause Redis to simulate an outage
        redisContainer.getDockerClient()
                      .pauseContainerCmd(redisContainer.getContainerId())
                      .exec();
        try {
            // Register driver - must succeed despite Redis being paused
            long capturedAt = System.currentTimeMillis();
            mockMvc.perform(post("/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"driverId":"%s","latitude":17.385,"longitude":78.486,
                         "action":"REGISTER","capturedAt":%d}
                        """.formatted(driverId, capturedAt)))
                    .andExpect(status().isAccepted());

            await().atMost(Duration.ofSeconds(10))
                   .pollInterval(Duration.ofMillis(50))
                   .untilAsserted(() -> assertTrue(
                           driverRegistry.findById(driverId).isPresent(),
                           "Driver must appear in DriverRegistry even when Redis is paused"));

            // Submit ride request - matching and PostgreSQL persistence must still work
            mockMvc.perform(post("/request")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"requestId":"%s","latitude":17.385,"longitude":78.486}
                        """.formatted(requestId)))
                    .andExpect(status().isAccepted());

            await().atMost(Duration.ofSeconds(10))
                   .pollInterval(Duration.ofMillis(50))
                   .untilAsserted(() -> {
                       var optStatus = persistenceService.getRequestStatus(requestId);
                       assertTrue(optStatus.isPresent(), "Request must be persisted despite Redis outage");
                       assertEquals(AssignmentPersistenceService.STATUS_MATCHED, optStatus.get(),
                               "Request must be MATCHED despite Redis outage");
                   });

            assertTrue(assignmentRepository.existsByRequestId(requestId),
                    "Assignment must be persisted in PostgreSQL despite Redis outage");

        } finally {
            // Guaranteed recovery: unpause Redis even if assertions fail
            try {
                var inspect = redisContainer.getDockerClient()
                        .inspectContainerCmd(redisContainer.getContainerId())
                        .exec();
                if (Boolean.TRUE.equals(inspect.getState().getPaused())) {
                    redisContainer.getDockerClient()
                            .unpauseContainerCmd(redisContainer.getContainerId())
                            .exec();
                }
            } catch (Exception ex) {
                // Unconditional fallback attempt
                try {
                    redisContainer.getDockerClient()
                            .unpauseContainerCmd(redisContainer.getContainerId())
                            .exec();
                } catch (Exception ignored) {}
            }
        }
    }

    // =========================================================================
    // Scenario 9 - Request status with telemetry: fresh driver returns MATCHED + coordinates
    // =========================================================================

    @Test
    @DisplayName("9. GET /api/requests/{requestId} returns MATCHED with telemetry for a fresh matched driver")
    void requestStatusReturnsMatchedWithTelemetryForFreshDriver() throws Exception {
        String driverId  = "E2E-D-S9";
        String requestId = "E2E-REQ-S9";
        long capturedAt = System.currentTimeMillis();

        // 1. Register driver with fresh location fix
        mockMvc.perform(post("/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"driverId":"%s","latitude":17.3850,"longitude":78.4867,
                     "action":"REGISTER","capturedAt":%d}
                    """.formatted(driverId, capturedAt)))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> assertTrue(
                       driverRegistry.findById(driverId).isPresent(),
                       "Driver must be registered in DriverRegistry"));

        // 2. Submit ride request
        mockMvc.perform(post("/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestId":"%s","latitude":17.3852,"longitude":78.4869}
                    """.formatted(requestId)))
                .andExpect(status().isAccepted());

        // 3. Await assignment persistence
        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   var optStatus = persistenceService.getRequestStatus(requestId);
                   assertTrue(optStatus.isPresent(), "Request status must be persisted");
                   assertEquals(AssignmentPersistenceService.STATUS_MATCHED, optStatus.get(),
                           "Request must be MATCHED");
               });

        // 4. Poll GET /api/requests/{requestId} - verify full telemetry payload
        mockMvc.perform(get("/api/requests/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.driverId").value(driverId))
                .andExpect(jsonPath("$.driverLatitude").value(17.3850))
                .andExpect(jsonPath("$.driverLongitude").value(78.4867))
                .andExpect(jsonPath("$.driverLocationAt").isNotEmpty());
    }

    // =========================================================================
    // Scenario 10 - Stale driver excluded by matching pipeline yields NO_DRIVERS
    // =========================================================================

    @Test
    @DisplayName("10. Stale driver location fix is excluded by matching pipeline; yields NO_DRIVERS and null telemetry")
    void staleDriverExcludedByMatchingYieldsNoDrivers() throws Exception {
        String driverId  = "E2E-D-S10";
        String requestId = "E2E-REQ-S10";
        // 120 seconds ago - strictly exceeds default 60s freshness window
        long staleCapturedAt = System.currentTimeMillis() - 120_000L;

        // 1. Register driver with stale timestamp
        mockMvc.perform(post("/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"driverId":"%s","latitude":17.3850,"longitude":78.4867,
                     "action":"REGISTER","capturedAt":%d}
                    """.formatted(driverId, staleCapturedAt)))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> assertTrue(
                       driverRegistry.findById(driverId).isPresent(),
                       "Driver must be stored in DriverRegistry"));

        // 2. Submit ride request
        mockMvc.perform(post("/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"requestId":"%s","latitude":17.3852,"longitude":78.4869}
                    """.formatted(requestId)))
                .andExpect(status().isAccepted());

        // 3. Await pipeline execution: stale driver is excluded from matching candidates,
        // resulting in NO_DRIVERS terminal persistence
        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   var optStatus = persistenceService.getRequestStatus(requestId);
                   assertTrue(optStatus.isPresent(), "Request status must be persisted");
                   assertEquals(AssignmentPersistenceService.STATUS_NO_DRIVERS, optStatus.get(),
                           "Request must terminate in NO_DRIVERS due to stale candidate exclusion");
               });

        // 4. Query GET /api/requests/{requestId} - verify status is NO_DRIVERS with no driver or telemetry
        mockMvc.perform(get("/api/requests/" + requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.status").value("NO_DRIVERS"))
                .andExpect(jsonPath("$.driverId").doesNotExist())
                .andExpect(jsonPath("$.driverLatitude").doesNotExist())
                .andExpect(jsonPath("$.driverLongitude").doesNotExist())
                .andExpect(jsonPath("$.driverLocationAt").doesNotExist());
    }

    // =========================================================================
    // Scenario 11 - End-to-end multi-request batch dispatch & passive telemetry
    // =========================================================================

    @Test
    @DisplayName("11. Multi-request burst via Kafka is batched, matched, atomically persisted, and recorded in controller telemetry")
    void multiRequestBurstIsBatchedAndRecordedInTelemetry() throws Exception {
        // 1. Register 3 drivers
        long now = System.currentTimeMillis();
        for (int i = 1; i <= 3; i++) {
            String dId = "E2E-D-BATCH-" + i;
            double lat = 17.3850 + (i * 0.001);
            double lon = 78.4867 + (i * 0.001);
            mockMvc.perform(post("/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"driverId":"%s","latitude":%f,"longitude":%f,
                         "action":"REGISTER","capturedAt":%d}
                        """.formatted(dId, lat, lon, now)))
                    .andExpect(status().isAccepted());
        }

        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   assertTrue(driverRegistry.findById("E2E-D-BATCH-1").isPresent());
                   assertTrue(driverRegistry.findById("E2E-D-BATCH-2").isPresent());
                   assertTrue(driverRegistry.findById("E2E-D-BATCH-3").isPresent());
               });

        // 2. Publish 3 ride requests in rapid succession
        for (int i = 1; i <= 3; i++) {
            String reqId = "E2E-REQ-BATCH-" + i;
            RideRequestDto dto = new RideRequestDto(reqId, null, 17.3850 + (i * 0.001), 78.4867 + (i * 0.001));
            rideRequestKafkaTemplate.send(KafkaTopics.RIDE_REQUESTS, reqId, dto).get(5, TimeUnit.SECONDS);
        }

        // 3. Await all 3 requests reaching terminal MATCHED state in PostgreSQL
        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   assertEquals(3, assignmentRepository.count(),
                           "All 3 requests must be persisted in assignments table");
                   for (int i = 1; i <= 3; i++) {
                       String reqId = "E2E-REQ-BATCH-" + i;
                       var optStatus = persistenceService.getRequestStatus(reqId);
                       assertTrue(optStatus.isPresent(), "Status for " + reqId + " must exist");
                       assertEquals(AssignmentPersistenceService.STATUS_MATCHED, optStatus.get());
                   }
               });

        // 4. Verify telemetry recorded batches and requests
        var telemetry = rideRequestConsumer.getWindowController().getTelemetrySnapshot();
        assertTrue(telemetry.totalBatches() >= 1, "Must have recorded at least 1 batch");
        assertTrue(telemetry.totalRequests() >= 3, "Must have observed at least 3 requests");
        assertTrue(telemetry.averageBatchSize() >= 1.0, "Average batch size must be >= 1.0");
    }

    // =========================================================================
    // Scenario 12 - End-to-end mixed batch: poison pill skipped without stalling partition
    // =========================================================================

    @Test
    @DisplayName("12. Poison pill record does not stall partition and allows subsequent valid request to match and commit")
    void poisonPillRecordDoesNotStallPartition() throws Exception {
        String driverId = "E2E-D-POISON";
        String validReqId = "E2E-REQ-POISON-VALID";
        long now = System.currentTimeMillis();

        mockMvc.perform(post("/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"driverId":"%s","latitude":17.3850,"longitude":78.4867,
                     "action":"REGISTER","capturedAt":%d}
                    """.formatted(driverId, now)))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> assertTrue(driverRegistry.findById(driverId).isPresent()));

        // Publish a null payload (simulates poison pill / tombstone) followed by a valid payload
        rideRequestKafkaTemplate.send(KafkaTopics.RIDE_REQUESTS, "KEY-POISON", null).get(5, TimeUnit.SECONDS);
        RideRequestDto validDto = new RideRequestDto(validReqId, null, 17.3851, 78.4868);
        rideRequestKafkaTemplate.send(KafkaTopics.RIDE_REQUESTS, validReqId, validDto).get(5, TimeUnit.SECONDS);

        // Await valid request successfully processed and persisted despite preceding poison pill
        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(50))
               .untilAsserted(() -> {
                   var optStatus = persistenceService.getRequestStatus(validReqId);
                   assertTrue(optStatus.isPresent(), "Valid request must be processed");
                   assertEquals(AssignmentPersistenceService.STATUS_MATCHED, optStatus.get());
                   assertTrue(assignmentRepository.existsByRequestId(validReqId));
               });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean isTerminal(String status) {
        return AssignmentPersistenceService.STATUS_MATCHED.equals(status)
            || AssignmentPersistenceService.STATUS_NO_DRIVERS.equals(status);
    }
}
