package com.aagosh.dispatch.messaging;

import com.aagosh.dispatch.api.RideRequestDto;
import com.aagosh.dispatch.config.KafkaTopics;
import com.aagosh.dispatch.persistence.AssignmentPersistenceService;
import com.aagosh.dispatch.persistence.RideRequestRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumer for the dead-letter topic ({@value KafkaTopics#RIDE_REQUESTS_DLT}).
 *
 * <p>When a ride request exhausts all exponential backoff retries, Spring Kafka's
 * {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer} publishes the
 * failed record to {@code ride-requests.DLT}.
 *
 * <p>This listener logs the poison pill / unrecoverable failure and updates the corresponding
 * database record in PostgreSQL to {@link AssignmentPersistenceService#STATUS_FAILED_DISPATCH}
 * so that state is terminal and observable.
 */
@Component
public class DeadLetterQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueConsumer.class);

    private final RideRequestRepository rideRequestRepository;

    public DeadLetterQueueConsumer(RideRequestRepository rideRequestRepository) {
        this.rideRequestRepository = rideRequestRepository;
    }

    @KafkaListener(
            topics = KafkaTopics.RIDE_REQUESTS_DLT,
            groupId = "${spring.kafka.consumer.dlt-group-id:dispatch-dlt-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        String key = record.key();
        log.error("[DLT-Consumer] Poison pill / unrecoverable failure record received on topic='{}' partition={} offset={} key='{}'",
                record.topic(), record.partition(), record.offset(), key);

        String requestId = resolveRequestId(record);
        if (requestId != null && !requestId.isBlank()) {
            try {
                rideRequestRepository.findByRequestId(requestId).ifPresent(entity -> {
                    entity.setStatus(AssignmentPersistenceService.STATUS_FAILED_DISPATCH);
                    rideRequestRepository.save(entity);
                    log.info("[DLT-Consumer] Updated PostgreSQL ride_requests status to '{}' for requestId='{}'",
                            AssignmentPersistenceService.STATUS_FAILED_DISPATCH, requestId);
                });
            } catch (Exception ex) {
                log.warn("[DLT-Consumer] Failed to update PostgreSQL status to FAILED_DISPATCH for requestId='{}': {}",
                        requestId, ex.getMessage());
            }
        } else {
            log.warn("[DLT-Consumer] Unable to resolve requestId from dead-letter record key='{}' value='{}'",
                    key, record.value());
        }

        if (ack != null) {
            ack.acknowledge();
        }
    }

    private String resolveRequestId(ConsumerRecord<String, Object> record) {
        if (record.key() != null && !record.key().isBlank()) {
            return record.key();
        }
        Object value = record.value();
        if (value instanceof RideRequestDto dto) {
            return dto.resolveId();
        }
        if (value instanceof String str && str.contains("requestId")) {
            // Simple JSON extraction fallback
            int idx = str.indexOf("\"requestId\"");
            if (idx != -1) {
                int colon = str.indexOf(':', idx);
                int quote1 = str.indexOf('"', colon);
                int quote2 = str.indexOf('"', quote1 + 1);
                if (quote1 != -1 && quote2 != -1) {
                    return str.substring(quote1 + 1, quote2);
                }
            }
        }
        return null;
    }
}

