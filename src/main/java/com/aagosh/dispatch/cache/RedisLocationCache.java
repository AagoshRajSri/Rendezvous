package com.aagosh.dispatch.cache;

import com.aagosh.dispatch.domain.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Non-authoritative Redis location cache for driver positions.
 *
 * <h2>Role</h2>
 * <p>This service is a best-effort cache. The local {@code DriverRegistry} remains the
 * authoritative matching source of truth. Redis is written after the local registry accepts
 * a location update and is never read during driver matching.
 *
 * <h2>Key schema</h2>
 * <p>Each driver is stored under the key {@code driver:{driverId}:location} as a Redis
 * Hash with three fields:
 * <ul>
 *   <li>{@code lat}        – latitude as a decimal string</li>
 *   <li>{@code lon}        – longitude as a decimal string</li>
 *   <li>{@code updated_at} – epoch milliseconds as a decimal string</li>
 * </ul>
 *
 * <h2>Atomic conditional write</h2>
 * <p>The write is guarded by a Lua script that reads the currently stored {@code updated_at}
 * and performs the {@code HSET} + {@code PEXPIRE} pair only when the incoming timestamp is
 * strictly greater than the stored value. The script executes atomically on the Redis server,
 * so a delayed write from an older event cannot overwrite a newer cached location even under
 * concurrent calls.
 *
 * <h2>TTL</h2>
 * <p>Every accepted write resets the key's TTL to {@value #LOCATION_TTL_SECONDS} seconds.
 * Stale writes (rejected by the Lua guard) do not touch the key or its TTL.
 *
 * <h2>Failure behaviour</h2>
 * <p>Any {@link Exception} thrown by the Redis client is caught, logged with context
 * (driver ID and timestamp), and swallowed. The Kafka consumer must not fail because
 * the location cache is unavailable.
 */
@Service
public class RedisLocationCache {

    private static final Logger log = LoggerFactory.getLogger(RedisLocationCache.class);

    /** Key template: driver:{driverId}:location */
    static final String KEY_PATTERN = "driver:%s:location";

    /** Hash field names stored under each driver key. */
    static final String FIELD_LAT        = "lat";
    static final String FIELD_LON        = "lon";
    static final String FIELD_UPDATED_AT = "updated_at";

    /** Location entries expire after 30 seconds. */
    static final int LOCATION_TTL_SECONDS = 30;

    /**
     * Lua script for atomic conditional write.
     *
     * <p>Arguments:
     * <ul>
     *   <li>KEYS[1]  – the driver location key</li>
     *   <li>ARGV[1]  – new latitude string</li>
     *   <li>ARGV[2]  – new longitude string</li>
     *   <li>ARGV[3]  – new timestamp (epoch millis, decimal string)</li>
     *   <li>ARGV[4]  – TTL in milliseconds (decimal string)</li>
     * </ul>
     *
     * <p>Returns 1 if the write was performed, 0 if rejected (stale timestamp).
     */
    public static final String LUA_SCRIPT =
            "local ts = redis.call('HGET', KEYS[1], 'updated_at')\n" +
            "if not ts or tonumber(ARGV[3]) > tonumber(ts) then\n" +
            "  redis.call('HSET', KEYS[1], 'lat', ARGV[1], 'lon', ARGV[2], 'updated_at', ARGV[3])\n" +
            "  redis.call('PEXPIRE', KEYS[1], ARGV[4])\n" +
            "  return 1\n" +
            "else\n" +
            "  return 0\n" +
            "end";

    private static final long TTL_MILLIS = Duration.ofSeconds(LOCATION_TTL_SECONDS).toMillis();

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long>   locationScript;
    private final com.aagosh.dispatch.metrics.DispatchMetrics dispatchMetrics;

