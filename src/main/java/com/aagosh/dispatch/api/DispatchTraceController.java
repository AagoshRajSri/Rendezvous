package com.aagosh.dispatch.api;

import com.aagosh.dispatch.tracing.DispatchTrace;
import com.aagosh.dispatch.tracing.DispatchTraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;

/**
 * REST controller exposing read-only access to dispatch execution traces.
 *
 * <h2>Endpoint</h2>
 * <p>{@code GET /api/requests/{requestId}/trace} – returns the authentic {@link DispatchTrace}
 * recorded during spatial search and candidate evaluation.
 *
 * <h2>Semantics & Guarantees</h2>
 * <ul>
 *   <li><strong>Read-only:</strong> Pure in-memory cache lookup against {@link DispatchTraceStore};
 *       performs no database operations and triggers no matching or dispatch side-effects.</li>
 *   <li><strong>Lifecycle & Expiration:</strong> Returns HTTP 404 if the trace has not been generated,
 *       has been evicted by LRU capacity limits, or has expired via TTL.</li>
 *   <li><strong>Isolation:</strong> Strictly validates request IDs and prevents cross-request data leaks.</li>
 *   <li><strong>Error handling:</strong> Adheres to {@link ApiErrorResponse} standard error structure
 *       without exposing internal stack traces or implementation details.</li>
 * </ul>
 */
@RestController
@RequestMapping({"/api/requests", "/requests"})
public class DispatchTraceController {

    private static final Logger log = LoggerFactory.getLogger(DispatchTraceController.class);

    private final DispatchTraceStore traceStore;

    public DispatchTraceController(DispatchTraceStore traceStore) {
        this.traceStore = Objects.requireNonNull(traceStore, "traceStore");
    }

    /**
     * Retrieves the authentic dispatch trace for a given ride request.
     *
     * @param requestId the request identifier; must not be null or blank
     * @return 200 OK with {@link DispatchTrace} if found and active; 404 Not Found if missing or expired;
     *         400 Bad Request if requestId is malformed or blank
     */
    @GetMapping("/{requestId}/trace")
    public ResponseEntity<?> getTrace(@PathVariable("requestId") String requestId) {
        validateRequestId(requestId);

        Optional<DispatchTrace> traceOpt = traceStore.get(requestId);
        if (traceOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiErrorResponse(
                            HttpStatus.NOT_FOUND.value(),
                            "Not Found",
                            "No dispatch trace found for requestId: " + requestId));
        }

        return ResponseEntity.ok(traceOpt.get());
    }

    private void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new InvalidRequestException("requestId must not be null or blank");
        }
    }
}
