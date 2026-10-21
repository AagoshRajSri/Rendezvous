package com.aagosh.dispatch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity mirroring the {@code assignments} table.
 *
 * <p>One row is inserted for every successful driver match.  The
 * {@code requestId} column has a {@code UNIQUE} database constraint, so a
 * duplicate Kafka re-delivery that reaches the insert step after a
 * crash-between-commit-and-ack will produce a
 * {@link org.springframework.dao.DataIntegrityViolationException} rather than
 * a second assignment row.
 */
@Entity
@Table(name = "assignments")
public class AssignmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The logical request identifier - unique within this table and a FK to
     * {@code ride_requests.request_id}.
     */
    @Column(name = "request_id", nullable = false, unique = true, length = 255)
    private String requestId;

    /**
     * The logical driver identifier from the in-memory {@code DriverRegistry}.
     * Not a FK to a drivers table (out of scope for this phase).
     */
    @Column(name = "driver_id", nullable = false, length = 255)
    private String driverId;

    /** Matching cost as computed by the configured {@link com.aagosh.dispatch.matching.CostFunction}. */
    @Column(name = "cost", nullable = false)
    private double cost;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    /** Required by JPA. */
    protected AssignmentEntity() {}

    public AssignmentEntity(String requestId, String driverId, double cost) {
        this.requestId  = requestId;
        this.driverId   = driverId;
        this.cost       = cost;
        this.assignedAt = Instant.now();
    }

    // ---- Accessors ----

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getDriverId() { return driverId; }
    public double getCost() { return cost; }
    public Instant getAssignedAt() { return assignedAt; }
}
