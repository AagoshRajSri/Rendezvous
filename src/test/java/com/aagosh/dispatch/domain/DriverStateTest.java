package com.aagosh.dispatch.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DriverStateTest {

    @Test
    void startsAvailable() {
        DriverState driver = new DriverState();

        assertEquals(MatchStatus.AVAILABLE, driver.getStatus());
        assertNull(driver.getClaimedByRequestId());
    }

    @Test
    void onlyFirstClaimSucceeds() {
        DriverState driver = new DriverState();

        assertTrue(driver.tryClaim("request-1"));
        assertFalse(driver.tryClaim("request-2"));

        assertEquals(MatchStatus.CLAIMED, driver.getStatus());
        assertEquals("request-1", driver.getClaimedByRequestId());
    }
}
