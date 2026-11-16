package com.aagosh.dispatch.service;

import com.aagosh.dispatch.api.RideRequestDto;
import com.aagosh.dispatch.persistence.IdempotentRideWriter;
import com.aagosh.dispatch.persistence.RideRequestEntity;
import com.aagosh.dispatch.persistence.RideRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Service managing durable API idempotency at the ride creation boundary.
 *
 * <h2>Concurrency Contract</h2>
 * <ol>
 *   <li><strong>Fast Path:</strong> Queries {@code rideRequestRepository.findByIdempotencyKey(key)}.
 *       If found, returns the existing record immediately (0 DB writes, 0 Kafka events).</li>
 *   <li><strong>Isolated Write:</strong> Attempts {@link IdempotentRideWriter#saveNew} in a
 *       {@code REQUIRES_NEW} transaction boundary. On success, indicates caller should publish to Kafka.</li>
 *   <li><strong>Fallback Read:</strong> Catches {@link DataIntegrityViolationException} <em>outside</em>
 *       the inner boundary, retries reading the winner's committed row until visible, and returns it
 *       (0 Kafka events published).</li>
 * </ol>
 */
@Service
public class RideCreationService {

    private static final Logger log = LoggerFactory.getLogger(RideCreationService.class);
    private static final int    FALLBACK_MAX_RETRIES = 15;
    private static final long   FALLBACK_SLEEP_MS    = 10L;

    private final RideRequestRepository repository;
    private final IdempotentRideWriter  writer;

    public RideCreationService(RideRequestRepository repository, IdempotentRideWriter writer) {
        this.repository = repository;
        this.writer     = writer;
    }

    /**
     * Resolves an incoming ride request against the durable idempotency store.
     * Note: Intentionally NOT annotated with @Transactional to prevent connection pool
     * starvation deadlocks when writer.saveNew opens an isolated REQUIRES_NEW transaction.
     *
     * @param idempotencyKey optional client-supplied idempotency key
     * @param requestDto     validated request payload
     * @return resolution result containing the canonical requestId and shouldPublish flag
     */
    public RideCreationResult resolveOrCreate(String idempotencyKey, RideRequestDto requestDto) {
        if (idempotencyKey == null) {
            String requestId = requestDto.resolveId() != null
                    ? requestDto.resolveId()
                    : UUID.randomUUID().toString();
            return RideCreationResult.winner(requestId);
        }

        // 1. Fast-path check: record already exists
        Optional<RideRequestEntity> existing = repository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.debug("Idempotency fast-path hit: key={} -> requestId={}",
                    idempotencyKey, existing.get().getRequestId());
            return RideCreationResult.duplicate(existing.get().getRequestId());
        }

        // 2. Prepare proposed entity
        String proposedRequestId = requestDto.resolveId() != null
                ? requestDto.resolveId()
                : UUID.randomUUID().toString();

        RideRequestEntity entity = new RideRequestEntity(
                proposedRequestId,
                requestDto.latitude(),
                requestDto.longitude(),
                "PENDING",
                idempotencyKey
        );

        // 3. Isolated write via REQUIRES_NEW
        try {
            RideRequestEntity saved = writer.saveNew(entity);
            log.debug("Idempotency key registered: key={} -> requestId={}",
                    idempotencyKey, saved.getRequestId());
            return RideCreationResult.winner(saved.getRequestId());
        } catch (DataIntegrityViolationException e) {
            // 4. Lost concurrent race — execute fallback read outside poisoned session
            log.debug("Idempotency INSERT race lost for key={}; executing fallback read", idempotencyKey);
            return RideCreationResult.duplicate(readWinnerWithRetry(idempotencyKey));
        }
    }

    private String readWinnerWithRetry(String idempotencyKey) {
        for (int attempt = 1; attempt <= FALLBACK_MAX_RETRIES; attempt++) {
            Optional<RideRequestEntity> winner = repository.findByIdempotencyKey(idempotencyKey);
            if (winner.isPresent()) {
                return winner.get().getRequestId();
            }
            try {
                Thread.sleep(FALLBACK_SLEEP_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return repository.findByIdempotencyKey(idempotencyKey)
                .map(RideRequestEntity::getRequestId)
                .orElseThrow(() -> new IllegalStateException(
                        "Failed to resolve winning idempotency row after " + FALLBACK_MAX_RETRIES +
                        " retries for key=" + idempotencyKey));
    }

    public record RideCreationResult(String requestId, boolean shouldPublish) {
        public static RideCreationResult winner(String requestId) {
            return new RideCreationResult(requestId, true);
        }
        public static RideCreationResult duplicate(String requestId) {
            return new RideCreationResult(requestId, false);
        }
    }
}

