package com.aagosh.dispatch.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DriverStateTest {

    @Test
    @DisplayName("DriverState starts AVAILABLE with no claim and no release record")
    void startsAvailable() {
        DriverState driver = new DriverState();

        assertEquals(MatchStatus.AVAILABLE, driver.getStatus());
        assertNull(driver.getClaimedByRequestId());
        assertNull(driver.getLastReleasedByRequestId());
    }

    @Test
    @DisplayName("Only first claim succeeds; second claim by different request is rejected")
    void onlyFirstClaimSucceeds() {
        DriverState driver = new DriverState();

        assertTrue(driver.tryClaim("request-1"));
        assertFalse(driver.tryClaim("request-2"));

        assertEquals(MatchStatus.CLAIMED, driver.getStatus());
        assertEquals("request-1", driver.getClaimedByRequestId());
    }

    @Test
    @DisplayName("tryClaim rejects null or blank requestId")
    void tryClaim_validatesInput() {
        DriverState driver = new DriverState();

        assertThrows(NullPointerException.class, () -> driver.tryClaim(null));
        assertThrows(IllegalArgumentException.class, () -> driver.tryClaim(""));
        assertThrows(IllegalArgumentException.class, () -> driver.tryClaim("   "));
    }

    @Test
    @DisplayName("Happy path: CLAIMED -> EN_ROUTE -> ON_TRIP -> COMPLETED")
    void happyPath_fullLifecycleTransitions() {
        DriverState driver = new DriverState();

        assertTrue(driver.tryClaim("req-1"));
        assertEquals(MatchStatus.CLAIMED, driver.getStatus());
        assertEquals("req-1", driver.getClaimedByRequestId());

        assertTrue(driver.transitionToEnRoute("req-1"));
        assertEquals(MatchStatus.EN_ROUTE, driver.getStatus());
        assertEquals("req-1", driver.getClaimedByRequestId());

        assertTrue(driver.transitionToOnTrip("req-1"));
        assertEquals(MatchStatus.ON_TRIP, driver.getStatus());
        assertEquals("req-1", driver.getClaimedByRequestId());

        assertTrue(driver.completeTrip("req-1"));
        assertEquals(MatchStatus.AVAILABLE, driver.getStatus());
        assertNull(driver.getClaimedByRequestId());
        assertEquals("req-1", driver.getLastReleasedByRequestId());
    }

    @Test
    @DisplayName("Transitions are idempotent when called repeatedly by the same owner")
    void transitions_areIdempotent() {
        DriverState driver = new DriverState();

        assertTrue(driver.tryClaim("req-1"));

        // Repeated transitionToEnRoute
        assertTrue(driver.transitionToEnRoute("req-1"));
        assertTrue(driver.transitionToEnRoute("req-1"));
        assertEquals(MatchStatus.EN_ROUTE, driver.getStatus());

        // Repeated transitionToOnTrip
        assertTrue(driver.transitionToOnTrip("req-1"));
        assertTrue(driver.transitionToOnTrip("req-1"));
        assertEquals(MatchStatus.ON_TRIP, driver.getStatus());

        // Repeated completeTrip
        assertTrue(driver.completeTrip("req-1"));
        assertTrue(driver.completeTrip("req-1"));
        assertEquals(MatchStatus.AVAILABLE, driver.getStatus());
    }

    @Test
    @DisplayName("Transitions reject unauthorized callers (ownership check)")
    void transitions_enforceOwnership() {
        DriverState driver = new DriverState();
        assertTrue(driver.tryClaim("owner-req"));

        assertFalse(driver.transitionToEnRoute("intruder-req"));
        assertEquals(MatchStatus.CLAIMED, driver.getStatus());

        assertTrue(driver.transitionToEnRoute("owner-req"));

        assertFalse(driver.transitionToOnTrip("intruder-req"));
        assertEquals(MatchStatus.EN_ROUTE, driver.getStatus());

        assertTrue(driver.transitionToOnTrip("owner-req"));

        assertFalse(driver.completeTrip("intruder-req"));
        assertEquals(MatchStatus.ON_TRIP, driver.getStatus());

        assertFalse(driver.cancelTrip("intruder-req"));
        assertEquals(MatchStatus.ON_TRIP, driver.getStatus());
    }

    @Test
    @DisplayName("Contradictory transitions are rejected")
    void contradictoryTransitions_areRejected() {
        DriverState driver = new DriverState();
        assertTrue(driver.tryClaim("req-1"));

        // Cannot skip EN_ROUTE and go straight to ON_TRIP
        assertFalse(driver.transitionToOnTrip("req-1"));
        assertEquals(MatchStatus.CLAIMED, driver.getStatus());

        // Advance to EN_ROUTE
        assertTrue(driver.transitionToEnRoute("req-1"));

        // Cannot regress back to CLAIMED or re-claim
        assertFalse(driver.tryClaim("req-1"));
        assertFalse(driver.tryClaim("req-2"));

        // Advance to ON_TRIP
        assertTrue(driver.transitionToOnTrip("req-1"));

        // Cannot go backwards from ON_TRIP to EN_ROUTE
        assertFalse(driver.transitionToEnRoute("req-1"));
        assertEquals(MatchStatus.ON_TRIP, driver.getStatus());
    }

    @Test
    @DisplayName("Cancellation is allowed from CLAIMED state")
    void cancelTrip_fromClaimed_succeeds() {
        DriverState driver = new DriverState();
        assertTrue(driver.tryClaim("req-1"));

        assertTrue(driver.cancelTrip("req-1"));
        assertEquals(MatchStatus.AVAILABLE, driver.getStatus());
        assertNull(driver.getClaimedByRequestId());
        assertEquals("req-1", driver.getLastReleasedByRequestId());

        // Idempotent cancellation
        assertTrue(driver.cancelTrip("req-1"));
    }

    @Test
    @DisplayName("Cancellation is allowed from EN_ROUTE state")
    void cancelTrip_fromEnRoute_succeeds() {
        DriverState driver = new DriverState();
        assertTrue(driver.tryClaim("req-1"));
        assertTrue(driver.transitionToEnRoute("req-1"));

        assertTrue(driver.cancelTrip("req-1"));
        assertEquals(MatchStatus.AVAILABLE, driver.getStatus());
        assertNull(driver.getClaimedByRequestId());
        assertEquals("req-1", driver.getLastReleasedByRequestId());

        // Idempotent cancellation
        assertTrue(driver.cancelTrip("req-1"));
    }

    @Test
    @DisplayName("Cancellation is STRICTLY DISALLOWED once ON_TRIP (after pickup)")
    void cancelTrip_fromOnTrip_isDisallowed() {
        DriverState driver = new DriverState();
        assertTrue(driver.tryClaim("req-1"));
        assertTrue(driver.transitionToEnRoute("req-1"));
        assertTrue(driver.transitionToOnTrip("req-1"));

        // Cancellation must fail!
        assertFalse(driver.cancelTrip("req-1"));
        assertEquals(MatchStatus.ON_TRIP, driver.getStatus());
        assertEquals("req-1", driver.getClaimedByRequestId());
    }

    @Test
    @DisplayName("Repeated cancellation cannot release a driver who has since been claimed by another request")
    void repeatedCancellation_doesNotReleaseNewClaimant() {
        DriverState driver = new DriverState();

        // 1. Req-1 claims and then cancels
        assertTrue(driver.tryClaim("req-1"));
        assertTrue(driver.cancelTrip("req-1"));
        assertEquals(MatchStatus.AVAILABLE, driver.getStatus());

        // 2. Req-2 claims the now-available driver
        assertTrue(driver.tryClaim("req-2"));
        assertEquals(MatchStatus.CLAIMED, driver.getStatus());
        assertEquals("req-2", driver.getClaimedByRequestId());

        // 3. Stale/delayed/duplicate cancellation for Req-1 arrives!
        assertFalse(driver.cancelTrip("req-1"));

        // Invariant: driver MUST remain claimed by req-2
        assertEquals(MatchStatus.CLAIMED, driver.getStatus());
        assertEquals("req-2", driver.getClaimedByRequestId());

        // 4. Repeated releaseClaim for Req-1 also fails to disturb req-2
        assertFalse(driver.releaseClaim("req-1"));
        assertEquals(MatchStatus.CLAIMED, driver.getStatus());
        assertEquals("req-2", driver.getClaimedByRequestId());
    }

    @Test
    @DisplayName("Repeated completeTrip cannot release a driver who has since been claimed by another request")
    void repeatedCompletion_doesNotReleaseNewClaimant() {
        DriverState driver = new DriverState();

        // 1. Req-1 completes full lifecycle
        assertTrue(driver.tryClaim("req-1"));
        assertTrue(driver.transitionToEnRoute("req-1"));
        assertTrue(driver.transitionToOnTrip("req-1"));
        assertTrue(driver.completeTrip("req-1"));
        assertEquals(MatchStatus.AVAILABLE, driver.getStatus());

        // 2. Req-2 claims the driver
        assertTrue(driver.tryClaim("req-2"));

        // 3. Duplicate completeTrip for req-1 arrives!
        assertFalse(driver.completeTrip("req-1"));
        assertEquals(MatchStatus.CLAIMED, driver.getStatus());
        assertEquals("req-2", driver.getClaimedByRequestId());
    }

    @Test
    @DisplayName("restoreActiveTrip correctly sets state on AVAILABLE driver")
    void restoreActiveTrip_setsStateOnAvailableDriver() {
        DriverState driver1 = new DriverState();
        assertTrue(driver1.restoreActiveTrip("req-assigned", TripStatus.ASSIGNED));
        assertEquals(MatchStatus.CLAIMED, driver1.getStatus());
        assertEquals("req-assigned", driver1.getClaimedByRequestId());

        DriverState driver2 = new DriverState();
        assertTrue(driver2.restoreActiveTrip("req-enroute", TripStatus.EN_ROUTE));
        assertEquals(MatchStatus.EN_ROUTE, driver2.getStatus());
        assertEquals("req-enroute", driver2.getClaimedByRequestId());

        DriverState driver3 = new DriverState();
        assertTrue(driver3.restoreActiveTrip("req-ontrip", TripStatus.IN_PROGRESS));
        assertEquals(MatchStatus.ON_TRIP, driver3.getStatus());
        assertEquals("req-ontrip", driver3.getClaimedByRequestId());

        // Terminal states cannot be restored
        DriverState driver4 = new DriverState();
        assertFalse(driver4.restoreActiveTrip("req-comp", TripStatus.COMPLETED));
        assertFalse(driver4.restoreActiveTrip("req-canc", TripStatus.CANCELLED));
        assertEquals(MatchStatus.AVAILABLE, driver4.getStatus());
    }

    @Test
    @DisplayName("restoreActiveTrip is idempotent for same request and target status")
    void restoreActiveTrip_isIdempotent() {
        DriverState driver = new DriverState();
        assertTrue(driver.restoreActiveTrip("req-1", TripStatus.EN_ROUTE));
        assertTrue(driver.restoreActiveTrip("req-1", TripStatus.EN_ROUTE));
        assertEquals(MatchStatus.EN_ROUTE, driver.getStatus());
    }

    @Test
    @DisplayName("restoreActiveTrip rejects conflicting request when driver is already claimed")
    void restoreActiveTrip_rejectsConflictingRequest() {
        DriverState driver = new DriverState();
        assertTrue(driver.tryClaim("active-req"));

        assertFalse(driver.restoreActiveTrip("other-req", TripStatus.EN_ROUTE));
        assertEquals(MatchStatus.CLAIMED, driver.getStatus());
        assertEquals("active-req", driver.getClaimedByRequestId());
    }
}
