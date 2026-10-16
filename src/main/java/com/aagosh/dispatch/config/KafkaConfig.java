package com.aagosh.dispatch.config;

import com.aagosh.dispatch.api.DriverPingRequest;
import com.aagosh.dispatch.api.RideRequestDto;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Kafka infrastructure configuration.
 *
 * <h2>Topics</h2>
 * <p>Declares {@link NewTopic} beans for {@value KafkaTopics#DRIVER_PINGS} and
 * {@value KafkaTopics#RIDE_REQUESTS}. {@link KafkaAdmin} creates these topics on startup
 * if absent (single partition, replication factor 1, appropriate for local development).
 *
 * <h2>Acknowledgement mode</h2>
 * <p>Containers use {@link ContainerProperties.AckMode#MANUAL_IMMEDIATE} so that offset
 * commits happen only after the consumer method has returned successfully.
 *
 * <h2>Error handling</h2>
 * <p>A {@link DefaultErrorHandler} with a {@link FixedBackOff} of 2 retries at 1-second
 * intervals is installed.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    private static final int TOPIC_PARTITIONS = 1;
    private static final short TOPIC_REPLICATION = 1;

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

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setAutoStartup(autoStartup);

        // Commit offsets only after successful processing.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Retry twice at 1-second intervals; then log and skip.
        factory.setCommonErrorHandler(
                new DefaultErrorHandler(new FixedBackOff(1_000L, 2L)));

        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> batchKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setAutoStartup(autoStartup);
        factory.setBatchListener(true);
        factory.getContainerProperties().setSubBatchPerPartition(true);

        // Commit offsets only after successful processing.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Retry twice at 1-second intervals; then log and skip.
        factory.setCommonErrorHandler(
                new DefaultErrorHandler(new FixedBackOff(1_000L, 2L)));

        return factory;
    }
}
