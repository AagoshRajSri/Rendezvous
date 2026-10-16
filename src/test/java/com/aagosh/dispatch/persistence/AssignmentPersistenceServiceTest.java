package com.aagosh.dispatch.persistence;

import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.RideRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import com.aagosh.dispatch.domain.TripStatus;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssignmentPersistenceServiceTest {

    private RideRequestRepository rideRequestRepository;
    private AssignmentRepository assignmentRepository;
    private TripRepository tripRepository;
    private AssignmentPersistenceService service;

    @BeforeEach
    void setUp() {
        rideRequestRepository = mock(RideRequestRepository.class);
        assignmentRepository  = mock(AssignmentRepository.class);
        tripRepository        = mock(TripRepository.class);
        service = new AssignmentPersistenceService(rideRequestRepository, assignmentRepository, tripRepository);
    }

    private RideRequest sampleRequest(String id) {
        return new RideRequest(id, new GeoPoint(17.3850, 78.4867));
    }

    private Assignment sampleAssignment(String reqId, String driverId, double cost) {
        DriverState state = new DriverState();
        state.tryClaim(reqId);
        Driver driver = new Driver(driverId, new GeoPoint(17.3860, 78.4870), state);
        return new Assignment(sampleRequest(reqId), driver, cost);
    }

    @Test
    @DisplayName("1. persistMatchedAssignment creates new RideRequestEntity with MATCHED, saves AssignmentEntity and TripEntity(ASSIGNED)")
    void persistMatchedAssignment_whenRequestNew_createsBothEntities() {
        RideRequest request = sampleRequest("REQ-100");
        Assignment assignment = sampleAssignment("REQ-100", "D-1", 1.50);

        when(rideRequestRepository.findByRequestId("REQ-100")).thenReturn(Optional.empty());

        service.persistMatchedAssignment(request, assignment);

        ArgumentCaptor<RideRequestEntity> reqCaptor = ArgumentCaptor.forClass(RideRequestEntity.class);
        verify(rideRequestRepository).saveAndFlush(reqCaptor.capture());
        assertEquals("REQ-100", reqCaptor.getValue().getRequestId());
        assertEquals(AssignmentPersistenceService.STATUS_MATCHED, reqCaptor.getValue().getStatus());

        ArgumentCaptor<AssignmentEntity> assignCaptor = ArgumentCaptor.forClass(AssignmentEntity.class);
        verify(assignmentRepository).saveAndFlush(assignCaptor.capture());
        assertEquals("REQ-100", assignCaptor.getValue().getRequestId());
        assertEquals("D-1", assignCaptor.getValue().getDriverId());
        assertEquals(1.50, assignCaptor.getValue().getCost(), 1e-6);

        ArgumentCaptor<TripEntity> tripCaptor = ArgumentCaptor.forClass(TripEntity.class);
        verify(tripRepository).saveAndFlush(tripCaptor.capture());
        assertEquals("REQ-100", tripCaptor.getValue().getRequestId());
        assertEquals("D-1", tripCaptor.getValue().getDriverId());
        assertEquals(TripStatus.ASSIGNED, tripCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("2. persistMatchedAssignment updates existing pending RideRequestEntity to MATCHED and saves TripEntity")
    void persistMatchedAssignment_whenRequestExists_updatesStatus() {
        RideRequest request = sampleRequest("REQ-101");
        Assignment assignment = sampleAssignment("REQ-101", "D-2", 2.0);

        RideRequestEntity existing = new RideRequestEntity("REQ-101", 17.3850, 78.4867, AssignmentPersistenceService.STATUS_PENDING);
        when(rideRequestRepository.findByRequestId("REQ-101")).thenReturn(Optional.of(existing));

        service.persistMatchedAssignment(request, assignment);

        verify(rideRequestRepository).saveAndFlush(existing);
        assertEquals(AssignmentPersistenceService.STATUS_MATCHED, existing.getStatus());
        verify(assignmentRepository).saveAndFlush(any(AssignmentEntity.class));
        verify(tripRepository).saveAndFlush(any(TripEntity.class));
    }

    @Test
    @DisplayName("3. persistMatchedAssignment propagates database exceptions for transaction rollback")
    void persistMatchedAssignment_propagatesException() {
        RideRequest request = sampleRequest("REQ-102");
        Assignment assignment = sampleAssignment("REQ-102", "D-3", 0.75);

        when(rideRequestRepository.findByRequestId("REQ-102")).thenReturn(Optional.empty());
        when(assignmentRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThrows(DataIntegrityViolationException.class, () ->
                service.persistMatchedAssignment(request, assignment));
    }

    @Test
    @DisplayName("4. persistNoDrivers creates RideRequestEntity with NO_DRIVERS status")
    void persistNoDrivers_createsEntityWithNoDrivers() {
        RideRequest request = sampleRequest("REQ-103");
        when(rideRequestRepository.findByRequestId("REQ-103")).thenReturn(Optional.empty());

        service.persistNoDrivers(request);

        ArgumentCaptor<RideRequestEntity> reqCaptor = ArgumentCaptor.forClass(RideRequestEntity.class);
        verify(rideRequestRepository).saveAndFlush(reqCaptor.capture());
        assertEquals("REQ-103", reqCaptor.getValue().getRequestId());
        assertEquals(AssignmentPersistenceService.STATUS_NO_DRIVERS, reqCaptor.getValue().getStatus());
        verify(assignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("5. isRequestFinalized returns true for MATCHED and NO_DRIVERS, false for PENDING or missing")
    void isRequestFinalized_checksDurableTerminalStatus() {
        when(rideRequestRepository.findByRequestId("REQ-M"))
                .thenReturn(Optional.of(new RideRequestEntity("REQ-M", 0, 0, AssignmentPersistenceService.STATUS_MATCHED)));
        when(rideRequestRepository.findByRequestId("REQ-N"))
                .thenReturn(Optional.of(new RideRequestEntity("REQ-N", 0, 0, AssignmentPersistenceService.STATUS_NO_DRIVERS)));
        when(rideRequestRepository.findByRequestId("REQ-P"))
                .thenReturn(Optional.of(new RideRequestEntity("REQ-P", 0, 0, AssignmentPersistenceService.STATUS_PENDING)));
        when(rideRequestRepository.findByRequestId("REQ-X"))
                .thenReturn(Optional.empty());

        assertTrue(service.isRequestFinalized("REQ-M"));
        assertTrue(service.isRequestFinalized("REQ-N"));
        assertFalse(service.isRequestFinalized("REQ-P"));
        assertFalse(service.isRequestFinalized("REQ-X"));
    }

    @Test
    @DisplayName("6. findAssignment and isAssignmentPersisted delegate to repository")
    void assignmentQueries_delegateToRepository() {
        AssignmentEntity entity = new AssignmentEntity("REQ-QRY", "D-1", 1.0);
        when(assignmentRepository.findByRequestId("REQ-QRY")).thenReturn(Optional.of(entity));
        when(assignmentRepository.existsByRequestId("REQ-QRY")).thenReturn(true);
        when(assignmentRepository.existsByRequestId("REQ-MISS")).thenReturn(false);

        assertEquals(Optional.of(entity), service.findAssignment("REQ-QRY"));
        assertTrue(service.isAssignmentPersisted("REQ-QRY"));
        assertFalse(service.isAssignmentPersisted("REQ-MISS"));
    }

    @Test
    @DisplayName("7. DriverState releaseClaim releases only matching claim and is thread-safe CAS")
    void driverState_releaseClaimOwnershipProtection() {
        DriverState state = new DriverState();
        assertTrue(state.tryClaim("REQ-A"));
        assertEquals(MatchStatus.CLAIMED, state.getStatus());

        // Releasing by another request must fail
        assertFalse(state.releaseClaim("REQ-B"));
        assertEquals(MatchStatus.CLAIMED, state.getStatus());

        // Releasing by owner succeeds
        assertTrue(state.releaseClaim("REQ-A"));
        assertEquals(MatchStatus.AVAILABLE, state.getStatus());

        // Second release is no-op
        assertFalse(state.releaseClaim("REQ-A"));
        assertEquals(MatchStatus.AVAILABLE, state.getStatus());
    }

    @Test
    @DisplayName("8. findTrip and isTripPersisted delegate to tripRepository")
    void tripQueries_delegateToRepository() {
        TripEntity entity = new TripEntity("REQ-TRIP", "D-1", TripStatus.ASSIGNED);
        when(tripRepository.findByRequestId("REQ-TRIP")).thenReturn(Optional.of(entity));
        when(tripRepository.existsByRequestId("REQ-TRIP")).thenReturn(true);
        when(tripRepository.existsByRequestId("REQ-TRIP-MISS")).thenReturn(false);

        assertEquals(Optional.of(entity), service.findTrip("REQ-TRIP"));
        assertTrue(service.isTripPersisted("REQ-TRIP"));
        assertFalse(service.isTripPersisted("REQ-TRIP-MISS"));
    }

    @Test
    @DisplayName("9. persistBatch with mixed outcomes persists MATCHED, NO_DRIVERS, and flushes all repos")
    void persistBatch_mixedOutcomes_persistsMatchedAndNoDrivers() {
        RideRequest req1 = sampleRequest("REQ-1");
        RideRequest req2 = sampleRequest("REQ-2");
        RideRequest req3 = sampleRequest("REQ-3");

        Assignment a1 = sampleAssignment("REQ-1", "D-1", 1.25);
        Assignment a3 = sampleAssignment("REQ-3", "D-3", 2.50);

        when(rideRequestRepository.findByRequestId(any())).thenReturn(Optional.empty());
        when(assignmentRepository.existsByRequestId(any())).thenReturn(false);
        when(tripRepository.existsByRequestId(any())).thenReturn(false);

        AssignmentPersistenceService.BatchPersistResult result =
                service.persistBatch(List.of(req1, req2, req3), List.of(a1, a3));

        assertEquals(2, result.matchedPersisted());
        assertEquals(1, result.noDriversPersisted());
        assertEquals(0, result.duplicatesSkipped());

        // Verify ride_requests saved for all 3
        ArgumentCaptor<RideRequestEntity> reqCaptor = ArgumentCaptor.forClass(RideRequestEntity.class);
        verify(rideRequestRepository, times(3)).save(reqCaptor.capture());
        List<RideRequestEntity> savedReqs = reqCaptor.getAllValues();
        assertEquals("REQ-1", savedReqs.get(0).getRequestId());
        assertEquals(AssignmentPersistenceService.STATUS_MATCHED, savedReqs.get(0).getStatus());
        assertEquals("REQ-2", savedReqs.get(1).getRequestId());
        assertEquals(AssignmentPersistenceService.STATUS_NO_DRIVERS, savedReqs.get(1).getStatus());
        assertEquals("REQ-3", savedReqs.get(2).getRequestId());
        assertEquals(AssignmentPersistenceService.STATUS_MATCHED, savedReqs.get(2).getStatus());

        // Verify assignments saved only for matched requests (REQ-1, REQ-3)
        ArgumentCaptor<AssignmentEntity> assignCaptor = ArgumentCaptor.forClass(AssignmentEntity.class);
        verify(assignmentRepository, times(2)).save(assignCaptor.capture());
        assertEquals("REQ-1", assignCaptor.getAllValues().get(0).getRequestId());
        assertEquals("D-1", assignCaptor.getAllValues().get(0).getDriverId());
        assertEquals("REQ-3", assignCaptor.getAllValues().get(1).getRequestId());
        assertEquals("D-3", assignCaptor.getAllValues().get(1).getDriverId());

        // Verify trips saved only for matched requests
        ArgumentCaptor<TripEntity> tripCaptor = ArgumentCaptor.forClass(TripEntity.class);
        verify(tripRepository, times(2)).save(tripCaptor.capture());
        assertEquals("REQ-1", tripCaptor.getAllValues().get(0).getRequestId());
        assertEquals(TripStatus.ASSIGNED, tripCaptor.getAllValues().get(0).getStatus());
        assertEquals("REQ-3", tripCaptor.getAllValues().get(1).getRequestId());
        assertEquals(TripStatus.ASSIGNED, tripCaptor.getAllValues().get(1).getStatus());

        // Verify transactional flush
        verify(rideRequestRepository).flush();
        verify(assignmentRepository).flush();
        verify(tripRepository).flush();
    }

    @Test
    @DisplayName("10. persistBatch skips already finalized requests (durable idempotency)")
    void persistBatch_skipsAlreadyFinalizedRequests() {
        RideRequest reqFinalM = sampleRequest("REQ-FINAL-M");
        RideRequest reqFinalN = sampleRequest("REQ-FINAL-N");
        RideRequest reqFresh  = sampleRequest("REQ-FRESH");

        Assignment aFinalM = sampleAssignment("REQ-FINAL-M", "D-1", 1.0);
        Assignment aFresh  = sampleAssignment("REQ-FRESH", "D-2", 2.0);

        RideRequestEntity entityFinalM = new RideRequestEntity("REQ-FINAL-M", 0, 0, AssignmentPersistenceService.STATUS_MATCHED);
        RideRequestEntity entityFinalN = new RideRequestEntity("REQ-FINAL-N", 0, 0, AssignmentPersistenceService.STATUS_NO_DRIVERS);

        when(rideRequestRepository.findByRequestId("REQ-FINAL-M")).thenReturn(Optional.of(entityFinalM));
        when(rideRequestRepository.findByRequestId("REQ-FINAL-N")).thenReturn(Optional.of(entityFinalN));
        when(rideRequestRepository.findByRequestId("REQ-FRESH")).thenReturn(Optional.empty());
        when(assignmentRepository.existsByRequestId(any())).thenReturn(false);
        when(tripRepository.existsByRequestId(any())).thenReturn(false);

        AssignmentPersistenceService.BatchPersistResult result =
                service.persistBatch(List.of(reqFinalM, reqFinalN, reqFresh), List.of(aFinalM, aFresh));

        assertEquals(1, result.matchedPersisted());
        assertEquals(0, result.noDriversPersisted());
        assertEquals(2, result.duplicatesSkipped());

        // Assignment and trip saved only for REQ-FRESH
        ArgumentCaptor<AssignmentEntity> assignCaptor = ArgumentCaptor.forClass(AssignmentEntity.class);
        verify(assignmentRepository, times(1)).save(assignCaptor.capture());
        assertEquals("REQ-FRESH", assignCaptor.getValue().getRequestId());

        ArgumentCaptor<TripEntity> tripCaptor = ArgumentCaptor.forClass(TripEntity.class);
        verify(tripRepository, times(1)).save(tripCaptor.capture());
        assertEquals("REQ-FRESH", tripCaptor.getValue().getRequestId());
    }

    @Test
    @DisplayName("11. persistBatch processes entities in deterministic ascending requestId order")
    void persistBatch_deterministicAscendingOrder() {
        RideRequest reqZ = sampleRequest("REQ-Z");
        RideRequest reqA = sampleRequest("REQ-A");
        RideRequest reqM = sampleRequest("REQ-M");

        Assignment aZ = sampleAssignment("REQ-Z", "D-Z", 1.0);
        Assignment aA = sampleAssignment("REQ-A", "D-A", 1.0);

        when(rideRequestRepository.findByRequestId(any())).thenReturn(Optional.empty());
        when(assignmentRepository.existsByRequestId(any())).thenReturn(false);
        when(tripRepository.existsByRequestId(any())).thenReturn(false);

        // Input in reverse/unordered sequence
        service.persistBatch(List.of(reqZ, reqA, reqM), List.of(aZ, aA));

        ArgumentCaptor<RideRequestEntity> captor = ArgumentCaptor.forClass(RideRequestEntity.class);
        verify(rideRequestRepository, times(3)).save(captor.capture());
        List<String> order = captor.getAllValues().stream().map(RideRequestEntity::getRequestId).toList();
        assertEquals(List.of("REQ-A", "REQ-M", "REQ-Z"), order);
    }

    @Test
    @DisplayName("12. persistBatch propagates database exceptions to trigger transaction rollback")
    void persistBatch_propagatesDatabaseExceptionsForRollback() {
        RideRequest req = sampleRequest("REQ-ERR");
        Assignment a = sampleAssignment("REQ-ERR", "D-1", 1.0);

        when(rideRequestRepository.findByRequestId("REQ-ERR")).thenReturn(Optional.empty());
        when(assignmentRepository.save(any())).thenThrow(new DataIntegrityViolationException("Simulated constraint violation"));

        assertThrows(DataIntegrityViolationException.class, () ->
                service.persistBatch(List.of(req), List.of(a)));
    }

    @Test
    @DisplayName("13. reconcileAndCompensateClaims releases driver claims when DB rollback is confirmed")
    void reconcileAndCompensateClaims_releasesClaimsOnConfirmedRollback() {
        Assignment a1 = sampleAssignment("REQ-1", "D-1", 1.0);
        Assignment a2 = sampleAssignment("REQ-2", "D-2", 2.0);

        assertEquals(MatchStatus.CLAIMED, a1.driver().state().getStatus());
        assertEquals(MatchStatus.CLAIMED, a2.driver().state().getStatus());

        // Rollback confirmed: findAssignment returns empty for both
        when(assignmentRepository.findByRequestId(any())).thenReturn(Optional.empty());

        AssignmentPersistenceService.BatchCompensationResult comp =
                service.reconcileAndCompensateClaims(List.of(a1, a2));

        assertEquals(2, comp.totalAssignments());
        assertEquals(2, comp.claimsReleased());
        assertEquals(0, comp.claimsRetained());
        assertEquals(List.of("REQ-1", "REQ-2"), comp.releasedRequestIds());

        // In-memory claims safely compensated to AVAILABLE
        assertEquals(MatchStatus.AVAILABLE, a1.driver().state().getStatus());
        assertEquals(MatchStatus.AVAILABLE, a2.driver().state().getStatus());
    }

    @Test
    @DisplayName("14. reconcileAndCompensateClaims retains driver claim when ambiguous commit succeeded")
    void reconcileAndCompensateClaims_retainsClaimOnAmbiguousCommit() {
        Assignment a1 = sampleAssignment("REQ-1", "D-1", 1.0);
        Assignment a2 = sampleAssignment("REQ-2", "D-2", 2.0);

        // REQ-1 actually committed with D-1 despite error
        AssignmentEntity committedEntity = new AssignmentEntity("REQ-1", "D-1", 1.0);
        when(assignmentRepository.findByRequestId("REQ-1")).thenReturn(Optional.of(committedEntity));
        // REQ-2 truly rolled back
        when(assignmentRepository.findByRequestId("REQ-2")).thenReturn(Optional.empty());

        AssignmentPersistenceService.BatchCompensationResult comp =
                service.reconcileAndCompensateClaims(List.of(a1, a2));

        assertEquals(2, comp.totalAssignments());
        assertEquals(1, comp.claimsReleased());
        assertEquals(1, comp.claimsRetained());
        assertEquals(List.of("REQ-2"), comp.releasedRequestIds());
        assertEquals(List.of("REQ-1"), comp.retainedRequestIds());

        // D-1 claim must NOT be released!
        assertEquals(MatchStatus.CLAIMED, a1.driver().state().getStatus());
        // D-2 claim is safely released
        assertEquals(MatchStatus.AVAILABLE, a2.driver().state().getStatus());
    }

    @Test
    @DisplayName("15. reconcileAndCompensateClaims releases claim if assignment committed with a conflicting driver")
    void reconcileAndCompensateClaims_releasesClaimOnConflictingDriver() {
        Assignment a1 = sampleAssignment("REQ-1", "D-1", 1.0);

        // Competing process committed REQ-1 with another driver D-OTHER
        AssignmentEntity conflictingEntity = new AssignmentEntity("REQ-1", "D-OTHER", 0.8);
        when(assignmentRepository.findByRequestId("REQ-1")).thenReturn(Optional.of(conflictingEntity));

        AssignmentPersistenceService.BatchCompensationResult comp =
                service.reconcileAndCompensateClaims(List.of(a1));

        assertEquals(1, comp.claimsReleased());
        assertEquals(0, comp.claimsRetained());

        // Our claim on D-1 is released
        assertEquals(MatchStatus.AVAILABLE, a1.driver().state().getStatus());
    }

    @Test
    @DisplayName("16. persistBatch rejects duplicate assignments for same requestId within batch")
    void persistBatch_rejectsDuplicateAssignmentForSameRequestInBatch() {
        RideRequest req = sampleRequest("REQ-DUP");
        Assignment a1 = sampleAssignment("REQ-DUP", "D-1", 1.0);
        Assignment a2 = sampleAssignment("REQ-DUP", "D-2", 2.0);

        assertThrows(IllegalArgumentException.class, () ->
                service.persistBatch(List.of(req), List.of(a1, a2)));
    }

    @Test
    @DisplayName("17. reconcileAndCompensateClaims retains claim when DB lookup fails with exception (never assume rollback)")
    void reconcileAndCompensateClaims_retainsClaimOnDbLookupFailure() {
        Assignment a1 = sampleAssignment("REQ-ERR", "D-1", 1.0);
        assertEquals(MatchStatus.CLAIMED, a1.driver().state().getStatus());

        // Database lookup fails unexpectedly (e.g. timeout or connection drop)
        when(assignmentRepository.findByRequestId("REQ-ERR"))
                .thenThrow(new RuntimeException("Database connection timeout during reconciliation"));

        AssignmentPersistenceService.BatchCompensationResult comp =
                service.reconcileAndCompensateClaims(List.of(a1));

        assertEquals(1, comp.totalAssignments());
        assertEquals(0, comp.claimsReleased());
        assertEquals(1, comp.claimsRetained());
        assertEquals(List.of("REQ-ERR"), comp.retainedRequestIds());

        // In-memory claim is safely retained, NOT released!
        assertEquals(MatchStatus.CLAIMED, a1.driver().state().getStatus());
    }
}
