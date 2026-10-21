package com.aagosh.dispatch.persistence;

import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.TripStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TripLifecycleService}.
 *
 * <p>{@link TripTransitionPersistor} and {@link DriverRegistry} are mocked so that tests
 * target only the coordination logic in {@code TripLifecycleService}: pre-flight driver
 * resolution, outcome forwarding, in-memory {@link DriverState} updates, and the
 * {@link LifecycleOutcome#DRIVER_STATE_DIVERGED} path.
 *
 * <p>Transition rule correctness and database serialization are verified in
 * {@link TripLifecycleServiceIT}.
 */
class TripLifecycleServiceTest {

    private static final String REQUEST_ID = "REQ-UNIT-1";
    private static final String DRIVER_ID  = "DRIVER-UNIT-1";

    private TripTransitionPersistor persistor;
    private DriverRegistry          driverRegistry;
    private TripLifecycleService    service;

    /** A real DriverState so we can assert on its actual status changes. */
    private DriverState driverState;
    private Driver      driver;

    @BeforeEach
    void setUp() {
        persistor      = mock(TripTransitionPersistor.class);
        driverRegistry = mock(DriverRegistry.class);
        service        = new TripLifecycleService(persistor, driverRegistry);

        driverState = new DriverState();
        driver      = new Driver(DRIVER_ID, new GeoPoint(17.0, 78.0), driverState);
    }

    // -----------------------------------------------------------------------
    // 1. Happy path: ASSIGNED → EN_ROUTE
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("1. beginEnRoute: ASSIGNED → EN_ROUTE - TRANSITIONED, driver state advances to EN_ROUTE")
    void beginEnRoute_happyPath() {
        driverState.tryClaim(REQUEST_ID);
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.EN_ROUTE))
                .thenReturn(LifecycleOutcome.TRANSITIONED);

        LifecycleOutcome result = service.beginEnRoute(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.TRANSITIONED, result);
        assertEquals(MatchStatus.EN_ROUTE, driverState.getStatus());
    }

    // -----------------------------------------------------------------------
    // 2. Happy path: EN_ROUTE → IN_PROGRESS
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("2. beginPickup: EN_ROUTE → IN_PROGRESS - TRANSITIONED, driver state advances to ON_TRIP")
    void beginPickup_happyPath() {
        driverState.tryClaim(REQUEST_ID);
        driverState.transitionToEnRoute(REQUEST_ID);
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.IN_PROGRESS))
                .thenReturn(LifecycleOutcome.TRANSITIONED);

        LifecycleOutcome result = service.beginPickup(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.TRANSITIONED, result);
        assertEquals(MatchStatus.ON_TRIP, driverState.getStatus());
    }

    // -----------------------------------------------------------------------
    // 3. Happy path: IN_PROGRESS → COMPLETED
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("3. completeTrip: IN_PROGRESS → COMPLETED - TRANSITIONED, driver state returns to AVAILABLE")
    void completeTrip_happyPath() {
        driverState.tryClaim(REQUEST_ID);
        driverState.transitionToEnRoute(REQUEST_ID);
        driverState.transitionToOnTrip(REQUEST_ID);
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.COMPLETED))
                .thenReturn(LifecycleOutcome.TRANSITIONED);

        LifecycleOutcome result = service.completeTrip(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.TRANSITIONED, result);
        assertEquals(MatchStatus.AVAILABLE, driverState.getStatus());
    }

    // -----------------------------------------------------------------------
    // 4. Happy path: cancel from ASSIGNED
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("4. cancelTrip from ASSIGNED - TRANSITIONED, driver state returns to AVAILABLE")
    void cancelTrip_fromAssigned() {
        driverState.tryClaim(REQUEST_ID);
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.CANCELLED))
                .thenReturn(LifecycleOutcome.TRANSITIONED);

        LifecycleOutcome result = service.cancelTrip(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.TRANSITIONED, result);
        assertEquals(MatchStatus.AVAILABLE, driverState.getStatus());
    }

    // -----------------------------------------------------------------------
    // 5. Happy path: cancel from EN_ROUTE
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("5. cancelTrip from EN_ROUTE - TRANSITIONED, driver state returns to AVAILABLE")
    void cancelTrip_fromEnRoute() {
        driverState.tryClaim(REQUEST_ID);
        driverState.transitionToEnRoute(REQUEST_ID);
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.CANCELLED))
                .thenReturn(LifecycleOutcome.TRANSITIONED);

        LifecycleOutcome result = service.cancelTrip(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.TRANSITIONED, result);
        assertEquals(MatchStatus.AVAILABLE, driverState.getStatus());
    }

    // -----------------------------------------------------------------------
    // 6. Invalid: cancel from IN_PROGRESS (post-pickup)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("6. cancelTrip from IN_PROGRESS - INVALID_TRANSITION forwarded; DriverState not touched")
    void cancelTrip_fromInProgress_rejected() {
        driverState.tryClaim(REQUEST_ID);
        driverState.transitionToEnRoute(REQUEST_ID);
        driverState.transitionToOnTrip(REQUEST_ID);
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.CANCELLED))
                .thenReturn(LifecycleOutcome.INVALID_TRANSITION);

        LifecycleOutcome result = service.cancelTrip(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.INVALID_TRANSITION, result);
        // DriverState must not have been modified
        assertEquals(MatchStatus.ON_TRIP, driverState.getStatus());
    }

    // -----------------------------------------------------------------------
    // 7. Idempotent: already EN_ROUTE
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("7. beginEnRoute when already EN_ROUTE - ALREADY_IN_TARGET_STATE; DriverState not re-transitioned")
    void beginEnRoute_alreadyEnRoute_idempotent() {
        driverState.tryClaim(REQUEST_ID);
        driverState.transitionToEnRoute(REQUEST_ID);
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.EN_ROUTE))
                .thenReturn(LifecycleOutcome.ALREADY_IN_TARGET_STATE);

        LifecycleOutcome result = service.beginEnRoute(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.ALREADY_IN_TARGET_STATE, result);
        // DriverState remains EN_ROUTE (not touched)
        assertEquals(MatchStatus.EN_ROUTE, driverState.getStatus());
    }

    // -----------------------------------------------------------------------
    // 8. Idempotent: already CANCELLED
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("8. cancelTrip when already CANCELLED - ALREADY_IN_TARGET_STATE; DriverState not touched")
    void cancelTrip_alreadyCancelled_idempotent() {
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.CANCELLED))
                .thenReturn(LifecycleOutcome.ALREADY_IN_TARGET_STATE);

        LifecycleOutcome result = service.cancelTrip(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.ALREADY_IN_TARGET_STATE, result);
    }

    // -----------------------------------------------------------------------
    // 9. Trip not found
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("9. Trip not found - TRIP_NOT_FOUND; DriverState not touched")
    void beginEnRoute_tripNotFound() {
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.EN_ROUTE))
                .thenReturn(LifecycleOutcome.TRIP_NOT_FOUND);

        LifecycleOutcome result = service.beginEnRoute(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.TRIP_NOT_FOUND, result);
        assertEquals(MatchStatus.AVAILABLE, driverState.getStatus());
    }

    // -----------------------------------------------------------------------
    // 10. Driver not in DriverRegistry - pre-flight guard
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("10. Driver absent from DriverRegistry - DRIVER_NOT_FOUND; persistor never called")
    void beginEnRoute_driverNotFound() {
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.empty());

        LifecycleOutcome result = service.beginEnRoute(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.DRIVER_NOT_FOUND, result);
        verify(persistor, never()).transition(any(), any(), any());
    }

    // -----------------------------------------------------------------------
    // 11. Ownership mismatch
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("11. Ownership mismatch - OWNERSHIP_MISMATCH forwarded; DriverState not touched")
    void beginEnRoute_ownershipMismatch() {
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.EN_ROUTE))
                .thenReturn(LifecycleOutcome.OWNERSHIP_MISMATCH);

        LifecycleOutcome result = service.beginEnRoute(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.OWNERSHIP_MISMATCH, result);
        assertEquals(MatchStatus.AVAILABLE, driverState.getStatus());
    }

    // -----------------------------------------------------------------------
    // 12. DB commit succeeds but DriverState CAS fails → DRIVER_STATE_DIVERGED
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("12. DB commits EN_ROUTE but DriverState CAS fails (wrong state) - DRIVER_STATE_DIVERGED")
    void beginEnRoute_driverStateDiverged() {
        // Driver is AVAILABLE - transitionToEnRoute requires CLAIMED, so CAS will fail.
        // driverState starts AVAILABLE (no tryClaim called).
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.EN_ROUTE))
                .thenReturn(LifecycleOutcome.TRANSITIONED);

        LifecycleOutcome result = service.beginEnRoute(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.DRIVER_STATE_DIVERGED, result);
        // DriverState was not mutated to EN_ROUTE
        assertEquals(MatchStatus.AVAILABLE, driverState.getStatus());
    }

    // -----------------------------------------------------------------------
    // 13. KEY TEST: DB commit fails (exception) - DriverState must NOT be mutated
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("13. DB commit fails (exception from persistor) - DriverState is never mutated")
    void beginEnRoute_dbCommitFails_driverStateNotMutated() {
        driverState.tryClaim(REQUEST_ID);
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.EN_ROUTE))
                .thenThrow(new DataIntegrityViolationException("simulated DB failure"));

        try {
            service.beginEnRoute(REQUEST_ID, DRIVER_ID);
        } catch (DataIntegrityViolationException ignored) {
            // expected
        }

        // DriverState must remain CLAIMED - the exception fired before any DriverState update
        assertEquals(MatchStatus.CLAIMED, driverState.getStatus());
        assertEquals(REQUEST_ID, driverState.getClaimedByRequestId());
    }

    // -----------------------------------------------------------------------
    // 14. beginPickup when skipping EN_ROUTE (ASSIGNED → IN_PROGRESS) - INVALID_TRANSITION
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("14. beginPickup from ASSIGNED (skip EN_ROUTE) - INVALID_TRANSITION; DriverState not touched")
    void beginPickup_fromAssigned_invalid() {
        driverState.tryClaim(REQUEST_ID);
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.IN_PROGRESS))
                .thenReturn(LifecycleOutcome.INVALID_TRANSITION);

        LifecycleOutcome result = service.beginPickup(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.INVALID_TRANSITION, result);
        assertEquals(MatchStatus.CLAIMED, driverState.getStatus());
    }

    // -----------------------------------------------------------------------
    // 15. completeTrip already COMPLETED - idempotent
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("15. completeTrip when already COMPLETED - ALREADY_IN_TARGET_STATE")
    void completeTrip_alreadyCompleted_idempotent() {
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.COMPLETED))
                .thenReturn(LifecycleOutcome.ALREADY_IN_TARGET_STATE);

        LifecycleOutcome result = service.completeTrip(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.ALREADY_IN_TARGET_STATE, result);
    }

    // -----------------------------------------------------------------------
    // 16. cancelTrip from COMPLETED - INVALID_TRANSITION
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("16. cancelTrip from COMPLETED - INVALID_TRANSITION; DriverState not touched")
    void cancelTrip_fromCompleted_invalid() {
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.CANCELLED))
                .thenReturn(LifecycleOutcome.INVALID_TRANSITION);

        LifecycleOutcome result = service.cancelTrip(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.INVALID_TRANSITION, result);
        assertEquals(MatchStatus.AVAILABLE, driverState.getStatus());
    }

    // -----------------------------------------------------------------------
    // 17. beginEnRoute from COMPLETED - INVALID_TRANSITION
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("17. beginEnRoute from COMPLETED - INVALID_TRANSITION")
    void beginEnRoute_fromCompleted_invalid() {
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.EN_ROUTE))
                .thenReturn(LifecycleOutcome.INVALID_TRANSITION);

        LifecycleOutcome result = service.beginEnRoute(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.INVALID_TRANSITION, result);
    }

    // -----------------------------------------------------------------------
    // 18. Driver with wrong-owner claim - DRIVER_STATE_DIVERGED after DB commits
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("18. DB commits EN_ROUTE but driver CLAIMED by different request - DRIVER_STATE_DIVERGED")
    void beginEnRoute_driverClaimedByDifferentRequest_diverged() {
        // Claim driver for a different request - transitionToEnRoute("REQ-UNIT-1") will fail
        driverState.tryClaim("DIFFERENT-REQUEST");
        when(driverRegistry.findById(DRIVER_ID)).thenReturn(Optional.of(driver));
        when(persistor.transition(REQUEST_ID, DRIVER_ID, TripStatus.EN_ROUTE))
                .thenReturn(LifecycleOutcome.TRANSITIONED);

        LifecycleOutcome result = service.beginEnRoute(REQUEST_ID, DRIVER_ID);

        assertEquals(LifecycleOutcome.DRIVER_STATE_DIVERGED, result);
        // DriverState still CLAIMED by the original request
        assertEquals(MatchStatus.CLAIMED, driverState.getStatus());
        assertEquals("DIFFERENT-REQUEST", driverState.getClaimedByRequestId());
    }

    // -----------------------------------------------------------------------
    // 19. findTrip delegates to persistor
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("19. findTrip delegates to persistor.findTrip and returns its result")
    void findTrip_delegatesToPersistor() {
        TripEntity entity = new TripEntity(REQUEST_ID, DRIVER_ID, TripStatus.ASSIGNED);
        when(persistor.findTrip(REQUEST_ID)).thenReturn(Optional.of(entity));

        Optional<TripEntity> result = service.findTrip(REQUEST_ID);

        assertEquals(Optional.of(entity), result);
        verify(persistor).findTrip(REQUEST_ID);
    }
}
