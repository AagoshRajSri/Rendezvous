package com.aagosh.dispatch.api;

import com.aagosh.dispatch.messaging.AdaptiveWindowController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link TelemetryController} using MockMvc standalone setup.
 */
class TelemetryControllerTest {

    private AdaptiveWindowController windowController;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        windowController = new AdaptiveWindowController();
        TelemetryController controller = new TelemetryController(windowController);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Constructor rejects null AdaptiveWindowController")
    void constructorRejectsNullController() {
        assertThrows(NullPointerException.class, () -> new TelemetryController(null));
    }

    @Test
    @DisplayName("GET /api/telemetry/batches returns HTTP 200 with default/initial metrics")
    void getBatchTelemetryInitialState() throws Exception {
        mockMvc.perform(get("/api/telemetry/batches")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalBatches").value(0))
                .andExpect(jsonPath("$.totalRequests").value(0))
                .andExpect(jsonPath("$.lastBatchSize").value(0))
                .andExpect(jsonPath("$.maxObservedBatchSize").value(0))
                .andExpect(jsonPath("$.averageBatchSize").value(0.0))
                .andExpect(jsonPath("$.estimatedArrivalRate").value(0.0))
                .andExpect(jsonPath("$.targetWindowNanos").isNumber())
                .andExpect(jsonPath("$.targetBatchSize").isNumber());
    }

    @Test
    @DisplayName("GET /api/telemetry/batches accurately serializes populated batch metrics")
    void getBatchTelemetryPopulatedState() throws Exception {
        windowController.recordArrivals(12);
        windowController.recordArrivals(8);

        mockMvc.perform(get("/api/telemetry/batches")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBatches").value(2))
                .andExpect(jsonPath("$.totalRequests").value(20))
                .andExpect(jsonPath("$.lastBatchSize").value(8))
                .andExpect(jsonPath("$.maxObservedBatchSize").value(12))
                .andExpect(jsonPath("$.averageBatchSize").value(10.0))
                .andExpect(jsonPath("$.estimatedArrivalRate").isNumber());
    }

    @Test
    @DisplayName("GET /telemetry/batches route alias is functional and returns identical shape")
    void getBatchTelemetryAliasRoute() throws Exception {
        windowController.recordArrivals(15);

        mockMvc.perform(get("/telemetry/batches")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBatches").value(1))
                .andExpect(jsonPath("$.totalRequests").value(15))
                .andExpect(jsonPath("$.lastBatchSize").value(15))
                .andExpect(jsonPath("$.maxObservedBatchSize").value(15))
                .andExpect(jsonPath("$.averageBatchSize").value(15.0));
    }
}
