package com.aagosh.dispatch.persistence;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link AssignmentPersistenceService} against a real
 * PostgreSQL instance managed by Testcontainers.
 *
 * <p>The test uses {@link DataJpaTest} to load only the JPA slice (repositories,
 * entities, Flyway, DataSource) without starting the full Spring Boot application
 * context (no Kafka beans, no REST controllers). This keeps each test fast and
 * focused on the persistence contract.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AssignmentPersistenceService.class)
class AssignmentPersistenceServiceIT {

    static {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("dispatch_test")
                    .withUsername("test_user")
                    .withPassword("test_pass")
                    .withEnv("TZ", "UTC");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            String url = postgres.getJdbcUrl();
            return url.contains("options=") ? url : (url.contains("?") ? url + "&options=-c%20timezone%3DUTC" : url + "?options=-c%20timezone%3DUTC");
        });
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);
        // Ensure Flyway and JPA are enabled for this slice (overrides test exclusions)
        registry.add("spring.flyway.enabled",       () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private AssignmentPersistenceService service;

    @Autowired
    private RideRequestRepository rideRequestRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RideRequest makeRequest(String id) {
        return new RideRequest(id, new GeoPoint(17.3850, 78.4867));
    }

    private Assignment makeAssignment(String requestId, String driverId, double cost) {
        RideRequest request = makeRequest(requestId);
        DriverState state   = new DriverState();
        state.tryClaim(requestId);
        Driver driver = new Driver(driverId, new GeoPoint(17.3860, 78.4870), state);
        return new Assignment(request, driver, cost);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. persistRequest creates a PENDING row on first call")
    void persistRequest_firstCall_createsPendingRow() {
        RideRequest request = makeRequest("REQ-IT-001");

        PersistOutcome outcome = service.persistRequest(request);

        assertEquals(PersistOutcome.PERSISTED, outcome);
        assertTrue(rideRequestRepository.existsByRequestId("REQ-IT-001"));
        RideRequestEntity entity = rideRequestRepository.findByRequestId("REQ-IT-001").orElseThrow();
        assertEquals(AssignmentPersistenceService.STATUS_PENDING, entity.getStatus());
        assertEquals(17.3850, entity.getPickupLat(), 1e-6);
        assertEquals(78.4867, entity.getPickupLon(), 1e-6);
    }

    @Test
    @DisplayName("2. persistRequest returns DUPLICATE when row already exists")
    void persistRequest_secondCall_returnsDuplicate() {
        RideRequest request = makeRequest("REQ-IT-002");

        service.persistRequest(request);                // first: PERSISTED
        PersistOutcome duplicate = service.persistRequest(request); // second: DUPLICATE

        assertEquals(PersistOutcome.DUPLICATE, duplicate);
        // Exactly one row
        assertEquals(1, rideRequestRepository.findAll().stream()
                .filter(e -> "REQ-IT-002".equals(e.getRequestId()))
                .count());
    }

    @Test
    @DisplayName("3. persistAssignment creates assignment row and updates request status to MATCHED")
    void persistAssignment_success_createsRowAndUpdatesStatus() {
        RideRequest request  = makeRequest("REQ-IT-003");
        service.persistRequest(request);

        Assignment assignment = makeAssignment("REQ-IT-003", "D-IT-001", 1.23);
        PersistOutcome outcome = service.persistAssignment(assignment);

        assertEquals(PersistOutcome.PERSISTED, outcome);
        assertTrue(assignmentRepository.existsByRequestId("REQ-IT-003"));

        AssignmentEntity entity = assignmentRepository.findByRequestId("REQ-IT-003").orElseThrow();
        assertEquals("D-IT-001", entity.getDriverId());
        assertEquals(1.23, entity.getCost(), 1e-9);

        // ride_requests row must now be MATCHED
        RideRequestEntity reqEntity = rideRequestRepository.findByRequestId("REQ-IT-003").orElseThrow();
        assertEquals(AssignmentPersistenceService.STATUS_MATCHED, reqEntity.getStatus());
    }

    @Test
    @DisplayName("4. persistAssignment returns DUPLICATE when assignment already persisted (crash-between-commit-and-ack)")
    void persistAssignment_duplicate_returnsDuplicate() {
        RideRequest request = makeRequest("REQ-IT-004");
        service.persistRequest(request);
        Assignment assignment = makeAssignment("REQ-IT-004", "D-IT-002", 0.5);

        service.persistAssignment(assignment);                  // first: PERSISTED
        PersistOutcome duplicate = service.persistAssignment(assignment); // second: DUPLICATE

        assertEquals(PersistOutcome.DUPLICATE, duplicate);
        // Exactly one assignment row
        assertEquals(1, assignmentRepository.findAll().stream()
                .filter(e -> "REQ-IT-004".equals(e.getRequestId()))
                .count());
    }

    @Test
    @DisplayName("5. updateRequestStatus transitions ride_requests status correctly")
    void updateRequestStatus_updatesStatusField() {
        RideRequest request = makeRequest("REQ-IT-005");
        service.persistRequest(request);

        service.updateRequestStatus("REQ-IT-005", AssignmentPersistenceService.STATUS_NO_DRIVER);

        RideRequestEntity entity = rideRequestRepository.findByRequestId("REQ-IT-005").orElseThrow();
        assertEquals(AssignmentPersistenceService.STATUS_NO_DRIVER, entity.getStatus());
    }

    @Test
    @DisplayName("6. releaseClaim returns the driver to AVAILABLE with ownership check")
    void releaseClaim_ownershipSafe_driverReturnedToAvailable() {
        DriverState state = new DriverState();
        state.tryClaim("REQ-IT-RELEASE");

        assertEquals(MatchStatus.CLAIMED, state.getStatus());

        // Releasing with the wrong request ID must be a no-op
        assertFalse(state.releaseClaim("WRONG-ID"),
                "releaseClaim with wrong ID must not release the driver");
        assertEquals(MatchStatus.CLAIMED, state.getStatus());

        // Releasing with the correct request ID must succeed
        assertTrue(state.releaseClaim("REQ-IT-RELEASE"));
        assertEquals(MatchStatus.AVAILABLE, state.getStatus());
    }

    @Test
    @DisplayName("7. releaseClaim is a no-op when driver is already AVAILABLE")
    void releaseClaim_alreadyAvailable_noOp() {
        DriverState state = new DriverState();
        // Driver was never claimed
        assertFalse(state.releaseClaim("ANY-ID"));
        assertEquals(MatchStatus.AVAILABLE, state.getStatus());
    }

    @Test
    @DisplayName("8. Flyway migrations created both tables with the correct schema")
    void flywayMigrations_bothTablesExist() {
        // If Flyway ran correctly, we can persist and retrieve from both tables
        RideRequest request = makeRequest("REQ-IT-SCHEMA");
        assertEquals(PersistOutcome.PERSISTED, service.persistRequest(request));
        Assignment assignment = makeAssignment("REQ-IT-SCHEMA", "D-SCHEMA", 0.99);
        assertEquals(PersistOutcome.PERSISTED, service.persistAssignment(assignment));

        assertTrue(rideRequestRepository.existsByRequestId("REQ-IT-SCHEMA"));
        assertTrue(assignmentRepository.existsByRequestId("REQ-IT-SCHEMA"));
    }
}
