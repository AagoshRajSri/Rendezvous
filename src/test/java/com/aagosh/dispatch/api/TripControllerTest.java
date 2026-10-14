package com.aagosh.dispatch.api;

import com.aagosh.dispatch.domain.TripStatus;
import com.aagosh.dispatch.persistence.LifecycleOutcome;
import com.aagosh.dispatch.persistence.TripEntity;
import com.aagosh.dispatch.persistence.TripLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link TripController} and its integration with {@link GlobalExceptionHandler}.
 */
class TripControllerTest {

    private TripLifecycleService tripLifecycleService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tripLifecycleService = mock(TripLifecycleService.class);
        TripController controller = new TripController(tripLifecycleService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // -------------------------------------------------------------------------
    // GET /api/trips/{requestId} tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/trips/{requestId} returns 200 OK with full trip details when found")
    void getTrip_existingTrip_returns200Ok() throws Exception {
        TripEntity trip = new TripEntity("REQ-100", "DRV-100", TripStatus.ASSIGNED);
        when(tripLifecycleService.findTrip("REQ-100")).thenReturn(Optional.of(trip));

        mockMvc.perform(get("/api/trips/REQ-100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-100"))
                .andExpect(jsonPath("$.driverId").value("DRV-100"))
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    @DisplayName("GET /trips/{requestId} (route alias) returns 200 OK when found")
    void getTrip_routeAlias_returns200Ok() throws Exception {
        TripEntity trip = new TripEntity("REQ-100", "DRV-100", TripStatus.EN_ROUTE);
        when(tripLifecycleService.findTrip("REQ-100")).thenReturn(Optional.of(trip));

        mockMvc.perform(get("/trips/REQ-100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-100"))
                .andExpect(jsonPath("$.status").value("EN_ROUTE"));
    }

    @Test
    @DisplayName("GET /api/trips/{requestId} returns 404 Not Found when trip does not exist")
    void getTrip_missingTrip_returns404NotFound() throws Exception {
        when(tripLifecycleService.findTrip("REQ-MISSING")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/trips/REQ-MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Trip not found for requestId: REQ-MISSING"));
    }

    @Test
    @DisplayName("GET /api/trips/{requestId} returns 400 Bad Request when requestId is blank")
    void getTrip_blankRequestId_returns400BadRequest() throws Exception {
        mockMvc.perform(get("/api/trips/%20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("requestId must not be null or blank"));
    }

    // -------------------------------------------------------------------------
    // POST /api/trips/{requestId}/en-route tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/trips/{requestId}/en-route returns 200 OK on TRANSITIONED")
    void beginEnRoute_validRequest_returns200Ok() throws Exception {
        when(tripLifecycleService.beginEnRoute("REQ-100", "DRV-100"))
                .thenReturn(LifecycleOutcome.TRANSITIONED);

        String json = """
                {
                    "driverId": "DRV-100"
                }
                """;

        mockMvc.perform(post("/api/trips/REQ-100/en-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-100"))
                .andExpect(jsonPath("$.driverId").value("DRV-100"))
                .andExpect(jsonPath("$.status").value("EN_ROUTE"))
                .andExpect(jsonPath("$.outcome").value("TRANSITIONED"))
                .andExpect(jsonPath("$.message").value("Trip successfully transitioned to EN_ROUTE"));
    }

    @Test
    @DisplayName("POST /trips/{requestId}/en-route (route alias) returns 200 OK")
    void beginEnRoute_routeAlias_returns200Ok() throws Exception {
        when(tripLifecycleService.beginEnRoute("REQ-100", "DRV-100"))
                .thenReturn(LifecycleOutcome.TRANSITIONED);

        String json = """
                {
                    "driverId": "DRV-100"
                }
                """;

        mockMvc.perform(post("/trips/REQ-100/en-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EN_ROUTE"))
                .andExpect(jsonPath("$.outcome").value("TRANSITIONED"));
    }

    @Test
    @DisplayName("POST /api/trips/{requestId}/en-route returns 200 OK on ALREADY_IN_TARGET_STATE (idempotent)")
    void beginEnRoute_alreadyInTargetState_returns200Ok() throws Exception {
        when(tripLifecycleService.beginEnRoute("REQ-100", "DRV-100"))
                .thenReturn(LifecycleOutcome.ALREADY_IN_TARGET_STATE);

        String json = """
                {
                    "driverId": "DRV-100"
                }
                """;

        mockMvc.perform(post("/api/trips/REQ-100/en-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EN_ROUTE"))
                .andExpect(jsonPath("$.outcome").value("ALREADY_IN_TARGET_STATE"))
                .andExpect(jsonPath("$.message").value("Trip is already in status EN_ROUTE"));
    }

    // -------------------------------------------------------------------------
    // POST /api/trips/{requestId}/start tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/trips/{requestId}/start returns 200 OK on TRANSITIONED")
    void startTrip_validRequest_returns200Ok() throws Exception {
        when(tripLifecycleService.beginPickup("REQ-100", "DRV-100"))
                .thenReturn(LifecycleOutcome.TRANSITIONED);

        String json = """
                {
                    "driverId": "DRV-100"
                }
                """;

        mockMvc.perform(post("/api/trips/REQ-100/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.outcome").value("TRANSITIONED"))
                .andExpect(jsonPath("$.message").value("Trip successfully transitioned to IN_PROGRESS"));
    }

    // -------------------------------------------------------------------------
    // POST /api/trips/{requestId}/complete tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/trips/{requestId}/complete returns 200 OK on TRANSITIONED")
    void completeTrip_validRequest_returns200Ok() throws Exception {
        when(tripLifecycleService.completeTrip("REQ-100", "DRV-100"))
                .thenReturn(LifecycleOutcome.TRANSITIONED);

        String json = """
                {
                    "driverId": "DRV-100"
                }
                """;

        mockMvc.perform(post("/api/trips/REQ-100/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.outcome").value("TRANSITIONED"))
                .andExpect(jsonPath("$.message").value("Trip successfully transitioned to COMPLETED"));
    }

    // -------------------------------------------------------------------------
    // POST /api/trips/{requestId}/cancel tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/trips/{requestId}/cancel returns 200 OK on TRANSITIONED")
    void cancelTrip_validRequest_returns200Ok() throws Exception {
        when(tripLifecycleService.cancelTrip("REQ-100", "DRV-100"))
                .thenReturn(LifecycleOutcome.TRANSITIONED);

        String json = """
                {
                    "driverId": "DRV-100"
                }
                """;

        mockMvc.perform(post("/api/trips/REQ-100/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.outcome").value("TRANSITIONED"))
                .andExpect(jsonPath("$.message").value("Trip successfully transitioned to CANCELLED"));
    }

    @Test
    @DisplayName("POST /api/trips/{requestId}/cancel returns 409 Conflict when cancellation disallowed (e.g. after pickup)")
    void cancelTrip_afterPickup_returns409Conflict() throws Exception {
        when(tripLifecycleService.cancelTrip("REQ-100", "DRV-100"))
                .thenReturn(LifecycleOutcome.INVALID_TRANSITION);

        String json = """
                {
                    "driverId": "DRV-100"
                }
                """;

        mockMvc.perform(post("/api/trips/REQ-100/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Invalid trip transition: trip REQ-100 cannot transition to CANCELLED"));
    }

    // -------------------------------------------------------------------------
    // Lifecycle error outcomes tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST transition returns 409 Conflict on OWNERSHIP_MISMATCH")
    void transition_ownershipMismatch_returns409Conflict() throws Exception {
        when(tripLifecycleService.beginEnRoute("REQ-100", "DRV-IMPOSTER"))
                .thenReturn(LifecycleOutcome.OWNERSHIP_MISMATCH);

        String json = """
                {
                    "driverId": "DRV-IMPOSTER"
                }
                """;

        mockMvc.perform(post("/api/trips/REQ-100/en-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Driver DRV-IMPOSTER does not own trip REQ-100"));
    }

    @Test
    @DisplayName("POST transition returns 404 Not Found on TRIP_NOT_FOUND")
    void transition_tripNotFound_returns404NotFound() throws Exception {
        when(tripLifecycleService.beginEnRoute("REQ-UNKNOWN", "DRV-100"))
                .thenReturn(LifecycleOutcome.TRIP_NOT_FOUND);

        String json = """
                {
                    "driverId": "DRV-100"
                }
                """;

        mockMvc.perform(post("/api/trips/REQ-UNKNOWN/en-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Trip not found for requestId: REQ-UNKNOWN"));
    }

    @Test
    @DisplayName("POST transition returns 404 Not Found on DRIVER_NOT_FOUND")
    void transition_driverNotFound_returns404NotFound() throws Exception {
        when(tripLifecycleService.beginEnRoute("REQ-100", "DRV-GHOST"))
                .thenReturn(LifecycleOutcome.DRIVER_NOT_FOUND);

        String json = """
                {
                    "driverId": "DRV-GHOST"
                }
                """;

        mockMvc.perform(post("/api/trips/REQ-100/en-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Driver DRV-GHOST not found in registry"));
    }

    @Test
    @DisplayName("POST transition returns 503 Service Unavailable on DRIVER_STATE_DIVERGED with non-retry advice")
    void transition_driverStateDiverged_returns503ServiceUnavailable() throws Exception {
        when(tripLifecycleService.beginEnRoute("REQ-100", "DRV-100"))
                .thenReturn(LifecycleOutcome.DRIVER_STATE_DIVERGED);

        String json = """
                {
                    "driverId": "DRV-100"
                }
                """;

        mockMvc.perform(post("/api/trips/REQ-100/en-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("committed to the database")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Do NOT retry blindly")));
    }

    // -------------------------------------------------------------------------
    // Validation tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST transition returns 400 Bad Request when request body is missing")
    void transition_missingBody_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/trips/REQ-100/en-route")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("POST transition returns 400 Bad Request when JSON is malformed")
    void transition_malformedJson_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/trips/REQ-100/en-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Malformed or unreadable JSON request body"));
    }

    @Test
    @DisplayName("POST transition returns 400 Bad Request when driverId is null")
    void transition_nullDriverId_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/trips/REQ-100/en-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("driverId must not be null or blank"));
    }

    @Test
    @DisplayName("POST transition returns 400 Bad Request when driverId is blank")
    void transition_blankDriverId_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/trips/REQ-100/en-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("driverId must not be null or blank"));
    }

    @Test
    @DisplayName("POST transition returns 400 Bad Request when requestId in path is blank")
    void transition_blankRequestId_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/trips/%20/en-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\": \"DRV-100\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("requestId must not be null or blank"));
    }

    // -------------------------------------------------------------------------
    // Unexpected internal exception sanitization
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Unexpected internal exception is sanitized and mapped to 500 without leaking stack traces")
    void unexpectedException_sanitizedTo500() throws Exception {
        when(tripLifecycleService.beginEnRoute(anyString(), anyString()))
                .thenThrow(new RuntimeException("Database connection terminated abruptly"));

        mockMvc.perform(post("/api/trips/REQ-100/en-route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\": \"DRV-100\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An internal error occurred"));
    }
}
