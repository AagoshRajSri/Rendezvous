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

class RideControllerTest {

    private KafkaTemplate<String, RideRequestDto> kafkaTemplate;
    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        RideController controller = new RideController(kafkaTemplate);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void setupSuccessfulSend() {
        @SuppressWarnings("unchecked")
        SendResult<String, RideRequestDto> sendResult = mock(SendResult.class);
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(KafkaTopics.RIDE_REQUESTS, 0), 0, 0, 0, 0, 0);
        when(sendResult.getRecordMetadata()).thenReturn(metadata);
        CompletableFuture<SendResult<String, RideRequestDto>> future =
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(eq(KafkaTopics.RIDE_REQUESTS), anyString(), any(RideRequestDto.class)))
                .thenReturn(future);
    }

    @Test
    @DisplayName("1. Valid ride request publishes to ride-requests topic with requestId as key and returns HTTP 202 Accepted")
    void validRideRequestPublishesToKafka() throws Exception {
        setupSuccessfulSend();

        String requestJson = """
                {
                    "requestId": "REQ-100",
                    "latitude": 17.3860,
                    "longitude": 78.4870
                }
                """;

        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value("REQ-100"))
                .andExpect(jsonPath("$.message").value("Ride request accepted and queued for matching"));

        ArgumentCaptor<RideRequestDto> captor = ArgumentCaptor.forClass(RideRequestDto.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.RIDE_REQUESTS), eq("REQ-100"), captor.capture());

        RideRequestDto sent = captor.getValue();
        assertEquals("REQ-100", sent.resolveId());
        assertEquals(17.3860, sent.latitude());
        assertEquals(78.4870, sent.longitude());
    }

    @Test
    @DisplayName("2. Valid ride request with legacy 'id' field publishes to ride-requests using resolved id")
    void legacyIdFieldResolvesAndPublishes() throws Exception {
        setupSuccessfulSend();

        String requestJson = """
                {
                    "id": "REQ-LEGACY",
                    "latitude": 17.3860,
                    "longitude": 78.4870
                }
                """;

        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value("REQ-LEGACY"));

        verify(kafkaTemplate).send(eq(KafkaTopics.RIDE_REQUESTS), eq("REQ-LEGACY"), any(RideRequestDto.class));
    }

    @Test
    @DisplayName("3. Invalid pickup coordinates reject with HTTP 400 Bad Request without publishing")
    void invalidCoordinatesRejectedWithoutPublishing() throws Exception {
        // Latitude > 90
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "R1", "latitude": 95.0, "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));

        // Latitude < -90
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "R1", "latitude": -91.0, "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest());

        // Longitude > 180
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "R1", "latitude": 17.0, "longitude": 185.0}
                                """))
                .andExpect(status().isBadRequest());

        // Longitude < -180
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "R1", "latitude": 17.0, "longitude": -185.0}
                                """))
                .andExpect(status().isBadRequest());

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("4. Malformed ride requests reject with HTTP 400 Bad Request without publishing")
    void malformedRideRequestsRejectedWithoutPublishing() throws Exception {
        // Blank requestId
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "   ", "latitude": 17.0, "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("requestId must not be null or blank"));

        // Missing requestId and id
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude": 17.0, "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest());

        // Missing latitude
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "R1", "longitude": 78.0}
                                """))
                .andExpect(status().isBadRequest());

        // Malformed JSON syntax
        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or unreadable JSON request body"));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("5. Kafka publication failure returns HTTP 503 Service Unavailable")
    void publicationFailureReturnsServiceUnavailable() throws Exception {
        CompletableFuture<SendResult<String, RideRequestDto>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new TimeoutException("Kafka broker unreachable"));
        when(kafkaTemplate.send(eq(KafkaTopics.RIDE_REQUESTS), anyString(), any(RideRequestDto.class)))
                .thenReturn(failedFuture);

        String requestJson = """
                {
                    "requestId": "REQ-FAIL",
                    "latitude": 17.3860,
                    "longitude": 78.4870
                }
                """;

        mockMvc.perform(post("/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value(
                        "Event could not be published; the message broker is temporarily unavailable"));
    }
}
