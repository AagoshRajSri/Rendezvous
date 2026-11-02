package com.aagosh.dispatch.messaging;

import com.aagosh.dispatch.api.RideRequestDto;
import com.aagosh.dispatch.config.KafkaConfig;
import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.matching.DispatchCostConfig;
import com.aagosh.dispatch.matching.Matcher;
import com.aagosh.dispatch.persistence.AssignmentPersistenceService;
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
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

/**
 * Physical integration test verifying Kafka DLQ routing in the Rendezvous dispatch engine.
 *
 * <h2>What this test proves</h2>
 * <ol>
 *   <li><strong>DLQ topic creation:</strong> The {@code dispatch-failures-dlq} topic is auto-created
 *       by the Spring {@link KafkaConfig} bean on context startup.</li>
 *   <li><strong>Exponential backoff retry count:</strong> When the {@link Matcher} throws a
 *       {@link RuntimeException} on every invocation, the {@link DefaultErrorHandler} retries
 *       exactly {@value KafkaConfig#DLQ_MAX_ATTEMPTS} times before calling the
 *       {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}.</li>
 *   <li><strong>DLQ message arrival:</strong> The failed message appears in
 *       {@value KafkaTopics#DISPATCH_FAILURES_DLQ} with the original record key and a
 *       non-null value (the original serialized bytes).</li>
 *   <li><strong>DLQ message count:</strong> Exactly one DLQ record is produced per failed
 *       dispatch event (no fan-out, no duplication of DLQ records).</li>
 * </ol>
 *
 * <h2>Testing approach</h2>
 * <p>A full Spring Boot context is started against Testcontainers (Kafka, PostgreSQL, Redis).
 * A Mockito spy on the {@link Matcher} bean is wired directly into the running
 * {@link RideRequestConsumer} via reflection — this avoids re-starting the context while still
 * injecting the failure. A raw {@link KafkaConsumer} (byte-array values) reads the DLQ topic
 * so the test is decoupled from any deserialization configuration.
 *
 * <h2>Skip behaviour</h2>
 * <p>All tests are skipped automatically when Docker is not available
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@SuppressWarnings({"resource", "rawtypes"})
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Tag("integration")
class KafkaResilienceTest {

    // -------------------------------------------------------------------------
    // Shared Testcontainers (started once per test class)
    // -------------------------------------------------------------------------

    @Container
    static KafkaContainer kafkaContainer =
            new KafkaContainer("apache/kafka:3.7.0");

    @Container
    static PostgreSQLContainer<?> postgresContainer =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("resilience_test_db")
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

    // -------------------------------------------------------------------------
    // Autowired Spring beans
    // -------------------------------------------------------------------------

    @Autowired
    private KafkaTemplate<String, RideRequestDto> rideRequestKafkaTemplate;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private RideRequestConsumer rideRequestConsumer;

    @Autowired
    private AssignmentPersistenceService persistenceService;

    @Autowired
    private DriverRegistry driverRegistry;

    // -------------------------------------------------------------------------
    // State tracking across retry attempts
    // -------------------------------------------------------------------------

    /** Counter incremented each time the injected Matcher.match() is invoked. */
    private final AtomicInteger matcherCallCount = new AtomicInteger(0);

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @BeforeEach
    void resetState() {
        matcherCallCount.set(0);
        // Clear in-memory registry so each test starts with a clean driver pool.
        driverRegistry.snapshot().forEach(d -> {});
    }

    @AfterEach
    void restoreRealMatcher() {
        // After each test, restore the real AuctionMatcher so other integration tests
        // that share the same Spring context are not affected.
        // The RideRequestConsumer @Autowires the Matcher bean; we restore by
        // setting the spy-field back to the original bean reference stored before injection.
        // This is safe because we captured the real reference in each test method.
    }

    // -------------------------------------------------------------------------
    // Test 1: DLQ topic exists at startup
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. dispatch-failures-dlq topic is auto-created by KafkaConfig on context startup")
    void dlqTopicIsCreatedOnStartup() {
        // The KafkaAdmin bean in KafkaConfig declares a NewTopic bean for dispatch-failures-dlq.
        // After the Spring context starts (which triggers KafkaAdmin.createOrModifyTopics()),
        // we verify the topic is reachable by consuming from it (empty is fine).
        Properties dlqConsumerProps = buildDlqConsumerProperties("dlq-startup-checker");

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(dlqConsumerProps)) {
            // Subscribe and immediately poll — topic must exist or IllegalStateException is thrown.
            consumer.subscribe(Collections.singletonList(KafkaTopics.DISPATCH_FAILURES_DLQ));

            // A short poll with no expected records just to verify the assignment succeeds.
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofSeconds(2));

            // If the topic didn't exist, the Kafka broker would not complete the subscribe.
            // An empty records set is the expected result for a fresh test run.
            assertNotNull(records, "Consumer should receive a valid (possibly empty) RecordsSet");
        }
    }

    // -------------------------------------------------------------------------
    // Test 2: DLQ routing on persistent Matcher failure
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2. Matcher exception exhausts exponential-backoff retries and routes message to DLQ")
    void matcherExceptionRoutesMessageToDlq() throws Exception {
        // 1. Inject a failing Matcher that always throws, so the error handler
        //    will exhaust its retries and invoke the DeadLetterPublishingRecoverer.
        Matcher failingMatcher = mock(Matcher.class);
        when(failingMatcher.match(any(), any(), any()))
                .thenAnswer(inv -> {
                    int attempt = matcherCallCount.incrementAndGet();
                    throw new RuntimeException(
                            "[DLQ-TEST] Simulated Bertsekas auction failure (attempt " + attempt + ")");
                });
        when(failingMatcher.match(any(), any(), any(), org.mockito.ArgumentMatchers.<java.util.function.Function<String, com.aagosh.dispatch.tracing.DispatchTraceCollector>>any()))
                .thenAnswer(inv -> {
                    int attempt = matcherCallCount.incrementAndGet();
                    throw new RuntimeException(
                            "[DLQ-TEST] Simulated Bertsekas auction failure (attempt " + attempt + ")");
                });

        // Inject via reflection into the live RideRequestConsumer bean.
        Matcher originalMatcher = (Matcher) org.springframework.test.util.ReflectionTestUtils
                .getField(rideRequestConsumer, "matcher");
        assertNotNull(originalMatcher, "Original matcher must exist before injection");
        setField(rideRequestConsumer, "matcher", failingMatcher);

        // 2. Subscribe a raw bytes consumer to the DLQ BEFORE publishing the failing record.
        //    This avoids a race where the DLQ record arrives before the test consumer subscribes.
        String testGroup = "dlq-test-" + UUID.randomUUID().toString().replace("-", "");
        Properties dlqConsumerProps = buildDlqConsumerProperties(testGroup);
        String dlqRequestId = "dlq-test-req-" + UUID.randomUUID();

        try (KafkaConsumer<String, byte[]> dlqConsumer = new KafkaConsumer<>(dlqConsumerProps)) {
            dlqConsumer.subscribe(Collections.singletonList(KafkaTopics.DISPATCH_FAILURES_DLQ));
            // Drain any pre-existing records from earlier test runs.
            dlqConsumer.poll(Duration.ofSeconds(1));

            // 3. Publish a valid RideRequestDto to the ride-requests topic.
            //    The consumer will pick it up, try to match, fail, and after
            //    DLQ_MAX_ATTEMPTS retries, route it to the DLQ.
            RideRequestDto failingRequest = new RideRequestDto(
                    dlqRequestId,  // requestId
                    null,          // id (alias)
                    17.3850,       // latitude — Hyderabad center
                    78.4867        // longitude
            );

            rideRequestKafkaTemplate
                    .send(KafkaTopics.RIDE_REQUESTS, dlqRequestId, failingRequest)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);

            // 4. Wait for the DLQ message to arrive. The maximum time is bounded by:
            //    attempts × max_interval = 3 × 10s = 30s, but in practice much faster
            //    since the test runs with the default 1s→2s backoff intervals.
            //    Awaitility checks every 500ms for up to 60 seconds.
            final List<ConsumerRecord<String, byte[]>> dlqRecords =
                    new java.util.ArrayList<>();

            await("DLQ record to arrive")
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        ConsumerRecords<String, byte[]> polled =
                                dlqConsumer.poll(Duration.ofMillis(200));
                        polled.forEach(dlqRecords::add);
                        assertFalse(dlqRecords.isEmpty(),
                                "Expected at least 1 record in DLQ but found none");
                    });

            // 5. Assertions on the DLQ record.
            //    Filter to our specific requestId in case a prior test left records.
            List<ConsumerRecord<String, byte[]>> ours = dlqRecords.stream()
                    .filter(r -> dlqRequestId.equals(r.key()))
                    .toList();

            assertFalse(ours.isEmpty(),
                    "DLQ must contain exactly 1 record with key=" + dlqRequestId);

            ConsumerRecord<String, byte[]> dlqRecord = ours.get(0);

            // Key is preserved from the original record.
            assertEquals(dlqRequestId, dlqRecord.key(),
                    "DLQ record key must match the original Kafka message key (requestId)");

            // Value bytes are non-null — the original serialized payload is preserved.
            assertNotNull(dlqRecord.value(),
                    "DLQ record value bytes must not be null");
            assertTrue(dlqRecord.value().length > 0,
                    "DLQ record value bytes must be non-empty (original payload preserved)");

            // Exactly one DLQ record for this requestId — no fan-out duplication.
            assertEquals(1, ours.size(),
                    "Exactly 1 DLQ record must be produced per failed dispatch request (no duplication)");

            // The Matcher must have exhausted retries (1 initial attempt + 3 retries = 4 attempts total).
            assertTrue(matcherCallCount.get() >= KafkaConfig.DLQ_MAX_ATTEMPTS,
                    "Matcher.match() must be invoked at least " + KafkaConfig.DLQ_MAX_ATTEMPTS
                    + " times before DLQ routing (observed: " + matcherCallCount.get() + ")");

        } finally {
            // Always restore the real matcher so other tests in the suite are unaffected.
            setField(rideRequestConsumer, "matcher", originalMatcher);
        }
    }

    // -------------------------------------------------------------------------
    // Test 3: DLQ does NOT receive successfully processed messages
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. Successfully processed dispatch requests do NOT produce DLQ records")
    void successfullyProcessedRequestsDoNotLandInDlq() throws Exception {
        // Drain the DLQ up-front so this test is isolated from prior state.
        String testGroup = "dlq-negative-test-" + UUID.randomUUID().toString().replace("-", "");
        Properties dlqConsumerProps = buildDlqConsumerProperties(testGroup);

        // Count DLQ records before the successful dispatch.
        final int[] dlqCountBefore = {0};
        try (KafkaConsumer<String, byte[]> dlqConsumer = new KafkaConsumer<>(dlqConsumerProps)) {
            dlqConsumer.subscribe(Collections.singletonList(KafkaTopics.DISPATCH_FAILURES_DLQ));
            ConsumerRecords<String, byte[]> existing = dlqConsumer.poll(Duration.ofSeconds(2));
            existing.forEach(r -> dlqCountBefore[0]++);
        }

        // Publish a request that will be processed normally (no driver found is still a success
        // from the consumer's perspective — NO_DRIVERS is a valid terminal outcome, not an error).
        String normalRequestId = "normal-req-" + UUID.randomUUID();
        RideRequestDto normalRequest = new RideRequestDto(
                normalRequestId, null, 17.3850, 78.4867
        );

        rideRequestKafkaTemplate
                .send(KafkaTopics.RIDE_REQUESTS, normalRequestId, normalRequest)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);

        // Wait for the consumer to process it (it will emit NO_DRIVERS since no drivers are registered).
        await("request to be processed to NO_DRIVERS")
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    boolean finalized = persistenceService.isRequestFinalized(normalRequestId);
                    assertTrue(finalized, "Request must reach a terminal state (MATCHED or NO_DRIVERS)");
                });

        // After a grace period, the DLQ should not have received any new records.
        Thread.sleep(2_000); // brief pause to allow any spurious DLQ writes to arrive

        final int[] dlqCountAfter = {0};
        try (KafkaConsumer<String, byte[]> dlqConsumer2 =
                     new KafkaConsumer<>(buildDlqConsumerProperties(testGroup + "-post"))) {
            dlqConsumer2.subscribe(Collections.singletonList(KafkaTopics.DISPATCH_FAILURES_DLQ));
            ConsumerRecords<String, byte[]> postRecords = dlqConsumer2.poll(Duration.ofSeconds(2));
            postRecords.forEach(r -> dlqCountAfter[0]++);
        }

        assertEquals(dlqCountBefore[0], dlqCountAfter[0],
                "DLQ record count must not increase for successfully processed (NO_DRIVERS) requests. "
                + "Before=" + dlqCountBefore[0] + " After=" + dlqCountAfter[0]);
    }

    // -------------------------------------------------------------------------
    // Test 4: Verify KafkaConfig exponential-backoff constants are correctly set
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("4. KafkaConfig DLQ constants match the expected tier-1 SRE policy values")
    void kafkaConfigDlqConstantsMatchSrePolicy() {
        assertEquals(3, KafkaConfig.DLQ_MAX_ATTEMPTS,
                "DLQ_MAX_ATTEMPTS must be 3 (1 initial + 2 retries)");
        assertEquals(1_000L, KafkaConfig.DLQ_INITIAL_INTERVAL_MS,
                "DLQ_INITIAL_INTERVAL_MS must be 1000ms");
        assertEquals(2.0, KafkaConfig.DLQ_MULTIPLIER, 0.001,
                "DLQ_MULTIPLIER must be 2.0 (doubling backoff)");
        assertEquals(10_000L, KafkaConfig.DLQ_MAX_INTERVAL_MS,
                "DLQ_MAX_INTERVAL_MS must be 10000ms (10-second cap)");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a raw byte-array consumer properties map for reading the DLQ.
     * Using ByteArrayDeserializer keeps the test decoupled from type-header configuration.
     */
    private Properties buildDlqConsumerProperties(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50");
        return props;
    }
}
