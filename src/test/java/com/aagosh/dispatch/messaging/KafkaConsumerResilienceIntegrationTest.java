package com.aagosh.dispatch.messaging;

import com.aagosh.dispatch.api.RideRequestDto;
import com.aagosh.dispatch.config.KafkaConfig;
import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.matching.Matcher;
import com.aagosh.dispatch.persistence.AssignmentPersistenceService;
import com.aagosh.dispatch.persistence.AssignmentRepository;
import com.aagosh.dispatch.persistence.RideRequestEntity;
import com.aagosh.dispatch.persistence.RideRequestRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2: Kafka Consumer Resilience, Redelivery Idempotency & DLT Routing Integration Test.
 *
 * <p>Proves with live Testcontainers (Kafka, PostgreSQL, Redis) that:
 * <ul>
 *   <li><strong>Redelivery Resilience:</strong> When Kafka delivers the same event multiple times,
 *       the consumer verifies durable PostgreSQL status, safely acknowledges the offset, and creates
 *       exactly 1 assignment (zero duplicate dispatch rows).</li>
 *   <li><strong>Poison Pill to DLT:</strong> When an unrecoverable failure occurs, Spring Kafka's
 *       DefaultErrorHandler executes exponential backoff retries (3 attempts), routes the record to
 *       {@code ride-requests.DLT}, and DeadLetterQueueConsumer updates DB status to {@code FAILED_DISPATCH}.</li>
 *   <li><strong>In-Batch Deduplication:</strong> When identical request IDs appear in the same Kafka
 *       poll batch, the duplicate is dropped before the auction matcher is invoked.</li>
 * </ul>
 */
@SuppressWarnings({"resource", "rawtypes"})
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Tag("integration")
class KafkaConsumerResilienceIntegrationTest {

    @Container
    static KafkaContainer kafkaContainer =
            new KafkaContainer("apache/kafka:3.7.0");

    @Container
    static PostgreSQLContainer<?> postgresContainer =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("consumer_resilience_test_db")
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
        registry.add("spring.kafka.listener.auto-startup", () -> "true");

        registry.add("spring.datasource.url", () -> {
            String url = postgresContainer.getJdbcUrl();
            return url.contains("options=") ? url
                    : (url.contains("?") ? url + "&options=-c%20timezone%3DUTC"
                                         : url + "?options=-c%20timezone%3DUTC");
        });
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private KafkaTemplate<String, RideRequestDto> rideRequestKafkaTemplate;

    @Autowired
    private RideRequestConsumer rideRequestConsumer;

    @Autowired
    private AssignmentPersistenceService persistenceService;

    @Autowired
    private RideRequestRepository rideRequestRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private DriverRegistry driverRegistry;

    private Matcher originalMatcher;

    @BeforeEach
    void setUp() {
        originalMatcher = (Matcher) ReflectionTestUtils.getField(rideRequestConsumer, "matcher");
        assignmentRepository.deleteAll();
        rideRequestRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        if (originalMatcher != null) {
            ReflectionTestUtils.setField(rideRequestConsumer, "matcher", originalMatcher);
        }
        assignmentRepository.deleteAll();
        rideRequestRepository.deleteAll();
    }

    // =========================================================================
    // Test 1: Kafka Redelivery Resilience (At-Least-Once Processing)
    // =========================================================================

