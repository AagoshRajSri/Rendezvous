package com.aagosh.dispatch.persistence;

import com.aagosh.dispatch.domain.TripStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity mirroring the {@code trips} table.
 *
 * <p>Tracks the operational lifecycle of a ride after assignment:
 * {@link TripStatus#ASSIGNED}, {@link TripStatus#EN_ROUTE},
 * {@link TripStatus#IN_PROGRESS}, {@link TripStatus#COMPLETED},
 * or {@link TripStatus#CANCELLED}.
 *
 * <p>The {@code requestId} column carries a {@code UNIQUE} constraint matching
 * the current no-reassignment scope (at most one trip entity per ride request).
 */
@Entity
@Table(name = "trips")
public class TripEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true, length = 255)
    private String requestId;

    @Column(name = "driver_id", nullable = false, length = 255)
    private String driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TripStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected TripEntity() {}

    public TripEntity(String requestId, String driverId, TripStatus status) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.driverId  = Objects.requireNonNull(driverId, "driverId");
        this.status    = Objects.requireNonNull(status, "status");
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    // ---- Accessors ----

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getDriverId() {
        return driverId;
    }

    public TripStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(TripStatus status) {
        this.status    = Objects.requireNonNull(status, "status");
        this.updatedAt = Instant.now();
    }
}
