package com.aagosh.dispatch.metrics;

import com.aagosh.dispatch.cache.RedisLocationCache;
import com.aagosh.dispatch.persistence.AssignmentPersistenceService;
import com.aagosh.dispatch.persistence.RideRequestRepository;
import com.aagosh.dispatch.persistence.TripLifecycleService;
import com.aagosh.dispatch.persistence.TripTransitionPersistor;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test verifying that Spring Boot Actuator and Micrometer Prometheus endpoints
 * are properly exposed, configured securely, and that sensitive endpoints are disabled.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class ActuatorSecurityTest {

    @MockitoBean
    private AssignmentPersistenceService persistenceService;

    @MockitoBean
    private TripTransitionPersistor tripTransitionPersistor;

    @MockitoBean
    private TripLifecycleService tripLifecycleService;

    @MockitoBean
    private RideRequestRepository rideRequestRepository;

    @MockitoBean
    private RedisLocationCache redisLocationCache;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("1. GET /actuator/health returns 200 OK without exposing component details")
    void healthEndpointReturnsOkWithoutSensitiveDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("2. GET /actuator/prometheus returns 200 OK with Prometheus-formatted metrics")
    void prometheusEndpointReturnsMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(Matchers.containsString("dispatch_outcomes_total")))
                .andExpect(content().string(Matchers.containsString("dispatch_matching_duration_seconds")));
    }

    @Test
    @DisplayName("3. Sensitive management endpoints are disabled or unexposed (return 404 Not Found)")
    void sensitiveEndpointsAreUnexposed() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/heapdump")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/shutdown")).andExpect(status().isNotFound());
    }
}
