package com.aagosh.dispatch.persistence;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.domain.TripStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link TripLifecycleService} and {@link TripTransitionPersistor}
 * against a real PostgreSQL instance managed by Testcontainers.
 *
 * <p>Uses {@link DataJpaTest} to load the JPA slice (repositories, entities, Flyway, DataSource)
 * without starting a full Spring Boot application context (no Kafka, no Redis, no REST).
 *
 * <h2>Transaction modes</h2>
 * <ul>
 *   <li>Most tests: default {@code @Transactional} (rolled back after each test). The
 *       {@code TripTransitionPersistor}'s {@code @Transactional} joins the ambient test
 *       transaction, allowing DB assertions within the same unit of work without real commits.</li>
 *   <li>Tests annotated {@code @Transactional(propagation = NOT_SUPPORTED)}: no ambient
 *       transaction. Each {@code TripTransitionPersistor} call starts and commits its own
 *       real transaction. Used for concurrency, rollback-vs-DriverState, and Option-B
 *       sequencing tests that require actual commits.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TripTransitionPersistor.class, TripLifecycleService.class, DriverRegistry.class,
         AssignmentPersistenceService.class})
class TripLifecycleServiceIT {

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
            return url.contains("options=") ? url
                    : (url.contains("?") ? url + "&options=-c%20timezone%3DUTC"
                                         : url + "?options=-c%20timezone%3DUTC");
        });
        registry.add("spring.datasource.username",      postgres::getUsername);
        registry.add("spring.datasource.password",      postgres::getPassword);
        registry.add("spring.flyway.enabled",            () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto",   () -> "validate");
    }

    @Autowired private TripLifecycleService         service;
    @Autowired private TripTransitionPersistor      persistor;
    @Autowired private RideRequestRepository        rideRequestRepository;
    @Autowired private AssignmentRepository         assignmentRepository;
    @Autowired private TripRepository               tripRepository;
    @Autowired private AssignmentPersistenceService assignmentPersistenceService;
    @Autowired private DriverRegistry               driverRegistry;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a persisted ride-request row (PENDING) and returns the domain object. */
    private RideRequest persistRequest(String requestId) {
        RideRequest req = new RideRequest(requestId, new GeoPoint(17.39, 78.49));
        assignmentPersistenceService.persistRequest(req);
        return req;
    }

    /** Persists a matched assignment + trip (ASSIGNED) and registers the driver. */
    private Driver persistAssignmentAndRegister(String requestId, String driverId) {
        RideRequest req    = persistRequest(requestId);
        DriverState state  = new DriverState();
        state.tryClaim(requestId);
        Driver driver = new Driver(driverId, new GeoPoint(17.40, 78.50), state);

        Assignment assignment = new Assignment(req, driver, 2.0);
        assignmentPersistenceService.persistMatchedAssignment(req, assignment);

        // Register in DriverRegistry for lifecycle calls
        try {
            driverRegistry.register(driver);
        } catch (IllegalStateException alreadyRegistered) {
            // idempotent: OK in tests sharing the Spring context
        }
        return driver;
    }

    // -------------------------------------------------------------------------
    // Test 1: Full lifecycle ASSIGNED → EN_ROUTE → IN_PROGRESS → COMPLETED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Full lifecycle: ASSIGNED → EN_ROUTE → IN_PROGRESS → COMPLETED (DB + DriverState)")
    void fullLifecycle_assignedToCompleted() {
        Driver driver = persistAssignmentAndRegister("REQ-LC-001", "DRV-LC-001");
        DriverState state = driver.state();

        // ASSIGNED → EN_ROUTE
        LifecycleOutcome r1 = service.beginEnRoute("REQ-LC-001", "DRV-LC-001");
        assertEquals(LifecycleOutcome.TRANSITIONED, r1);
        assertEquals(TripStatus.EN_ROUTE, tripRepository.findByRequestId("REQ-LC-001").orElseThrow().getStatus());
        assertEquals(MatchStatus.EN_ROUTE, state.getStatus());

        // EN_ROUTE → IN_PROGRESS
        LifecycleOutcome r2 = service.beginPickup("REQ-LC-001", "DRV-LC-001");
        assertEquals(LifecycleOutcome.TRANSITIONED, r2);
        assertEquals(TripStatus.IN_PROGRESS, tripRepository.findByRequestId("REQ-LC-001").orElseThrow().getStatus());
        assertEquals(MatchStatus.ON_TRIP, state.getStatus());

        // IN_PROGRESS → COMPLETED
        LifecycleOutcome r3 = service.completeTrip("REQ-LC-001", "DRV-LC-001");
        assertEquals(LifecycleOutcome.TRANSITIONED, r3);
        assertEquals(TripStatus.COMPLETED, tripRepository.findByRequestId("REQ-LC-001").orElseThrow().getStatus());
        assertEquals(MatchStatus.AVAILABLE, state.getStatus());
    }

    // -------------------------------------------------------------------------
    // Test 2: Cancel from ASSIGNED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2. cancelTrip from ASSIGNED - DB = CANCELLED, driver AVAILABLE")
    void cancelTrip_fromAssigned() {
        Driver driver = persistAssignmentAndRegister("REQ-LC-002", "DRV-LC-002");

        LifecycleOutcome result = service.cancelTrip("REQ-LC-002", "DRV-LC-002");

        assertEquals(LifecycleOutcome.TRANSITIONED, result);
        assertEquals(TripStatus.CANCELLED, tripRepository.findByRequestId("REQ-LC-002").orElseThrow().getStatus());
        assertEquals(MatchStatus.AVAILABLE, driver.state().getStatus());
    }

    // -------------------------------------------------------------------------
    // Test 3: Cancel from EN_ROUTE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. cancelTrip from EN_ROUTE - DB = CANCELLED, driver AVAILABLE")
    void cancelTrip_fromEnRoute() {
        Driver driver = persistAssignmentAndRegister("REQ-LC-003", "DRV-LC-003");
        service.beginEnRoute("REQ-LC-003", "DRV-LC-003");

        LifecycleOutcome result = service.cancelTrip("REQ-LC-003", "DRV-LC-003");

        assertEquals(LifecycleOutcome.TRANSITIONED, result);
        assertEquals(TripStatus.CANCELLED, tripRepository.findByRequestId("REQ-LC-003").orElseThrow().getStatus());
        assertEquals(MatchStatus.AVAILABLE, driver.state().getStatus());
    }

    // -------------------------------------------------------------------------
    // Test 4: Reject cancel from IN_PROGRESS (post-pickup forbidden)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("4. cancelTrip from IN_PROGRESS - INVALID_TRANSITION; DB unchanged, DriverState unchanged")
    void cancelTrip_fromInProgress_rejected() {
        Driver driver = persistAssignmentAndRegister("REQ-LC-004", "DRV-LC-004");
        service.beginEnRoute("REQ-LC-004", "DRV-LC-004");
        service.beginPickup("REQ-LC-004", "DRV-LC-004");

        LifecycleOutcome result = service.cancelTrip("REQ-LC-004", "DRV-LC-004");

        assertEquals(LifecycleOutcome.INVALID_TRANSITION, result);
        assertEquals(TripStatus.IN_PROGRESS, tripRepository.findByRequestId("REQ-LC-004").orElseThrow().getStatus());
        assertEquals(MatchStatus.ON_TRIP, driver.state().getStatus());
    }

    // -------------------------------------------------------------------------
    // Test 5: Idempotent beginEnRoute (twice)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("5. Idempotent: beginEnRoute called twice - second returns ALREADY_IN_TARGET_STATE")
    void beginEnRoute_idempotent() {
        persistAssignmentAndRegister("REQ-LC-005", "DRV-LC-005");

        LifecycleOutcome first  = service.beginEnRoute("REQ-LC-005", "DRV-LC-005");
        LifecycleOutcome second = service.beginEnRoute("REQ-LC-005", "DRV-LC-005");

        assertEquals(LifecycleOutcome.TRANSITIONED,          first);
        assertEquals(LifecycleOutcome.ALREADY_IN_TARGET_STATE, second);
        assertEquals(TripStatus.EN_ROUTE, tripRepository.findByRequestId("REQ-LC-005").orElseThrow().getStatus());
    }

    // -------------------------------------------------------------------------
    // Test 6: Idempotent cancelTrip (twice)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("6. Idempotent: cancelTrip called twice - second returns ALREADY_IN_TARGET_STATE")
    void cancelTrip_idempotent() {
        persistAssignmentAndRegister("REQ-LC-006", "DRV-LC-006");

        LifecycleOutcome first  = service.cancelTrip("REQ-LC-006", "DRV-LC-006");
        LifecycleOutcome second = service.cancelTrip("REQ-LC-006", "DRV-LC-006");

        assertEquals(LifecycleOutcome.TRANSITIONED,          first);
        assertEquals(LifecycleOutcome.ALREADY_IN_TARGET_STATE, second);
        assertEquals(TripStatus.CANCELLED, tripRepository.findByRequestId("REQ-LC-006").orElseThrow().getStatus());
    }

    // -------------------------------------------------------------------------
    // Test 7: Trip not found
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7. Trip not found - TRIP_NOT_FOUND; no DB mutation")
    void beginEnRoute_tripNotFound() {
        // Register driver so the pre-flight check passes
        DriverState state  = new DriverState();
        state.tryClaim("REQ-NONEXISTENT");
        Driver driver = new Driver("DRV-LC-007", new GeoPoint(17.0, 78.0), state);
        try { driverRegistry.register(driver); } catch (IllegalStateException ignored) {}

        LifecycleOutcome result = service.beginEnRoute("REQ-NONEXISTENT", "DRV-LC-007");

        assertEquals(LifecycleOutcome.TRIP_NOT_FOUND, result);
    }

    // -------------------------------------------------------------------------
    // Test 8: Ownership mismatch - wrong driverId supplied
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("8. Ownership mismatch - OWNERSHIP_MISMATCH; DB unchanged")
    void beginEnRoute_ownershipMismatch() {
        persistAssignmentAndRegister("REQ-LC-008", "DRV-LC-008");

        // Register a different driver
        DriverState wrongState = new DriverState();
        wrongState.tryClaim("REQ-LC-008");
        Driver wrongDriver = new Driver("DRV-LC-WRONG", new GeoPoint(17.0, 78.0), wrongState);
        try { driverRegistry.register(wrongDriver); } catch (IllegalStateException ignored) {}

        LifecycleOutcome result = service.beginEnRoute("REQ-LC-008", "DRV-LC-WRONG");

        assertEquals(LifecycleOutcome.OWNERSHIP_MISMATCH, result);
        // DB unchanged - still ASSIGNED
        assertEquals(TripStatus.ASSIGNED, tripRepository.findByRequestId("REQ-LC-008").orElseThrow().getStatus());
    }

    // -------------------------------------------------------------------------
    // Test 9: DRIVER_STATE_DIVERGED - DB commits but DriverState CAS fails
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9. DRIVER_STATE_DIVERGED: DB commits EN_ROUTE but DriverState is AVAILABLE (not CLAIMED)")
    void beginEnRoute_driverStateDiverged() {
        // Create the trip in ASSIGNED status
        persistRequest("REQ-LC-009");
        TripEntity trip = new TripEntity("REQ-LC-009", "DRV-LC-009", TripStatus.ASSIGNED);
        tripRepository.saveAndFlush(trip);

        // Register driver whose DriverState is AVAILABLE (never claimed) - CAS will fail
        DriverState state = new DriverState();   // AVAILABLE, not CLAIMED
        Driver driver = new Driver("DRV-LC-009", new GeoPoint(17.0, 78.0), state);
        try { driverRegistry.register(driver); } catch (IllegalStateException ignored) {}

        LifecycleOutcome result = service.beginEnRoute("REQ-LC-009", "DRV-LC-009");

        assertEquals(LifecycleOutcome.DRIVER_STATE_DIVERGED, result);
        // DB was committed to EN_ROUTE
        assertEquals(TripStatus.EN_ROUTE, tripRepository.findByRequestId("REQ-LC-009").orElseThrow().getStatus());
        // DriverState was NOT mutated - still AVAILABLE
        assertEquals(MatchStatus.AVAILABLE, state.getStatus());
    }

    // -------------------------------------------------------------------------
    // Test 10: Invalid: ASSIGNED → COMPLETED (skipping steps)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("10. Invalid transition ASSIGNED → COMPLETED - INVALID_TRANSITION; DB unchanged")
    void completeTrip_fromAssigned_invalidTransition() {
        persistAssignmentAndRegister("REQ-LC-010", "DRV-LC-010");

        LifecycleOutcome result = service.completeTrip("REQ-LC-010", "DRV-LC-010");

        assertEquals(LifecycleOutcome.INVALID_TRANSITION, result);
        assertEquals(TripStatus.ASSIGNED, tripRepository.findByRequestId("REQ-LC-010").orElseThrow().getStatus());
    }

    // -------------------------------------------------------------------------
    // Test 11: Invalid: EN_ROUTE → CANCELLED → ASSIGNED (terminal re-open)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("11. Re-opening a CANCELLED trip - INVALID_TRANSITION; terminal status preserved")
    void beginEnRoute_afterCancelled_invalid() {
        persistAssignmentAndRegister("REQ-LC-011", "DRV-LC-011");
        service.cancelTrip("REQ-LC-011", "DRV-LC-011");

        LifecycleOutcome result = service.beginEnRoute("REQ-LC-011", "DRV-LC-011");

        assertEquals(LifecycleOutcome.INVALID_TRANSITION, result);
        assertEquals(TripStatus.CANCELLED, tripRepository.findByRequestId("REQ-LC-011").orElseThrow().getStatus());
    }

    // -------------------------------------------------------------------------
    // Test 12: KEY TEST - Concurrent beginEnRoute: DB and DriverState consistent
    // Uses NOT_SUPPORTED so each transaction actually commits.
    // -------------------------------------------------------------------------

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("12. Concurrent beginEnRoute: exactly one TRANSITIONED, DB = EN_ROUTE, DriverState = EN_ROUTE")
    void concurrentBeginEnRoute_exactlyOneWins() throws Exception {
        // Setup: create request, assignment, trip - these commit because NOT_SUPPORTED has no ambient tx
        RideRequest req = new RideRequest("REQ-LC-CONC", new GeoPoint(17.39, 78.49));
        assignmentPersistenceService.persistRequest(req);

        DriverState state  = new DriverState();
        state.tryClaim("REQ-LC-CONC");
        Driver driver = new Driver("DRV-LC-CONC", new GeoPoint(17.40, 78.50), state);

        Assignment assignment = new Assignment(req, driver, 2.0);
        assignmentPersistenceService.persistMatchedAssignment(req, assignment);

        try { driverRegistry.register(driver); } catch (IllegalStateException ignored) {}

        // Two threads race to call beginEnRoute for the same trip
        int contenders = 2;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger transitioned = new AtomicInteger(0);
        AtomicInteger alreadyAtTarget = new AtomicInteger(0);
        List<AtomicReference<LifecycleOutcome>> outcomes = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < contenders; i++) {
            AtomicReference<LifecycleOutcome> outcomeRef = new AtomicReference<>();
            outcomes.add(outcomeRef);
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                LifecycleOutcome o = service.beginEnRoute("REQ-LC-CONC", "DRV-LC-CONC");
                outcomeRef.set(o);
                if (o == LifecycleOutcome.TRANSITIONED)            transitioned.incrementAndGet();
                if (o == LifecycleOutcome.ALREADY_IN_TARGET_STATE) alreadyAtTarget.incrementAndGet();
                return null;
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Exactly one winner
        assertEquals(1, transitioned.get(),    "Exactly one thread must TRANSITION");
        assertEquals(1, alreadyAtTarget.get(), "Exactly one thread must see ALREADY_IN_TARGET_STATE");

        // DB reflects committed EN_ROUTE
        TripEntity finalTrip = tripRepository.findByRequestId("REQ-LC-CONC").orElseThrow();
        assertEquals(TripStatus.EN_ROUTE, finalTrip.getStatus(), "DB must be EN_ROUTE after concurrent race");

        // DriverState reflects EN_ROUTE (updated by the winning thread)
        assertEquals(MatchStatus.EN_ROUTE, state.getStatus(),
                "DriverState must be EN_ROUTE - the winning thread updated it after its DB commit");
    }

    // -------------------------------------------------------------------------
    // Test 13: KEY TEST - DB rollback must NOT mutate DriverState
    // Simulates a DB failure path via an invalid transition that forces a non-commit path.
    // Uses NOT_SUPPORTED to exercise real transactions.
    // -------------------------------------------------------------------------

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("13. DB non-transition (INVALID_TRANSITION) - DriverState is never mutated")
    void invalidTransition_driverStateNotMutated() {
        // Setup: create trip in ASSIGNED status
        assignmentPersistenceService.persistRequest(
                new RideRequest("REQ-LC-ROLLBACK", new GeoPoint(17.0, 78.0)));
        TripEntity trip = new TripEntity("REQ-LC-ROLLBACK", "DRV-LC-ROLLBACK", TripStatus.ASSIGNED);
        tripRepository.saveAndFlush(trip);

        DriverState state = new DriverState();
        state.tryClaim("REQ-LC-ROLLBACK");
        Driver driver = new Driver("DRV-LC-ROLLBACK", new GeoPoint(17.0, 78.0), state);
        try { driverRegistry.register(driver); } catch (IllegalStateException ignored) {}

        // Attempt an invalid transition (ASSIGNED → COMPLETED - not permitted)
        LifecycleOutcome result = service.completeTrip("REQ-LC-ROLLBACK", "DRV-LC-ROLLBACK");

        assertEquals(LifecycleOutcome.INVALID_TRANSITION, result);

        // DB unchanged - still ASSIGNED
        assertEquals(TripStatus.ASSIGNED, tripRepository.findByRequestId("REQ-LC-ROLLBACK").orElseThrow().getStatus());

        // DriverState unchanged - still CLAIMED
        assertEquals(MatchStatus.CLAIMED, state.getStatus());
        assertEquals("REQ-LC-ROLLBACK", state.getClaimedByRequestId());
    }

    // -------------------------------------------------------------------------
    // Test 14: findTrip delegates correctly
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("14. findTrip returns present trip for existing requestId")
    void findTrip_existing() {
        persistAssignmentAndRegister("REQ-LC-FIND", "DRV-LC-FIND");

        Optional<TripEntity> result = service.findTrip("REQ-LC-FIND");

        assertTrue(result.isPresent());
        assertEquals("REQ-LC-FIND", result.get().getRequestId());
        assertEquals(TripStatus.ASSIGNED, result.get().getStatus());
    }

    @Test
    @DisplayName("14b. findTrip returns empty for non-existent requestId")
    void findTrip_nonExistent() {
        Optional<TripEntity> result = service.findTrip("REQ-DOES-NOT-EXIST");
        assertFalse(result.isPresent());
    }
}
