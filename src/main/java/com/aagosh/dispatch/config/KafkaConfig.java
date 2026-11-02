package com.aagosh.dispatch.config;

import com.aagosh.dispatch.api.DriverPingRequest;
import com.aagosh.dispatch.api.RideRequestDto;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Kafka infrastructure configuration.
 *
 * <h2>Topics</h2>
 * <p>Declares {@link NewTopic} beans for {@value KafkaTopics#DRIVER_PINGS},
 * {@value KafkaTopics#RIDE_REQUESTS}, and {@value KafkaTopics#DISPATCH_FAILURES_DLQ}.
 * {@link KafkaAdmin} creates these topics on startup if absent.
 *
 * <h2>Acknowledgement mode</h2>
 * <p>Containers use {@link ContainerProperties.AckMode#MANUAL_IMMEDIATE} so that offset
 * commits happen only after the consumer method has returned successfully.
 *
 * <h2>Error handling — Exponential Backoff + Dead Letter Queue</h2>
 * <p>A {@link DefaultErrorHandler} is installed on both listener container factories with:
 * <ul>
 *   <li><strong>Exponential backoff:</strong> Retries up to {@value #DLQ_MAX_ATTEMPTS} times,
 *       starting at {@value #DLQ_INITIAL_INTERVAL_MS} ms and doubling on each attempt (capped at
 *       {@value #DLQ_MAX_INTERVAL_MS} ms). This gives the transient failure (e.g., a momentarily
 *       unavailable DB or matching-engine exception) time to self-heal before being declared
 *       unrecoverable.</li>
 *   <li><strong>Dead Letter Queue:</strong> After all retries are exhausted, the
 *       {@link DeadLetterPublishingRecoverer} routes the failing message to
 *       {@value KafkaTopics#DISPATCH_FAILURES_DLQ}. The DLQ message preserves the original
 *       record key, value bytes, and all headers (including the serialized exception cause),
 *       enabling forensic analysis and deterministic replay.</li>
 * </ul>
 *
 * <h2>DLQ routing strategy</h2>
 * <p>The recoverer's destination resolver maps every failed record to partition 0 of the DLQ
 * (single-partition, single-shard) regardless of the original topic. Driver-ping failures are
 * typically non-critical (stale location update) and are intentionally also routed to the DLQ
 * so that operators have a unified dead-letter inbox.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private static final int TOPIC_PARTITIONS = 1;
    private static final short TOPIC_REPLICATION = 1;

    // ---- Exponential Backoff + DLQ configuration ----
    /** Maximum number of delivery attempts before routing to the DLQ. */
    public static final int DLQ_MAX_ATTEMPTS = 3;
    /** Initial backoff interval in milliseconds before the first retry. */
    public static final long DLQ_INITIAL_INTERVAL_MS = 1_000L;
    /** Backoff multiplier applied to each successive retry interval. */
    public static final double DLQ_MULTIPLIER = 2.0;
    /** Maximum backoff interval — caps exponential growth to 10 seconds. */
    public static final long DLQ_MAX_INTERVAL_MS = 10_000L;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:dispatch-service}")
    private String consumerGroupId;

    @Value("${spring.kafka.listener.auto-startup:true}")
    private boolean autoStartup;

    @Value("${spring.kafka.consumer.max-poll-records:50}")
    private int maxPollRecords;

    // -------------------------------------------------------------------------
    // KafkaAdmin & Topics
    // -------------------------------------------------------------------------

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        KafkaAdmin admin = new KafkaAdmin(configs);
        // Do not fail startup if broker is unavailable (e.g., during unit tests)
        admin.setFatalIfBrokerNotAvailable(false);
        admin.setAutoCreate(autoStartup);
        return admin;
    }

    @Bean
    public NewTopic driverPingsTopic() {
        return new NewTopic(KafkaTopics.DRIVER_PINGS, TOPIC_PARTITIONS, TOPIC_REPLICATION);
    }

    @Bean
    public NewTopic rideRequestsTopic() {
        return new NewTopic(KafkaTopics.RIDE_REQUESTS, TOPIC_PARTITIONS, TOPIC_REPLICATION);
    }

    /**
     * Dead Letter Queue topic for failed ride-request dispatch events.
     * Retention is set to 30 days (2,592,000,000 ms) to allow forensic replay.
     */
    @Bean
    public NewTopic dispatchFailuresDlqTopic() {
        return new NewTopic(KafkaTopics.DISPATCH_FAILURES_DLQ, TOPIC_PARTITIONS, TOPIC_REPLICATION)
                .configs(Map.of("retention.ms", "2592000000"));
    }

    // -------------------------------------------------------------------------
    // Producer configuration & templates
    // -------------------------------------------------------------------------

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public KafkaTemplate<String, DriverPingRequest> driverPingKafkaTemplate(
            ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>((ProducerFactory) producerFactory);
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public KafkaTemplate<String, RideRequestDto> rideRequestKafkaTemplate(
            ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>((ProducerFactory) producerFactory);
    }

    // -------------------------------------------------------------------------
    // Consumer configuration & container factory
    // -------------------------------------------------------------------------

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.aagosh.dispatch.api");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Builds the exponential-backoff error handler with DLQ routing.
     *
     * <p>On the first failure, waits {@value #DLQ_INITIAL_INTERVAL_MS} ms before retry 1.
     * On the second failure, waits {@code 1000 × 2.0 = 2000} ms. After
     * {@value #DLQ_MAX_ATTEMPTS} total attempts, the message is published to
     * {@value KafkaTopics#DISPATCH_FAILURES_DLQ} via the
     * {@link DeadLetterPublishingRecoverer}.
     *
     * @param kafkaTemplate the shared producer template used by the recoverer to publish
     *                      failed records to the DLQ
     * @return a fully configured {@link DefaultErrorHandler}
     */
    @Bean
    public DefaultErrorHandler dlqErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        // Route every exhausted failure to partition 0 of the DLQ, regardless of source topic.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> {
                    log.error(
                            "[DLQ] Routing failed record to '{}'. topic={}, partition={}, offset={}, key={}, cause={}",
                            KafkaTopics.DISPATCH_FAILURES_DLQ,
                            record.topic(), record.partition(), record.offset(), record.key(),
                            exception.getClass().getSimpleName() + ": " + exception.getMessage()
                    );
                    return new TopicPartition(KafkaTopics.DISPATCH_FAILURES_DLQ, 0);
                });

        // Exponential backoff: 1s → 2s → 4s (3 attempts total, max 10s cap).
        ExponentialBackOff backOff = new ExponentialBackOff(DLQ_INITIAL_INTERVAL_MS, DLQ_MULTIPLIER);
        backOff.setMaxAttempts(DLQ_MAX_ATTEMPTS);
        backOff.setMaxInterval(DLQ_MAX_INTERVAL_MS);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // Log the retry attempt at WARN level so Prometheus alert rules can detect
        // elevated retry rates without waiting for the DLQ to be populated.
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("[RetryListener] Retry attempt {}/{} for record topic={}, offset={}, cause={}",
                        deliveryAttempt, DLQ_MAX_ATTEMPTS,
                        record.topic(), record.offset(),
                        ex.getClass().getSimpleName() + ": " + ex.getMessage())
        );

        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            DefaultErrorHandler dlqErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setAutoStartup(autoStartup);

        // Commit offsets only after successful processing.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Exponential backoff with DLQ routing after exhaustion.
        factory.setCommonErrorHandler(dlqErrorHandler);

        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> batchKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            DefaultErrorHandler dlqErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setAutoStartup(autoStartup);
        factory.setBatchListener(true);
        factory.getContainerProperties().setSubBatchPerPartition(true);

        // Commit offsets only after successful processing.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Exponential backoff with DLQ routing after exhaustion.
        factory.setCommonErrorHandler(dlqErrorHandler);

        return factory;
    }
}
