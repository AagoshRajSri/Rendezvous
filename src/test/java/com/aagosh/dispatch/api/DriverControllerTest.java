package com.aagosh.dispatch.api;

import com.aagosh.dispatch.config.KafkaTopics;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DriverControllerTest {

    private KafkaTemplate<String, DriverPingRequest> kafkaTemplate;
    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        DriverController controller = new DriverController(kafkaTemplate);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void setupSuccessfulSend() {
        @SuppressWarnings("unchecked")
        SendResult<String, DriverPingRequest> sendResult = mock(SendResult.class);
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(KafkaTopics.DRIVER_PINGS, 0), 0, 0, 0, 0, 0);
        when(sendResult.getRecordMetadata()).thenReturn(metadata);
        CompletableFuture<SendResult<String, DriverPingRequest>> future =
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(eq(KafkaTopics.DRIVER_PINGS), anyString(), any(DriverPingRequest.class)))
                .thenReturn(future);
    }

    @Test
    @DisplayName("1. POST /ping publishes valid ping to driver-pings and returns HTTP 202 Accepted")
    void validDriverPingPublishesToKafka() throws Exception {
        setupSuccessfulSend();

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
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.driverId").value("D1"))
                .andExpect(jsonPath("$.message").value("Driver ping accepted and queued for processing"));

        ArgumentCaptor<DriverPingRequest> captor = ArgumentCaptor.forClass(DriverPingRequest.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.DRIVER_PINGS), eq("D1"), captor.capture());

        DriverPingRequest sent = captor.getValue();
        assertEquals("D1", sent.driverId());
        assertEquals(17.3850, sent.latitude());
        assertEquals(78.4867, sent.longitude());
        assertEquals(PingAction.AUTO, sent.resolveAction());
    }

    @Test
    @DisplayName("2. POST /register forces REGISTER action and publishes to driver-pings returning HTTP 202 Accepted (backward-compatible without capturedAt)")
    void validDriverRegistrationWithoutCapturedAtPublishesToKafka() throws Exception {
        setupSuccessfulSend();

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
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.driverId").value("D_REG"))
                .andExpect(jsonPath("$.message").value("Driver registration accepted and queued for processing"));

        ArgumentCaptor<DriverPingRequest> captor = ArgumentCaptor.forClass(DriverPingRequest.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.DRIVER_PINGS), eq("D_REG"), captor.capture());

        DriverPingRequest sent = captor.getValue();
        assertEquals("D_REG", sent.driverId());
        assertEquals(PingAction.REGISTER, sent.action());
        org.junit.jupiter.api.Assertions.assertNull(sent.capturedAt(), "capturedAt must be null when omitted by client");
    }

    @Test
    @DisplayName("2b. POST /register preserves client-provided capturedAt timestamp in published event")
    void validDriverRegistrationWithCapturedAtPublishesToKafka() throws Exception {
        setupSuccessfulSend();

        String json = """
                {
                    "driverId": "D_REG_TS",
                    "latitude": 17.4400,
                    "longitude": 78.3489,
                    "capturedAt": 1728900000000
                }
                """;

        mockMvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.driverId").value("D_REG_TS"))
                .andExpect(jsonPath("$.message").value("Driver registration accepted and queued for processing"));

        ArgumentCaptor<DriverPingRequest> captor = ArgumentCaptor.forClass(DriverPingRequest.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.DRIVER_PINGS), eq("D_REG_TS"), captor.capture());

        DriverPingRequest sent = captor.getValue();
        assertEquals("D_REG_TS", sent.driverId());
        assertEquals(PingAction.REGISTER, sent.action());
        assertEquals(1728900000000L, sent.capturedAt(), "capturedAt must be preserved through registration");
    }

    @Test
    @DisplayName("3. Invalid coordinates reject with HTTP 400 Bad Request without publishing to Kafka")
    void invalidCoordinatesRejectedWithoutPublishing() throws Exception {
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

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("4. Malformed request input rejects with HTTP 400 Bad Request without publishing")
    void malformedInputRejectedWithoutPublishing() throws Exception {
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

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("5. Kafka publication failure returns HTTP 503 Service Unavailable")
    void publicationFailureReturnsServiceUnavailable() throws Exception {
        CompletableFuture<SendResult<String, DriverPingRequest>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new TimeoutException("Kafka broker timeout"));
        when(kafkaTemplate.send(eq(KafkaTopics.DRIVER_PINGS), anyString(), any(DriverPingRequest.class)))
                .thenReturn(failedFuture);

        String json = """
                {
                    "driverId": "D_FAIL",
                    "latitude": 17.3850,
                    "longitude": 78.4867
                }
                """;

        mockMvc.perform(post("/ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value(
                        "Event could not be published; the message broker is temporarily unavailable"));
    }
}
