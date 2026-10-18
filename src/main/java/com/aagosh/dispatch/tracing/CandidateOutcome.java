package com.aagosh.dispatch.tracing;

/**
 * Lifecycle and evaluation outcome of a candidate driver evaluated for a ride request.
 */
public enum CandidateOutcome {
    /**
     * Candidate selected as the winning match, claimed atomically, and assigned to the request.
     */
    MATCHED,

    /**
     * Candidate evaluated, but ranked lower in cost or utility than the winning driver.
     */
    OUTCOMPETED,

    /**
     * Candidate driver was discovered in the spatial/fleet index but was not in AVAILABLE status
     * (e.g. already claimed by another request, en route, or on trip).
     */
    UNAVAILABLE,

    /**
     * Candidate driver was selected by the matcher, but the atomic claim (CAS via tryClaim)
     * failed due to concurrent competition from another request.
     */
    CLAIM_LOST,

    /**
     * Search or auction terminated without assigning this candidate (e.g. fleet exhausted or dummy).
     */
    UNMATCHED
}
