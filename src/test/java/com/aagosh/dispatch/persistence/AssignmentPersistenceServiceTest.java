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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssignmentPersistenceServiceTest {

    private RideRequestRepository rideRequestRepository;
    private AssignmentRepository assignmentRepository;
    private AssignmentPersistenceService service;

    @BeforeEach
    void setUp() {
        rideRequestRepository = mock(RideRequestRepository.class);
        assignmentRepository  = mock(AssignmentRepository.class);
        service = new AssignmentPersistenceService(rideRequestRepository, assignmentRepository);
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
    @DisplayName("1. persistMatchedAssignment creates new RideRequestEntity with MATCHED and saves AssignmentEntity")
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
    }

    @Test
    @DisplayName("2. persistMatchedAssignment updates existing pending RideRequestEntity to MATCHED")
    void persistMatchedAssignment_whenRequestExists_updatesStatus() {
        RideRequest request = sampleRequest("REQ-101");
        Assignment assignment = sampleAssignment("REQ-101", "D-2", 2.0);

        RideRequestEntity existing = new RideRequestEntity("REQ-101", 17.3850, 78.4867, AssignmentPersistenceService.STATUS_PENDING);
        when(rideRequestRepository.findByRequestId("REQ-101")).thenReturn(Optional.of(existing));

        service.persistMatchedAssignment(request, assignment);

        verify(rideRequestRepository).saveAndFlush(existing);
        assertEquals(AssignmentPersistenceService.STATUS_MATCHED, existing.getStatus());
        verify(assignmentRepository).saveAndFlush(any(AssignmentEntity.class));
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
}
