package com.aagosh.dispatch.messaging;

import com.aagosh.dispatch.api.DriverPingRequest;
import com.aagosh.dispatch.api.PingAction;
import com.aagosh.dispatch.cache.RedisLocationCache;
import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import com.aagosh.dispatch.domain.MatchStatus;
import com.aagosh.dispatch.domain.TripStatus;
import com.aagosh.dispatch.persistence.TripRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Kafka consumer for driver location telemetry and registration events.
 *
 * <h2>Topic</h2>
 * <p>Listens on {@value KafkaTopics#DRIVER_PINGS}. The consumer group is configured in
 * {@code application.properties} as {@code dispatch-service}.
 *
 * <h2>Event semantics</h2>
 * <p>Each consumed {@link DriverPingRequest} is processed according to its {@code action}
 * field using the same logic as the pre-Kafka {@code DriverController}:
 * <ul>
 *   <li>{@link PingAction#REGISTER}: registers the driver if absent; logs a warning and
 *       acknowledges on duplicate (the driver already exists - this is idempotent).</li>
 *   <li>{@link PingAction#UPDATE}: updates the driver's location. Logs a warning and
 *       acknowledges if the driver is not found (registration may not have been processed yet
 *       due to at-least-once reordering). Does <em>not</em> reset claim state.</li>
 *   <li>{@link PingAction#AUTO} (or absent): registers if new; updates location if
 *       already registered. Preserves claim state. Safe to retry.</li>
 * </ul>
 *
 * <h2>Driver State Recovery</h2>
 * <p>Drivers are maintained in-memory in {@link DriverRegistry}. When a driver registers or
 * reconnects after a service restart or reconnection, {@link #recoverDriverStateIfActive(String)}
 * lazily queries PostgreSQL for any active trip ({@code ASSIGNED}, {@code EN_ROUTE}, or {@code IN_PROGRESS})
 * and restores the in-memory {@link DriverState} claim. This avoids any blocking scan of trip state
 * during application startup and does not assume driver rows exist in PostgreSQL.
 *
 * <h2>Timestamp handling and ordering guarantees</h2>
 * <p>Location ordering is resolved by {@link #resolveTimestamp(DriverPingRequest, long)} in priority order:
 * <ol>
 *   <li><strong>Client-provided {@code capturedAt}</strong> (preferred) - epoch milliseconds indicating
 *       when the GPS fix was acquired on the mobile device.</li>
 *   <li><strong>Kafka record timestamp</strong> (best-effort fallback) - used when {@code capturedAt}
 *       is absent or non-positive. <em>Limitation:</em> This timestamp reflects broker append or producer
 *       transmission time, <strong>not</strong> GPS capture time. Under network delays, client reconnection,
 *       or producer retries, records may reach Kafka out of chronological order. Chronological ordering
 *       for legacy events relying on this fallback is therefore strictly best-effort.</li>
 *   <li><strong>{@code 0L} (unversioned)</strong> - used when neither client {@code capturedAt} nor
 *       a positive Kafka record timestamp is present. Indicates that no chronological ordering is available.
 *       An unversioned event can update a driver in the local {@link DriverRegistry} only while the driver
 *       remains unversioned ({@code updatedAt == 0L}); it <em>cannot</em> overwrite a location that has
 *       already been updated with a known positive timestamp. Unversioned events are not written to Redis.</li>
 * </ol>
 *
 * <h2>At-least-once delivery and idempotency</h2>
 * <p>Driver ping events are naturally idempotent:
 * <ul>
 *   <li>A duplicate REGISTER event for an already-registered driver is logged and
 *       acknowledged without resetting state.</li>
 *   <li>Duplicate or out-of-order location UPDATE events are rejected by the
 *       {@code DriverRegistry}'s timestamp-monotone CAS: only strictly newer coordinates
 *       replace stored ones.</li>
 *   <li>AUTO events behave identically across retries.</li>
 * </ul>
 *
 * <h2>Redis location cache</h2>
 * <p>After the local {@code DriverRegistry} accepts a location update, the new coordinates
 * are written to Redis via {@link RedisLocationCache}. Redis is a non-authoritative cache
 * and its write is best-effort: any Redis failure is logged and swallowed without affecting
 * Kafka offset acknowledgement.
 *
 * <p>A location update rejected by the local registry (stale timestamp) is also not written
 * to Redis, ensuring the cache never holds older data than the registry.
 *
 * <h2>Acknowledgement</h2>
 * <p>Offset acknowledgement ({@link Acknowledgment#acknowledge()}) is called after
 * successful processing. If the method throws an exception, the error handler defined
 * in {@code KafkaConfig} retries the record up to twice at 1-second intervals before
 * skipping it and advancing the offset.
 *
 * <h2>Claim preservation invariant</h2>
 * <p>This consumer <em>never</em> creates a new {@link DriverState} for an existing
 * driver. All update paths reuse the existing {@link DriverState} via
 * {@link DriverRegistry#updateLocation(String, GeoPoint, long)}.
 */
@Component
public class DriverPingConsumer {

    private static final Logger log = LoggerFactory.getLogger(DriverPingConsumer.class);

    private static final List<TripStatus> ACTIVE_TRIP_STATUSES =
            List.of(TripStatus.ASSIGNED, TripStatus.EN_ROUTE, TripStatus.IN_PROGRESS);

    private final DriverRegistry     driverRegistry;
    private final RedisLocationCache redisLocationCache;
    private final TripRepository     tripRepository;

    @Autowired
    public DriverPingConsumer(DriverRegistry driverRegistry,
                              RedisLocationCache redisLocationCache,
                              @Autowired(required = false) TripRepository tripRepository) {
        this.driverRegistry     = Objects.requireNonNull(driverRegistry,     "driverRegistry");
        this.redisLocationCache = Objects.requireNonNull(redisLocationCache, "redisLocationCache");
        this.tripRepository     = tripRepository;
    }

    public DriverPingConsumer(DriverRegistry driverRegistry,
                              RedisLocationCache redisLocationCache) {
        this(driverRegistry, redisLocationCache, null);
    }

    /**
     * Processes a single driver ping event from the {@code driver-pings} topic.
     *
     * @param record the Kafka record; payload is a {@link DriverPingRequest}
     * @param ack    manual acknowledgement handle; called on success
     */
    @KafkaListener(
            topics = KafkaTopics.DRIVER_PINGS,
            groupId = "${spring.kafka.consumer.group-id:dispatch-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onDriverPing(ConsumerRecord<String, DriverPingRequest> record, Acknowledgment ack) {
        DriverPingRequest event = record.value();

        if (event == null) {
            String errorDetail = extractDeserializationError(record);
            log.warn("Received null or unparseable driver ping record at topic={} partition={} offset={} key={}{}; acknowledging and skipping",
                    record.topic(), record.partition(), record.offset(), record.key(),
                    errorDetail != null ? " (" + errorDetail + ")" : "");
            ack.acknowledge();
            return;
        }

        // Re-validate to guard against malformed events published outside the REST layer.
        try {
            event.validate();
        } catch (Exception ex) {
            log.error("Invalid driver ping event at topic={} partition={} offset={} key={}: {}",
                    record.topic(), record.partition(), record.offset(), record.key(), ex.getMessage());
            // Acknowledge and skip: retrying invalid payloads will not help.
            ack.acknowledge();
            return;
        }

        // Resolve the best available location timestamp:
        //  1. Client-provided capturedAt (preferred - reflects actual GPS fix time).
        //  2. Kafka broker-append timestamp (fallback - see class Javadoc for limitations).
        long timestamp = resolveTimestamp(event, record.timestamp());

        GeoPoint location = new GeoPoint(event.latitude(), event.longitude());
        PingAction action = event.resolveAction();

        try {
            switch (action) {
                case REGISTER -> processRegister(event.driverId(), location, timestamp, record);
                case UPDATE   -> processUpdate(event.driverId(), location, timestamp, record);
                case AUTO     -> processAuto(event.driverId(), location, timestamp, record);
            }
            ack.acknowledge();
        } catch (Exception ex) {
            // Let the error handler retry; do NOT acknowledge.
            log.error("Failed to process driver ping for driverId={} at topic={} partition={} offset={} key={}: {}",
                    event.driverId(), record.topic(), record.partition(), record.offset(), record.key(), ex.getMessage(), ex);
            throw ex;
        }
    }

    // -------------------------------------------------------------------------
    // Action handlers
    // -------------------------------------------------------------------------

    private void processRegister(String driverId, GeoPoint location, long timestamp,
                                 ConsumerRecord<?, ?> record) {
        DriverState state = createOrRecoverState(driverId);
        try {
            driverRegistry.register(new Driver(driverId, location, state, timestamp));
            log.debug("Registered driver {} via consumer (partition={} offset={})",
                    driverId, record.partition(), record.offset());
            // Write initial location to Redis for newly registered drivers.
            updateRedisCacheSafe(driverId, location, timestamp);
        } catch (IllegalStateException ex) {
            // Driver already registered - ensure state recovery if currently AVAILABLE
            recoverDriverStateIfActive(driverId);
            log.warn("Duplicate REGISTER event for already-registered driver {}; acknowledging",
                    driverId);
        }
    }

    private void processUpdate(String driverId, GeoPoint location, long timestamp,
                               ConsumerRecord<?, ?> record) {
        try {
            boolean accepted = driverRegistry.updateLocation(driverId, location, timestamp);
            if (accepted) {
                log.debug("Updated location for driver {} via consumer (partition={} offset={})",
                        driverId, record.partition(), record.offset());
                // Only write to Redis if the local registry accepted the update.
                updateRedisCacheSafe(driverId, location, timestamp);
            } else {
                log.debug("Stale location update rejected for driver {} (timestamp={}); skipping Redis write",
                        driverId, timestamp);
            }
        } catch (IllegalStateException ex) {
            // Driver not found. This can happen if a REGISTER event has not been processed yet
            // (e.g., partition reassignment or consumer restart). Log and acknowledge;
            // a subsequent ping will converge the registry.
            log.warn("UPDATE event for unknown driver {}; acknowledging without retry", driverId);
        }
    }

    private void processAuto(String driverId, GeoPoint location, long timestamp,
                             ConsumerRecord<?, ?> record) {
        Optional<Driver> existing = driverRegistry.findById(driverId);
        if (existing.isPresent()) {
            recoverDriverStateIfActive(driverId);
            processUpdate(driverId, location, timestamp, record);
        } else {
            DriverState state = createOrRecoverState(driverId);
            try {
                driverRegistry.register(new Driver(driverId, location, state, timestamp));
                log.debug("Auto-registered driver {} via consumer (partition={} offset={})",
                        driverId, record.partition(), record.offset());
                updateRedisCacheSafe(driverId, location, timestamp);
            } catch (IllegalStateException ex) {
                // Race: another consumer thread registered between findById and register.
                recoverDriverStateIfActive(driverId);
                processUpdate(driverId, location, timestamp, record);
            }
        }
    }

    /**
     * Creates a {@link DriverState} for a newly registering driver, pre-populating
     * it with any active persistent trip BEFORE the driver is published to {@link DriverRegistry}.
     * This guarantees that a driver with an active trip is never exposed to concurrent matching
     * as {@code AVAILABLE}.
     */
    private DriverState createOrRecoverState(String driverId) {
        DriverState state = new DriverState();
        if (tripRepository != null) {
            try {
                tripRepository.findFirstByDriverIdAndStatusIn(driverId, ACTIVE_TRIP_STATUSES)
                        .ifPresent(trip -> {
                            boolean restored = state.restoreActiveTrip(trip.getRequestId(), trip.getStatus());
                            if (restored) {
                                log.info("Restored active trip {} for driver {} with status {} prior to registration",
                                        trip.getRequestId(), driverId, trip.getStatus());
                            }
                        });
            } catch (Exception ex) {
                log.warn("Failed to check active trip state before registering driver {}: {}", driverId, ex.getMessage());
            }
        }
        return state;
    }

    /**
     * Lazily recovers in-memory driver state from PostgreSQL if an active trip
     * (ASSIGNED, EN_ROUTE, or IN_PROGRESS) exists for this driver.
     *
     * <p>Only queries PostgreSQL if the driver is currently {@link MatchStatus#AVAILABLE}
     * in memory, avoiding redundant database lookups during normal operation.
     * Never overwrites an unrelated active claim.
     */
    private void recoverDriverStateIfActive(String driverId) {
        if (tripRepository == null) {
            return;
        }
        try {
            driverRegistry.findById(driverId).ifPresent(driver -> {
                if (driver.state().getStatus() == MatchStatus.AVAILABLE) {
                    tripRepository.findFirstByDriverIdAndStatusIn(driverId, ACTIVE_TRIP_STATUSES)
                            .ifPresent(trip -> {
                                boolean restored = driver.state().restoreActiveTrip(trip.getRequestId(), trip.getStatus());
                                if (restored) {
                                    log.info("Recovered active trip {} for driver {} with status {}",
                                            trip.getRequestId(), driverId, trip.getStatus());
                                }
                            });
                }
            });
        } catch (Exception ex) {
            log.warn("Failed to recover active trip state for driver {}: {}", driverId, ex.getMessage());
        }
    }

    private void updateRedisCacheSafe(String driverId, GeoPoint location, long timestamp) {
        if (timestamp <= 0L) {
            // Unversioned events (no client capturedAt and no positive broker timestamp)
            // are not written to Redis to ensure they cannot overwrite versioned cached locations.
            return;
        }
        try {
            redisLocationCache.updateLocation(driverId, location.latitude(), location.longitude(), timestamp);
        } catch (Exception ex) {
            log.warn("Redis location cache update failed for driver {} at timestamp {}: {}",
                    driverId, timestamp, ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Timestamp resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves the effective location timestamp for this ping event.
     *
     * @param event           the validated ping payload
     * @param kafkaTimestamp  the broker-append timestamp from the Kafka record (millis)
     * @return the client-provided {@code capturedAt} if present and valid, otherwise
     *         the Kafka record timestamp if positive, otherwise 0 (no ordering available)
     */
    static long resolveTimestamp(DriverPingRequest event, long kafkaTimestamp) {
        if (event.capturedAt() != null && event.capturedAt() > 0) {
            return event.capturedAt();
        }
        if (kafkaTimestamp > 0) {
            return kafkaTimestamp;
        }
        return 0L;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String extractDeserializationError(ConsumerRecord<?, ?> record) {
        if (record.headers() == null) {
            return null;
        }
        Header header = record.headers().lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER);
        return header != null ? "deserialization failed in ErrorHandlingDeserializer" : null;
    }
}
