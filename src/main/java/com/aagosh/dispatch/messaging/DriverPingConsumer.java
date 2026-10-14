package com.aagosh.dispatch.messaging;

import com.aagosh.dispatch.api.DriverPingRequest;
import com.aagosh.dispatch.api.PingAction;
import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.DriverState;
import com.aagosh.dispatch.domain.GeoPoint;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.stereotype.Component;

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
 *       acknowledges on duplicate (the driver already exists — this is idempotent).</li>
 *   <li>{@link PingAction#UPDATE}: updates the driver's location. Logs a warning and
 *       acknowledges if the driver is not found (registration may not have been processed yet
 *       due to at-least-once reordering). Does <em>not</em> reset claim state.</li>
 *   <li>{@link PingAction#AUTO} (or absent): registers if new; updates location if
 *       already registered. Preserves claim state. Safe to retry.</li>
 * </ul>
 *
 * <h2>At-least-once delivery and idempotency</h2>
 * <p>Driver ping events are naturally idempotent:
 * <ul>
 *   <li>A duplicate REGISTER event for an already-registered driver is logged and
 *       acknowledged without resetting state.</li>
 *   <li>Duplicate location UPDATE events converge the registry to a known location.
 *       Because the registry has no event-time ordering guarantee, a duplicate may commit
 *       a slightly stale coordinate, but this matches the existing documented behaviour.</li>
 *   <li>AUTO events behave identically across retries.</li>
 * </ul>
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
 * {@link DriverRegistry#updateLocation(String, GeoPoint)}.
 */
@Component
public class DriverPingConsumer {

    private static final Logger log = LoggerFactory.getLogger(DriverPingConsumer.class);

    private final DriverRegistry driverRegistry;

    public DriverPingConsumer(DriverRegistry driverRegistry) {
        this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
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

        GeoPoint location = new GeoPoint(event.latitude(), event.longitude());
        PingAction action = event.resolveAction();

        try {
            switch (action) {
                case REGISTER -> processRegister(event.driverId(), location, record);
                case UPDATE   -> processUpdate(event.driverId(), location, record);
                case AUTO     -> processAuto(event.driverId(), location, record);
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

    private void processRegister(String driverId, GeoPoint location,
                                 ConsumerRecord<?, ?> record) {
        try {
            driverRegistry.register(new Driver(driverId, location, new DriverState()));
            log.debug("Registered driver {} via consumer (partition={} offset={})",
                    driverId, record.partition(), record.offset());
        } catch (IllegalStateException ex) {
            // Driver already registered — treat as idempotent success.
            log.warn("Duplicate REGISTER event for already-registered driver {}; acknowledging",
                    driverId);
        }
    }

    private void processUpdate(String driverId, GeoPoint location,
                               ConsumerRecord<?, ?> record) {
        try {
            driverRegistry.updateLocation(driverId, location);
            log.debug("Updated location for driver {} via consumer (partition={} offset={})",
                    driverId, record.partition(), record.offset());
        } catch (IllegalStateException ex) {
            // Driver not found. This can happen if a REGISTER event has not been processed yet
            // (e.g., partition reassignment or consumer restart). Log and acknowledge;
            // a subsequent ping will converge the registry.
            log.warn("UPDATE event for unknown driver {}; acknowledging without retry", driverId);
        }
    }

    private void processAuto(String driverId, GeoPoint location,
                              ConsumerRecord<?, ?> record) {
        Optional<Driver> existing = driverRegistry.findById(driverId);
        if (existing.isPresent()) {
            processUpdate(driverId, location, record);
        } else {
            try {
                driverRegistry.register(new Driver(driverId, location, new DriverState()));
                log.debug("Auto-registered driver {} via consumer (partition={} offset={})",
                        driverId, record.partition(), record.offset());
            } catch (IllegalStateException ex) {
                // Race: another consumer thread registered between findById and register.
                processUpdate(driverId, location, record);
            }
        }
    }

    private String extractDeserializationError(ConsumerRecord<?, ?> record) {
        if (record.headers() == null) {
            return null;
        }
        Header header = record.headers().lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER);
        return header != null ? "deserialization failed in ErrorHandlingDeserializer" : null;
    }
}
