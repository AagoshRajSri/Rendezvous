package com.aagosh.dispatch.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity mirroring the {@code ride_requests} table.
 *
 * <p>One row is inserted for every ride-request event consumed from Kafka or
 * accepted at the API boundary with an {@code Idempotency-Key}.
 * The {@code requestId} column carries a {@code UNIQUE} database constraint,
 * and {@code idempotencyKey} carries a {@code UNIQUE} constraint across non-null
 * values, which acts as the durable API-level idempotency guard.
 *
 * <p>Status values: {@code PENDING} (no driver matched yet), {@code MATCHED}
 * (an assignment row exists), {@code NO_DRIVER} / {@code NO_DRIVERS} (no available
 * driver at the time of processing).
 */
@Entity
@Table(name = "ride_requests")
public class RideRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The logical request identifier from the Kafka event.
     * Must be unique across the table - used as the durable idempotency key.
     */
    @Column(name = "request_id", nullable = false, unique = true, length = 255)
    private String requestId;

    @Column(name = "pickup_lat", nullable = false)
    private double pickupLat;

    @Column(name = "pickup_lon", nullable = false)
    private double pickupLon;

    /**
     * Processing outcome. One of {@code PENDING}, {@code MATCHED}, or {@code NO_DRIVER}.
     */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    /**
     * Client-supplied idempotency key (header {@code Idempotency-Key}).
     * Unique across all non-null rows in PostgreSQL.
     */
    @Column(name = "idempotency_key", unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected RideRequestEntity() {}

    public RideRequestEntity(String requestId, double pickupLat, double pickupLon, String status) {
        this(requestId, pickupLat, pickupLon, status, null);
    }

    public RideRequestEntity(String requestId, double pickupLat, double pickupLon, String status, String idempotencyKey) {
        this.requestId      = requestId;
        this.pickupLat      = pickupLat;
        this.pickupLon      = pickupLon;
        this.status         = status;
        this.idempotencyKey = idempotencyKey;
        this.receivedAt     = Instant.now();
        this.updatedAt      = this.receivedAt;
    }

    // ---- Accessors ----

    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public double getPickupLat() { return pickupLat; }
    public double getPickupLon() { return pickupLon; }
    public String getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(String status) {
        this.status    = status;
        this.updatedAt = Instant.now();
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        this.updatedAt      = Instant.now();
    }
}