    @Test
    @DisplayName("1. Kafka message redelivery is safely deduplicated via PostgreSQL state: exactly 1 assignment created")
    void kafkaRedeliveryResilienceAtLeastOnceProcessing() throws Exception {
        String requestId = "REQ-REDELIVERY-" + UUID.randomUUID();
        double lat = 17.3850;
        double lon = 78.4867;

        // 1. Register an available driver in the registry so matching succeeds
        String driverId = "DRV-REDELIVERY-" + UUID.randomUUID().toString().substring(0, 8);
        Driver driver = new Driver(driverId, new GeoPoint(lat, lon), new DriverState(), System.currentTimeMillis());
        driverRegistry.register(driver);

        // 2. Pre-create PENDING request entity in DB as the REST ingestion boundary does
        RideRequestEntity pendingEntity = new RideRequestEntity(requestId, lat, lon, "PENDING", "IDEM-" + UUID.randomUUID());
        rideRequestRepository.save(pendingEntity);

        // 3. Publish first Kafka ride-request event
        RideRequestDto event = new RideRequestDto(requestId, null, lat, lon);
        rideRequestKafkaTemplate.send(KafkaTopics.RIDE_REQUESTS, requestId, event).get(5, TimeUnit.SECONDS);

        // 4. Await initial successful dispatch: assignment persisted and status updated to MATCHED
        await("Wait for initial dispatch and assignment persistence")
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    assertTrue(persistenceService.isRequestFinalized(requestId), "Request must be finalized");
                    assertEquals(1, assignmentRepository.count(), "Exactly 1 assignment row expected initially");
                    Optional<RideRequestEntity> found = rideRequestRepository.findByRequestId(requestId);
                    assertTrue(found.isPresent());
                    assertEquals("MATCHED", found.get().getStatus(), "Request status must be MATCHED");
                });

        long initialAssignmentsCount = assignmentRepository.count();
        assertEquals(1, initialAssignmentsCount);

        // 5. Produce the EXACT SAME event to Kafka again (simulating Kafka at-least-once redelivery)
        rideRequestKafkaTemplate.send(KafkaTopics.RIDE_REQUESTS, requestId, event).get(5, TimeUnit.SECONDS);

        // 6. Give the consumer time to poll and process the redelivered message
        Thread.sleep(3000);

        // 7. ASSERT: Assignments count must remain exactly 1 (no duplicate assignment rows created)
        assertEquals(1, assignmentRepository.count(),
                "Assignments count must remain exactly 1 after duplicate Kafka redelivery");
        assertEquals(1, rideRequestRepository.count(),
                "RideRequest count must remain exactly 1");

        Optional<RideRequestEntity> entityAfterRedelivery = rideRequestRepository.findByRequestId(requestId);
        assertTrue(entityAfterRedelivery.isPresent());
        assertEquals("MATCHED", entityAfterRedelivery.get().getStatus(),
                "RideRequest status must remain MATCHED without state corruption");
    }

    // =========================================================================
    // Test 2: Poison Pill Routing to DLT & DB Status Update
    // =========================================================================

    @Test
    @DisplayName("2. Unrecoverable failure retries 3 times, routes to ride-requests.DLT, and updates status to FAILED_DISPATCH")
    void poisonPillRoutesToDltAndUpdatesStatusToFailedDispatch() throws Exception {
        String poisonRequestId = "REQ-POISON-" + UUID.randomUUID();
        double lat = 17.3850;
        double lon = 78.4867;

        // 1. Pre-create PENDING request entity in PostgreSQL
        RideRequestEntity poisonEntity = new RideRequestEntity(poisonRequestId, lat, lon, "PENDING", "IDEM-" + UUID.randomUUID());
        rideRequestRepository.save(poisonEntity);

        // 2. Set up raw bytes consumer to inspect ride-requests.DLT topic
        String testGroup = "dlt-verifier-" + UUID.randomUUID().toString().replace("-", "");
        Properties dltConsumerProps = buildDltConsumerProperties(testGroup);

        try (KafkaConsumer<String, byte[]> dltConsumer = new KafkaConsumer<>(dltConsumerProps)) {
            dltConsumer.subscribe(Collections.singletonList(KafkaTopics.RIDE_REQUESTS_DLT));
            // Drain initial
            dltConsumer.poll(Duration.ofSeconds(1));

            // 3. Inject failing matcher that throws RuntimeException on every attempt
            AtomicInteger matcherAttempts = new AtomicInteger(0);
            Matcher failingMatcher = mock(Matcher.class);
            when(failingMatcher.match(any(), any(), any())).thenAnswer(inv -> {
                int attempt = matcherAttempts.incrementAndGet();
                throw new RuntimeException("[CHAOS] Injected failure for poison pill test (attempt " + attempt + ")");
            });
            when(failingMatcher.match(any(), any(), any(), org.mockito.ArgumentMatchers.<java.util.function.Function<String, com.aagosh.dispatch.tracing.DispatchTraceCollector>>any()))
                    .thenAnswer(inv -> {
                        int attempt = matcherAttempts.incrementAndGet();
                        throw new RuntimeException("[CHAOS] Injected failure for poison pill test (attempt " + attempt + ")");
                    });

            ReflectionTestUtils.setField(rideRequestConsumer, "matcher", failingMatcher);

            // 4. Publish the poison pill event
            RideRequestDto poisonDto = new RideRequestDto(poisonRequestId, null, lat, lon);
            rideRequestKafkaTemplate.send(KafkaTopics.RIDE_REQUESTS, poisonRequestId, poisonDto).get(5, TimeUnit.SECONDS);

            // 5. Await message arrival on ride-requests.DLT topic
            List<ConsumerRecord<String, byte[]>> dltRecords = new ArrayList<>();
            await("Wait for dead-letter record to arrive on ride-requests.DLT")
                    .atMost(Duration.ofSeconds(45))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        ConsumerRecords<String, byte[]> polled = dltConsumer.poll(Duration.ofMillis(300));
                        polled.forEach(r -> {
                            if (poisonRequestId.equals(r.key())) {
                                dltRecords.add(r);
                            }
                        });
                        assertFalse(dltRecords.isEmpty(),
                                "Expected at least 1 record in ride-requests.DLT for key=" + poisonRequestId);
                    });

            // 6. Verify DLT record attributes
            assertEquals(1, dltRecords.size(), "Expected exactly 1 DLT record for the failed request");
            ConsumerRecord<String, byte[]> dltRecord = dltRecords.get(0);
            assertEquals(poisonRequestId, dltRecord.key(), "DLT record key must match the original requestId");
            assertNotNull(dltRecord.value(), "DLT record payload must not be null");
            assertTrue(dltRecord.value().length > 0, "DLT record payload must contain original serialized bytes");

            // 7. Verify Spring Kafka DefaultErrorHandler retried at least 3 times
            assertTrue(matcherAttempts.get() >= KafkaConfig.DLQ_MAX_ATTEMPTS,
                    "Matcher must be invoked at least " + KafkaConfig.DLQ_MAX_ATTEMPTS + " times before DLT routing (observed: " + matcherAttempts.get() + ")");

            // 8. Verify DeadLetterQueueConsumer marked the DB status as FAILED_DISPATCH
            await("Wait for DeadLetterQueueConsumer to update PostgreSQL status to FAILED_DISPATCH")
                    .atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofMillis(300))
                    .untilAsserted(() -> {
                        Optional<RideRequestEntity> updatedEntity = rideRequestRepository.findByRequestId(poisonRequestId);
                        assertTrue(updatedEntity.isPresent(), "Entity must exist in database");
                        assertEquals("FAILED_DISPATCH", updatedEntity.get().getStatus(),
                                "Database status must be updated to FAILED_DISPATCH upon DLT arrival");
                    });
        }
    }

    // =========================================================================
    // Test 3: In-Batch Duplicate Request Deduplication
    // =========================================================================

    @Test
    @DisplayName("3. Duplicate request IDs within the same Kafka batch are filtered out before auction solver")
    void inBatchDuplicateRequestsDeduplicatedBeforeMatching() {
        String batchRequestId = "REQ-BATCH-DUP-" + UUID.randomUUID();
        double lat = 17.3850;
        double lon = 78.4867;

        // 1. Register an available driver
        String driverId = "DRV-BATCH-" + UUID.randomUUID().toString().substring(0, 8);
        Driver driver = new Driver(driverId, new GeoPoint(lat, lon), new DriverState(), System.currentTimeMillis());
        driverRegistry.register(driver);

        // 2. Pre-create PENDING request entity in DB
        RideRequestEntity pendingEntity = new RideRequestEntity(batchRequestId, lat, lon, "PENDING", "IDEM-" + UUID.randomUUID());
        rideRequestRepository.save(pendingEntity);

        // 3. Construct a single batch containing TWO identical records
        RideRequestDto dto = new RideRequestDto(batchRequestId, null, lat, lon);
        ConsumerRecord<String, RideRequestDto> record1 =
                new ConsumerRecord<>(KafkaTopics.RIDE_REQUESTS, 0, 100L, batchRequestId, dto);
        ConsumerRecord<String, RideRequestDto> record2 =
                new ConsumerRecord<>(KafkaTopics.RIDE_REQUESTS, 0, 101L, batchRequestId, dto);

        long initialAssignments = assignmentRepository.count();

        // 4. Pass the batch directly to onRideRequests
        rideRequestConsumer.onRideRequests(List.of(record1, record2), () -> {});

        // 5. ASSERT: Exactly 1 assignment created, not 2
        assertEquals(initialAssignments + 1, assignmentRepository.count(),
                "Exactly 1 assignment must be created despite duplicate records in same poll batch");
        Optional<RideRequestEntity> entity = rideRequestRepository.findByRequestId(batchRequestId);
        assertTrue(entity.isPresent());
        assertEquals("MATCHED", entity.get().getStatus());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Properties buildDltConsumerProperties(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        return props;
    }
}

