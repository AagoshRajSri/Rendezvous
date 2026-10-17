package com.aagosh.dispatch.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link RedisLocationCache}: validates key naming, script argument
 * construction, staleness guard, and failure isolation — all without a real Redis instance.
 */
class RedisLocationCacheTest {

    @SuppressWarnings("unchecked")
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<Long> locationScript = mock(RedisScript.class);

    private RedisLocationCache cache;

    @BeforeEach
    void setUp() {
        cache = new RedisLocationCache(redisTemplate, locationScript);
    }

    // -------------------------------------------------------------------------
    // Key format
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("keyFor produces 'driver:{id}:location' format")
    void keyFormatMatchesSpec() {
        assertEquals("driver:D1:location", RedisLocationCache.keyFor("D1"));
        assertEquals("driver:ABC-123:location", RedisLocationCache.keyFor("ABC-123"));
    }

    // -------------------------------------------------------------------------
    // Script execution
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateLocation executes Lua script with correct key and ARGV")
    void updateLocationExecutesLuaScript() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        cache.updateLocation("D1", 17.3850, 78.4867, 1000L);

        verify(redisTemplate).execute(
                locationScript,
                List.of("driver:D1:location"),
                "17.385",
                "78.4867",
                "1000",
                String.valueOf(30_000L) // 30s TTL in millis
        );
    }

    @Test
    @DisplayName("updateLocation with zero timestamp is skipped (guard against no-timestamp writes)")
    void zeroTimestampIsSkipped() {
        cache.updateLocation("D1", 17.0, 78.0, 0L);

        verify(redisTemplate, never()).execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("updateLocation with negative timestamp is skipped")
    void negativeTimestampIsSkipped() {
        cache.updateLocation("D1", 17.0, 78.0, -1L);

        verify(redisTemplate, never()).execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("updateLocation with blank driverId is skipped without calling Redis")
    void blankDriverIdIsSkipped() {
        cache.updateLocation("   ", 17.0, 78.0, 1000L);

        verify(redisTemplate, never()).execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("updateLocation with null driverId is skipped without calling Redis")
    void nullDriverIdIsSkipped() {
        cache.updateLocation(null, 17.0, 78.0, 1000L);

        verify(redisTemplate, never()).execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString());
    }

    // -------------------------------------------------------------------------
    // Failure isolation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Redis exception is caught and does not propagate to caller")
    void redisExceptionIsSwallowed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Connection refused"));

        // Must not throw
        cache.updateLocation("D1", 17.0, 78.0, 1000L);
    }

    @Test
    @DisplayName("Redis timeout exception is caught and does not propagate to caller")
    void redisTimeoutIsSwallowed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Timeout"));

        cache.updateLocation("D2", 17.0, 78.0, 2000L);
        // No exception propagated
    }

    // -------------------------------------------------------------------------
    // Metrics recording tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getLocation records cache hit, miss, and error metrics correctly")
    void getLocationRecordsMetrics() {
        io.micrometer.core.instrument.MeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.aagosh.dispatch.metrics.DispatchMetrics metrics = new com.aagosh.dispatch.metrics.DispatchMetrics(registry);
        RedisLocationCache instrumentedCache = new RedisLocationCache(redisTemplate, locationScript, metrics);

        org.springframework.data.redis.core.HashOperations hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        // 1. Cache Hit
        when(hashOps.entries("driver:D1:location")).thenReturn(java.util.Map.of(
                "lat", "17.3850",
                "lon", "78.4867",
                "updated_at", "1000"
        ));
        RedisLocationCache.LocationResult hitResult = instrumentedCache.getLocation("D1");
        org.junit.jupiter.api.Assertions.assertInstanceOf(RedisLocationCache.LocationResult.Found.class, hitResult);
        assertEquals(1.0, registry.find(com.aagosh.dispatch.metrics.DispatchMetrics.METRIC_REDIS_CACHE)
                .tag("result", com.aagosh.dispatch.metrics.DispatchMetrics.CACHE_HIT).counter().count());

        // 2. Cache Miss (absent key)
        when(hashOps.entries("driver:D2:location")).thenReturn(java.util.Collections.emptyMap());
        RedisLocationCache.LocationResult missResult = instrumentedCache.getLocation("D2");
        org.junit.jupiter.api.Assertions.assertInstanceOf(RedisLocationCache.LocationResult.Miss.class, missResult);
        assertEquals(1.0, registry.find(com.aagosh.dispatch.metrics.DispatchMetrics.METRIC_REDIS_CACHE)
                .tag("result", com.aagosh.dispatch.metrics.DispatchMetrics.CACHE_MISS).counter().count());

        // 3. Cache Error (Redis exception)
        when(hashOps.entries("driver:D3:location")).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("Conn reset"));
        RedisLocationCache.LocationResult errResult = instrumentedCache.getLocation("D3");
        org.junit.jupiter.api.Assertions.assertInstanceOf(RedisLocationCache.LocationResult.RedisError.class, errResult);
        assertEquals(1.0, registry.find(com.aagosh.dispatch.metrics.DispatchMetrics.METRIC_REDIS_CACHE)
                .tag("result", com.aagosh.dispatch.metrics.DispatchMetrics.CACHE_ERROR).counter().count());
    }

    @Test
    @DisplayName("updateLocation records cache error metric on Redis failure")
    void updateLocationRecordsErrorMetric() {
        io.micrometer.core.instrument.MeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.aagosh.dispatch.metrics.DispatchMetrics metrics = new com.aagosh.dispatch.metrics.DispatchMetrics(registry);
        RedisLocationCache instrumentedCache = new RedisLocationCache(redisTemplate, locationScript, metrics);

        when(redisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis unavailable"));

        instrumentedCache.updateLocation("D1", 17.0, 78.0, 1000L);

        assertEquals(1.0, registry.find(com.aagosh.dispatch.metrics.DispatchMetrics.METRIC_REDIS_CACHE)
                .tag("result", com.aagosh.dispatch.metrics.DispatchMetrics.CACHE_ERROR).counter().count());
    }
}
