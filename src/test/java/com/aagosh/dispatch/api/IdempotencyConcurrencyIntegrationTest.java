package com.aagosh.dispatch.api;

import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.persistence.RideRequestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1: High-Contention Concurrency & Race-Safe Idempotency Integration Test.
 *
 * <p>Proves under high contention (50 threads / 500 requests / 10 keys) that:
 * <ul>
 *   <li>PostgreSQL UNIQUE constraint + isolated transaction guarantees exactly 1 DB row per key (0% duplicates).</li>
 *   <li>Only the winning thread publishes to Kafka (0 duplicate events).</li>
 *   <li>Zero HTTP 500 errors or poisoned Hibernate sessions occur.</li>
 *   <li>100% deterministic HTTP responses return identical rideId per key.</li>
 * </ul>
 */
@SuppressWarnings({"resource", "rawtypes"})
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class IdempotencyConcurrencyIntegrationTest {

    private static final int    KEYS_COUNT       = 10;
    private static final int    THREADS_PER_KEY  = 50;
    private static final int    TOTAL_REQUESTS   = KEYS_COUNT * THREADS_PER_KEY; // 500
    private static final double PICKUP_LAT       = 17.3850;
    private static final double PICKUP_LON       = 78.4867;

    @Container
    static KafkaContainer kafkaContainer =
            new KafkaContainer("apache/kafka:3.7.0");

    @Container
    static PostgreSQLContainer<?> postgresContainer =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("dispatch_idempotency_test")
                    .withUsername("test_user")
                    .withPassword("test_pass")
                    .withEnv("TZ", "UTC");

    @Container
    static GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
        // Prevent background consumer from draining the topic during verification
        registry.add("spring.kafka.listener.auto-startup", () -> "false");

        registry.add("spring.datasource.url", () -> {
            String url = postgresContainer.getJdbcUrl();
            return url.contains("options=") ? url
                    : (url.contains("?") ? url + "&options=-c%20timezone%3DUTC"
                                         : url + "?options=-c%20timezone%3DUTC");
        });
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "50");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "30000");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired RideController        rideController;
    @Autowired RideRequestRepository rideRequestRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(rideController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        rideRequestRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        rideRequestRepository.deleteAll();
    }

    // =========================================================================
    // 1. High-Contention Concurrent Retry Storm (500 Requests / 10 Keys)
    // =========================================================================

    @Test
    @DisplayName("500 concurrent requests across 10 keys: exactly 10 DB rows, 10 Kafka events, 0 HTTP 500s, identical rideId per key")
    void highContentionConcurrentRetryStorm() throws Exception {
        List<String> keys = new ArrayList<>(KEYS_COUNT);
        for (int i = 0; i < KEYS_COUNT; i++) {
            keys.add("IDEM-STORM-" + UUID.randomUUID());
        }

        AtomicInteger http2xxCount = new AtomicInteger(0);
        AtomicInteger http5xxCount = new AtomicInteger(0);
        Map<String, ConcurrentHashMap<String, Boolean>> rideIdsByKey = new ConcurrentHashMap<>();
        for (String key : keys) {
            rideIdsByKey.put(key, new ConcurrentHashMap<>());
        }

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);

        Map<String, String> payloadByKey = new ConcurrentHashMap<>();
        for (int i = 0; i < KEYS_COUNT; i++) {
            String key = keys.get(i);
            payloadByKey.put(key, String.format(
                    "{\"requestId\":\"REQ-STORM-%d\",\"latitude\":%s,\"longitude\":%s}",
                    i, PICKUP_LAT, PICKUP_LON));
        }

        // Count pre-existing Kafka events (if prior test methods published)
        long initialKafkaEvents = drainAndCountKafkaTopic(kafkaContainer.getBootstrapServers(), KafkaTopics.RIDE_REQUESTS, 1_000);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // Simultaneous unblock of all 50 threads
                    for (int k = 0; k < KEYS_COUNT; k++) {
                        String key = keys.get(k);
                        String payload = payloadByKey.get(key);
                        MvcResult result = mockMvc.perform(post("/request")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("Idempotency-Key", key)
                                        .content(payload))
                                .andReturn();

                        int statusCode = result.getResponse().getStatus();
                        if (statusCode >= 200 && statusCode < 300) {
                            http2xxCount.incrementAndGet();
                            JsonNode tree = objectMapper.readTree(result.getResponse().getContentAsString());
                            String rideId = tree.has("rideId") ? tree.get("rideId").asText() : tree.get("requestId").asText();
                            rideIdsByKey.get(key).put(rideId, Boolean.TRUE);
                        } else if (statusCode >= 500) {
                            http5xxCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    http5xxCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertTrue(readyLatch.await(30, TimeUnit.SECONDS), "Threads failed to prepare");
        startLatch.countDown();
        assertTrue(doneLatch.await(90, TimeUnit.SECONDS), "Concurrent requests did not complete in time");
        executor.shutdown();

        // ---- ASSERTION 1: Exactly 10 rows in ride_requests table (0% duplicates) ----
        long dbRowCount = rideRequestRepository.count();
        assertEquals(KEYS_COUNT, dbRowCount,
                "Expected exactly " + KEYS_COUNT + " rows in ride_requests, but found " + dbRowCount);

        // ---- ASSERTION 2: Exactly 10 events published to Kafka ride-requests topic ----
        long totalKafkaEvents = drainAndCountKafkaTopic(kafkaContainer.getBootstrapServers(), KafkaTopics.RIDE_REQUESTS, 5_000);
        long publishedKafkaEvents = totalKafkaEvents - initialKafkaEvents;
        assertEquals(KEYS_COUNT, publishedKafkaEvents,
                "Expected exactly " + KEYS_COUNT + " Kafka events on topic " + KafkaTopics.RIDE_REQUESTS + ", but found " + publishedKafkaEvents);

        // ---- ASSERTION 3: All 500 responses are HTTP 2xx (0 HTTP 500 errors) ----
        assertEquals(0, http5xxCount.get(), "Detected " + http5xxCount.get() + " HTTP 500 errors under concurrent contention");
        assertEquals(TOTAL_REQUESTS, http2xxCount.get(), "Expected all " + TOTAL_REQUESTS + " requests to succeed with 2xx");

        // ---- ASSERTION 4: All 50 concurrent requests for a specific key received identical rideId ----
        for (String key : keys) {
            ConcurrentHashMap<String, Boolean> distinctRideIds = rideIdsByKey.get(key);
            assertEquals(1, distinctRideIds.size(),
                    "Key " + key + " received multiple distinct rideIds: " + distinctRideIds.keySet());
        }
    }

    // =========================================================================
    // 2. Sequential Retry Yields Identical rideId
    // =========================================================================

    @Test
    @DisplayName("Sequential retry with same Idempotency-Key returns identical rideId")
    void sequentialRetryYieldsIdenticalRideId() throws Exception {
        String key = "SEQ-TEST-" + UUID.randomUUID();
        String jsonPayload = String.format("{\"requestId\":\"REQ-SEQ-001\",\"latitude\":%s,\"longitude\":%s}", PICKUP_LAT, PICKUP_LON);

        MvcResult first = mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(jsonPayload))
                .andExpect(status().isAccepted())
                .andReturn();
        String firstRideId = objectMapper.readTree(first.getResponse().getContentAsString()).get("rideId").asText();

        MvcResult second = mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(jsonPayload))
                .andExpect(status().isAccepted())
                .andReturn();
        String secondRideId = objectMapper.readTree(second.getResponse().getContentAsString()).get("rideId").asText();

        MvcResult third = mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(jsonPayload))
                .andExpect(status().isAccepted())
                .andReturn();
        String thirdRideId = objectMapper.readTree(third.getResponse().getContentAsString()).get("rideId").asText();

        assertEquals(firstRideId, secondRideId);
        assertEquals(firstRideId, thirdRideId);
        assertEquals(1L, rideRequestRepository.count());
    }

    // =========================================================================
    // 3. Validation: Blank and Oversized Keys Return HTTP 400
    // =========================================================================

    @Test
    @DisplayName("Blank Idempotency-Key header returns HTTP 400 Bad Request")
    void blankKeyReturnsBadRequest() throws Exception {
        String jsonPayload = String.format("{\"requestId\":\"REQ-VAL-001\",\"latitude\":%s,\"longitude\":%s}", PICKUP_LAT, PICKUP_LON);

        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "   ")
                        .content(jsonPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Idempotency-Key header must not be blank"));
    }

    @Test
    @DisplayName("Oversized Idempotency-Key header (>128 chars) returns HTTP 400 Bad Request")
    void oversizedKeyReturnsBadRequest() throws Exception {
        String jsonPayload = String.format("{\"requestId\":\"REQ-VAL-002\",\"latitude\":%s,\"longitude\":%s}", PICKUP_LAT, PICKUP_LON);
        String oversizedKey = "X".repeat(129);

        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", oversizedKey)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Idempotency-Key header must not exceed 128 characters"));
    }

    // =========================================================================
    // Kafka Helper
    // =========================================================================

    private long drainAndCountKafkaTopic(String bootstrapServers, String topic, long maxWaitMs) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "verifier-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        long count = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));
            long deadline = System.currentTimeMillis() + maxWaitMs;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (!records.isEmpty()) {
                    count += records.count();
                    deadline = System.currentTimeMillis() + 2000;
                } else if (count > 0) {
                    break;
                }
            }
        }
        return count;
    }
}