    public RedisLocationCache(StringRedisTemplate redisTemplate,
                              RedisScript<Long> locationScript) {
        this(redisTemplate, locationScript, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RedisLocationCache(StringRedisTemplate redisTemplate,
                              RedisScript<Long> locationScript,
                              @org.springframework.beans.factory.annotation.Autowired(required = false) com.aagosh.dispatch.metrics.DispatchMetrics dispatchMetrics) {
        this.redisTemplate   = Objects.requireNonNull(redisTemplate,  "redisTemplate");
        this.locationScript  = Objects.requireNonNull(locationScript, "locationScript");
        this.dispatchMetrics = dispatchMetrics;
    }

    /**
     * Conditionally writes the driver's location into Redis.
     *
     * <p>The write is accepted only when {@code timestamp} is strictly greater than the
     * {@code updated_at} value currently stored for the key. If the key does not exist
     * the write always succeeds.
     *
     * <p>Any Redis exception is caught and logged; the caller is not affected.
     *
     * @param driverId  the driver's logical identifier; must not be blank
     * @param latitude  the new latitude (pre-validated by the domain layer)
     * @param longitude the new longitude (pre-validated by the domain layer)
     * @param timestamp epoch-millisecond timestamp for this location fix; must be &gt; 0
     */
    public void updateLocation(String driverId, double latitude, double longitude, long timestamp) {
        if (driverId == null || driverId.isBlank()) {
            log.warn("RedisLocationCache.updateLocation called with blank driverId; skipping");
            return;
        }
        if (timestamp <= 0) {
            log.warn("RedisLocationCache.updateLocation called with non-positive timestamp {} for driver {}; skipping",
                    timestamp, driverId);
            return;
        }

        String key = String.format(KEY_PATTERN, driverId);
        try {
            Long result = redisTemplate.execute(
                    locationScript,
                    List.of(key),
                    String.valueOf(latitude),
                    String.valueOf(longitude),
                    String.valueOf(timestamp),
                    String.valueOf(TTL_MILLIS));

            if (log.isDebugEnabled()) {
                if (Long.valueOf(1L).equals(result)) {
                    log.debug("Redis location cache updated for driver {} at timestamp {}", driverId, timestamp);
                } else {
                    log.debug("Redis location cache write rejected for driver {} (stale timestamp {})", driverId, timestamp);
                }
            }
        } catch (Exception ex) {
            if (dispatchMetrics != null) {
                dispatchMetrics.recordRedisCacheRequest(com.aagosh.dispatch.metrics.DispatchMetrics.CACHE_ERROR);
            }
            log.warn("Redis location cache write failed for driver {} at timestamp {}: {}",
                    driverId, timestamp, ex.getMessage());
        }
    }

    /**
     * Returns the key under which a driver's location is cached.
     * Exposed package-private for testing.
     */
    static String keyFor(String driverId) {
        return String.format(KEY_PATTERN, driverId);
    }

    // -------------------------------------------------------------------------
    // Location read
    // -------------------------------------------------------------------------

    /**
     * Discriminated result type for {@link #getLocation(String)}.
     *
     * <ul>
     *   <li>{@link Found}  – key exists and contained valid lat/lon/updated_at fields.</li>
     *   <li>{@link Miss}   – key does not exist in Redis (expired TTL or never written).</li>
     *   <li>{@link RedisError} – Redis was unreachable or returned an unexpected error.
     *       This is explicitly <em>not</em> treated as a cache miss so callers can handle
     *       infrastructure failures differently from genuine absence of data.</li>
     * </ul>
     */
    public sealed interface LocationResult
            permits LocationResult.Found, LocationResult.Miss, LocationResult.RedisError {

        /** A valid cached location was retrieved. */
        record Found(GeoPoint location, long updatedAt) implements LocationResult {}

        /** The key is absent (TTL expired or driver never cached). */
        record Miss() implements LocationResult {
            /** Singleton canonical instance. */
            public static final Miss INSTANCE = new Miss();
        }

        /** Redis was unavailable or threw an unexpected exception. */
        record RedisError(String message) implements LocationResult {}
    }

    /**
     * Reads the cached location for the specified driver from Redis.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@link LocationResult.Found}     if the key exists with valid lat/lon/updated_at data.</li>
     *   <li>{@link LocationResult.Miss}       if the key is absent (no cache entry or TTL expired).</li>
     *   <li>{@link LocationResult.RedisError} if Redis is unavailable or throws an unexpected error.
     *       <strong>This is explicitly distinct from a cache miss.</strong></li>
     * </ul>
     *
     * @param driverId the driver's logical identifier; must not be blank
     * @return a {@link LocationResult} describing the outcome; never {@code null}
     */
    public LocationResult getLocation(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            log.warn("RedisLocationCache.getLocation called with blank driverId");
            return recordResult(new LocationResult.RedisError("blank driverId"));
        }

        String key = String.format(KEY_PATTERN, driverId);
        try {
            Map<Object, Object> fields =
                    redisTemplate.opsForHash().entries(key);

            if (fields == null || fields.isEmpty()) {
                return recordResult(LocationResult.Miss.INSTANCE);
            }

            String latStr = (String) fields.get(FIELD_LAT);
            String lonStr = (String) fields.get(FIELD_LON);
            String tsStr  = (String) fields.get(FIELD_UPDATED_AT);

            if (latStr == null || lonStr == null || tsStr == null) {
                // Partial write — treat as a miss rather than silently fabricating data.
                log.warn("Incomplete Redis location entry for driver {}: lat={} lon={} ts={}",
                        driverId, latStr, lonStr, tsStr);
                return recordResult(LocationResult.Miss.INSTANCE);
            }

            double lat       = Double.parseDouble(latStr);
            double lon       = Double.parseDouble(lonStr);
            long   updatedAt = Long.parseLong(tsStr);
            GeoPoint location = new GeoPoint(lat, lon);  // validates range
            return recordResult(new LocationResult.Found(location, updatedAt));

        } catch (RedisConnectionFailureException ex) {
            // Explicit infrastructure failure — must NOT be silently downgraded to a Miss.
            log.warn("Redis connection failure reading location for driver {}: {}", driverId, ex.getMessage());
            return recordResult(new LocationResult.RedisError("connection failure: " + ex.getMessage()));
        } catch (NumberFormatException ex) {
            log.warn("Malformed Redis location fields for driver {}: {}", driverId, ex.getMessage());
            return recordResult(new LocationResult.RedisError("malformed data: " + ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            // GeoPoint range validation failed.
            log.warn("Invalid coordinates in Redis for driver {}: {}", driverId, ex.getMessage());
            return recordResult(new LocationResult.RedisError("invalid coordinates: " + ex.getMessage()));
        } catch (Exception ex) {
            // Unexpected Redis exception — not a cache miss.
            log.warn("Unexpected error reading Redis location for driver {}: {}", driverId, ex.getMessage());
            return recordResult(new LocationResult.RedisError("unexpected error: " + ex.getMessage()));
        }
    }

    private LocationResult recordResult(LocationResult result) {
        if (dispatchMetrics != null) {
            if (result instanceof LocationResult.Found) {
                dispatchMetrics.recordRedisCacheRequest(com.aagosh.dispatch.metrics.DispatchMetrics.CACHE_HIT);
            } else if (result instanceof LocationResult.Miss) {
                dispatchMetrics.recordRedisCacheRequest(com.aagosh.dispatch.metrics.DispatchMetrics.CACHE_MISS);
            } else if (result instanceof LocationResult.RedisError) {
                dispatchMetrics.recordRedisCacheRequest(com.aagosh.dispatch.metrics.DispatchMetrics.CACHE_ERROR);
            }
        }
        return result;
    }
}
