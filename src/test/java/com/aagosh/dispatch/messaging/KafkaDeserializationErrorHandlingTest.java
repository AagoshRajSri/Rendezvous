package com.aagosh.dispatch.messaging;

import com.aagosh.dispatch.api.DriverPingRequest;
import com.aagosh.dispatch.api.RideRequestDto;
import com.aagosh.dispatch.cache.RedisLocationCache;
import com.aagosh.dispatch.config.DriverFreshnessConfig;
import com.aagosh.dispatch.config.KafkaConfig;
import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.matching.DispatchCostConfig;
import com.aagosh.dispatch.matching.Matcher;
import com.aagosh.dispatch.persistence.AssignmentPersistenceService;
import com.aagosh.dispatch.persistence.PersistOutcome;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Focused tests verifying that malformed payloads and deserialization errors
 * are caught by {@link ErrorHandlingDeserializer} and safely skipped without
 * disrupting consumer partitions or throwing unhandled listener exceptions.
 */
class KafkaDeserializationErrorHandlingTest {

    @Test
    @DisplayName("1. ErrorHandlingDeserializer catches malformed JSON bytes, attaches exception header, and returns null")
    void errorHandlingDeserializerCatchesMalformedJson() {
        try (ErrorHandlingDeserializer<Object> deserializer = new ErrorHandlingDeserializer<>()) {
            Map<String, Object> configs = Map.of(
                    ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName(),
                    JsonDeserializer.TRUSTED_PACKAGES, "com.aagosh.dispatch.api"
            );
            deserializer.configure(configs, false);

            byte[] malformedBytes = "{ malformed JSON syntax: missing-quotes }".getBytes(StandardCharsets.UTF_8);
            RecordHeaders headers = new RecordHeaders();

            // Deserialization must NOT throw an unhandled exception to Kafka fetcher
            Object result = deserializer.deserialize(KafkaTopics.RIDE_REQUESTS, headers, malformedBytes);

            // Null value is safely returned
            assertNull(result, "Malformed byte payload should produce null object");

            // Deserialization exception metadata header is attached
            assertNotNull(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER),
                    "Header must contain deserialization exception information");
        }
    }

    @Test
    @DisplayName("2. RideRequestConsumer safely skips and acknowledges null/failed-deserialization payload")
    void rideRequestConsumerHandlesDeserializationFailureRecord() {
        Matcher mockMatcher = mock(Matcher.class);
        DriverRegistry driverRegistry = new DriverRegistry();
        AssignmentPersistenceService persistenceService = mock(AssignmentPersistenceService.class);
        org.mockito.Mockito.when(persistenceService.persistRequest(org.mockito.ArgumentMatchers.any()))
                .thenReturn(PersistOutcome.PERSISTED);
        RideRequestConsumer consumer = new RideRequestConsumer(mockMatcher, driverRegistry, DriverFreshnessConfig.DEFAULT, DispatchCostConfig.DEFAULT, persistenceService);
        Acknowledgment ack = mock(Acknowledgment.class);

        RecordHeaders headers = new RecordHeaders();
        headers.add(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, "corrupted-bytes".getBytes());
        ConsumerRecord<String, RideRequestDto> record = new ConsumerRecord<>(
                KafkaTopics.RIDE_REQUESTS, 0, 100L, 0L, null, 0, 0, "KEY-1", null, headers, null
        );

        consumer.onRideRequest(record, ack);

        // Offset acknowledged so partition is not stalled
        verify(ack).acknowledge();
        // Matcher never invoked on bad message
        verify(mockMatcher, never()).match(any(), any(), any());
    }

    @Test
    @DisplayName("3. DriverPingConsumer safely skips and acknowledges null/failed-deserialization payload")
    void driverPingConsumerHandlesDeserializationFailureRecord() {
        DriverRegistry driverRegistry = new DriverRegistry();
        RedisLocationCache redisCache = mock(RedisLocationCache.class);
        DriverPingConsumer consumer = new DriverPingConsumer(driverRegistry, redisCache);
        Acknowledgment ack = mock(Acknowledgment.class);

        RecordHeaders headers = new RecordHeaders();
        headers.add(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, "corrupted-bytes".getBytes());
        ConsumerRecord<String, DriverPingRequest> record = new ConsumerRecord<>(
                KafkaTopics.DRIVER_PINGS, 0, 100L, 0L, null, 0, 0, "KEY-2", null, headers, null
        );

        consumer.onDriverPing(record, ack);

        // Offset acknowledged so partition is not stalled
        verify(ack).acknowledge();
        // Registry untouched
        assertTrue(driverRegistry.snapshot().isEmpty());
    }

    @Test
    @DisplayName("4. KafkaConfig consumer factory is explicitly configured with ErrorHandlingDeserializer and JsonDeserializer")
    void kafkaConfigConsumerFactoryUsesErrorHandlingDeserializer() {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "consumerGroupId", "dispatch-service");
        ReflectionTestUtils.setField(config, "autoStartup", false);

        ConsumerFactory<String, Object> consumerFactory = config.consumerFactory();
        Map<String, Object> configurationProperties = consumerFactory.getConfigurationProperties();

        assertEquals(StringDeserializer.class,
                configurationProperties.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertEquals(ErrorHandlingDeserializer.class,
                configurationProperties.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        assertEquals(JsonDeserializer.class.getName(),
                configurationProperties.get(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS));
        assertEquals("com.aagosh.dispatch.api",
                configurationProperties.get(JsonDeserializer.TRUSTED_PACKAGES));
    }
}
