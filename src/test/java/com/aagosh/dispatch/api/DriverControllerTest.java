package com.aagosh.dispatch.api;

import com.aagosh.dispatch.cache.RedisLocationCache;
import com.aagosh.dispatch.config.DriverFreshnessConfig;
import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DriverControllerTest {

    private KafkaTemplate<String, DriverPingRequest> kafkaTemplate;
    private DriverRegistry driverRegistry;
    private RedisLocationCache redisLocationCache;
    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate      = mock(KafkaTemplate.class);
        driverRegistry     = new DriverRegistry();
        redisLocationCache = mock(RedisLocationCache.class);
        when(redisLocationCache.getLocation(anyString())).thenReturn(RedisLocationCache.LocationResult.Miss.INSTANCE);

        DriverController controller = new DriverController(
                kafkaTemplate, driverRegistry, redisLocationCache, DriverFreshnessConfig.DEFAULT);
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

    // -------------------------------------------------------------------------
    // GET /drivers/{driverId}/location
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. GET /drivers/{driverId}/location returns 404 when driver is not registered")
    void getDriverLocationNotFound() throws Exception {
        mockMvc.perform(get("/drivers/NON_EXISTENT/location"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("No driver registered with id: NON_EXISTENT"));
    }

    @Test
    @DisplayName("7. GET /drivers/{driverId}/location returns 409 Conflict with fresh=false for driver with updatedAt=0")
    void getDriverLocationUnversionedReturnsConflict() throws Exception {
        Driver d = new Driver("D_UNVER", new GeoPoint(17.385, 78.4867), new DriverState(), 0L);
        driverRegistry.register(d);

        mockMvc.perform(get("/drivers/D_UNVER/location"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.driverId").value("D_UNVER"))
                .andExpect(jsonPath("$.fresh").value(false))
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andExpect(jsonPath("$.longitude").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    @Test
    @DisplayName("8. GET /drivers/{driverId}/location returns 409 Conflict with fresh=false for stale driver")
    void getDriverLocationStaleReturnsConflict() throws Exception {
        long now = System.currentTimeMillis();
        long staleTs = now - 120_000L; // 2 minutes ago (staleness threshold 60s)
        Driver d = new Driver("D_STALE", new GeoPoint(17.385, 78.4867), new DriverState(), staleTs);
        driverRegistry.register(d);

        mockMvc.perform(get("/drivers/D_STALE/location"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.driverId").value("D_STALE"))
                .andExpect(jsonPath("$.fresh").value(false))
                .andExpect(jsonPath("$.latitude").value(17.385))
                .andExpect(jsonPath("$.longitude").value(78.4867))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    @DisplayName("9. GET /drivers/{driverId}/location returns 200 OK with fresh=true for fresh driver")
    void getDriverLocationFreshReturnsOk() throws Exception {
        long now = System.currentTimeMillis();
        long freshTs = now - 5_000L; // 5 seconds ago
        Driver d = new Driver("D_FRESH", new GeoPoint(17.385, 78.4867), new DriverState(), freshTs);
        driverRegistry.register(d);

        mockMvc.perform(get("/drivers/D_FRESH/location"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value("D_FRESH"))
                .andExpect(jsonPath("$.fresh").value(true))
                .andExpect(jsonPath("$.latitude").value(17.385))
                .andExpect(jsonPath("$.longitude").value(78.4867))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    @DisplayName("10. GET /drivers/{driverId}/location returns 409 Conflict for future-skewed driver")
    void getDriverLocationFutureSkewReturnsConflict() throws Exception {
        long now = System.currentTimeMillis();
        long futureTs = now + 30_000L; // 30 seconds into future (max skew 5s)
        Driver d = new Driver("D_FUTURE", new GeoPoint(17.385, 78.4867), new DriverState(), futureTs);
        driverRegistry.register(d);

        mockMvc.perform(get("/drivers/D_FUTURE/location"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.driverId").value("D_FUTURE"))
                .andExpect(jsonPath("$.fresh").value(false));
    }

    @Test
    @DisplayName("11. GET /drivers/{driverId}/location returns 200 OK directly from Redis cache hit when fresh")
    void getDriverLocationRedisHitFreshReturnsOk() throws Exception {
        long now = System.currentTimeMillis();
        long freshTs = now - 5_000L;
        when(redisLocationCache.getLocation("D_REDIS_FRESH")).thenReturn(
                new RedisLocationCache.LocationResult.Found(new GeoPoint(12.9716, 77.5946), freshTs));

        mockMvc.perform(get("/drivers/D_REDIS_FRESH/location"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value("D_REDIS_FRESH"))
                .andExpect(jsonPath("$.fresh").value(true))
                .andExpect(jsonPath("$.latitude").value(12.9716))
                .andExpect(jsonPath("$.longitude").value(77.5946))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    @DisplayName("12. GET /drivers/{driverId}/location returns 409 Conflict from Redis cache hit when stale")
    void getDriverLocationRedisHitStaleReturnsConflict() throws Exception {
        long now = System.currentTimeMillis();
        long staleTs = now - 120_000L;
        when(redisLocationCache.getLocation("D_REDIS_STALE")).thenReturn(
                new RedisLocationCache.LocationResult.Found(new GeoPoint(12.9716, 77.5946), staleTs));

        mockMvc.perform(get("/drivers/D_REDIS_STALE/location"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.driverId").value("D_REDIS_STALE"))
                .andExpect(jsonPath("$.fresh").value(false))
                .andExpect(jsonPath("$.latitude").value(12.9716))
                .andExpect(jsonPath("$.longitude").value(77.5946));
    }

    @Test
    @DisplayName("13. GET /drivers/{driverId}/location falls back to in-memory registry on Redis connection failure")
    void getDriverLocationRedisErrorWithInMemoryFallbackReturnsOk() throws Exception {
        long now = System.currentTimeMillis();
        long freshTs = now - 5_000L;
        Driver d = new Driver("D_FALLBACK", new GeoPoint(12.9716, 77.5946), new DriverState(), freshTs);
        driverRegistry.register(d);

        when(redisLocationCache.getLocation("D_FALLBACK"))
                .thenReturn(new RedisLocationCache.LocationResult.RedisError("connection failure: timeout"));

        mockMvc.perform(get("/drivers/D_FALLBACK/location"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value("D_FALLBACK"))
                .andExpect(jsonPath("$.fresh").value(true))
                .andExpect(jsonPath("$.latitude").value(12.9716))
                .andExpect(jsonPath("$.longitude").value(77.5946));
    }

    @Test
    @DisplayName("14. GET /drivers/{driverId}/location returns 503 Service Unavailable (NOT 404) on Redis failure when driver is not in memory")
    void getDriverLocationRedisErrorWithoutInMemoryFallbackReturnsServiceUnavailable() throws Exception {
        when(redisLocationCache.getLocation("D_UNKNOWN_DOWN"))
                .thenReturn(new RedisLocationCache.LocationResult.RedisError("connection failure: refused"));

        mockMvc.perform(get("/drivers/D_UNKNOWN_DOWN/location"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("Driver location cache is temporarily unavailable: connection failure: refused"));
    }
}
