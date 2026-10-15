package com.aagosh.dispatch.cache;

import com.aagosh.dispatch.cache.RedisLocationCache.LocationResult;
import com.aagosh.dispatch.domain.GeoPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying {@link RedisLocationCache#getLocation(String)} semantics,
 * including strict distinction between cache misses and Redis infrastructure errors.
 */
class RedisLocationCacheGetLocationTest {

    @SuppressWarnings("unchecked")
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<Long> locationScript = mock(RedisScript.class);

    private RedisLocationCache cache;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        cache = new RedisLocationCache(redisTemplate, locationScript);
    }

    @Test
    @DisplayName("getLocation returns RedisError for null or blank driverId without calling Redis")
    void blankOrNullDriverIdReturnsRedisError() {
        LocationResult r1 = cache.getLocation(null);
        assertInstanceOf(LocationResult.RedisError.class, r1);
        assertEquals("blank driverId", ((LocationResult.RedisError) r1).message());

        LocationResult r2 = cache.getLocation("");
        assertInstanceOf(LocationResult.RedisError.class, r2);

        LocationResult r3 = cache.getLocation("   ");
        assertInstanceOf(LocationResult.RedisError.class, r3);

        verify(redisTemplate, never()).opsForHash();
    }

    @Test
    @DisplayName("getLocation returns Miss when Redis key does not exist or is empty")
    void absentKeyReturnsMiss() {
        when(hashOps.entries("driver:D1:location")).thenReturn(Collections.emptyMap());

        LocationResult result = cache.getLocation("D1");
        assertSame(LocationResult.Miss.INSTANCE, result);

        when(hashOps.entries("driver:D2:location")).thenReturn(null);
        LocationResult result2 = cache.getLocation("D2");
        assertSame(LocationResult.Miss.INSTANCE, result2);
    }

    @Test
    @DisplayName("getLocation returns Miss when hash fields are incomplete (partial write)")
    void incompleteHashReturnsMiss() {
        // Missing updated_at
        when(hashOps.entries("driver:D1:location")).thenReturn(Map.of(
                "lat", "12.9716",
                "lon", "77.5946"
        ));
        assertSame(LocationResult.Miss.INSTANCE, cache.getLocation("D1"));

        // Missing lat
        when(hashOps.entries("driver:D2:location")).thenReturn(Map.of(
                "lon", "77.5946",
                "updated_at", "1700000000000"
        ));
        assertSame(LocationResult.Miss.INSTANCE, cache.getLocation("D2"));

        // Missing lon
        when(hashOps.entries("driver:D3:location")).thenReturn(Map.of(
                "lat", "12.9716",
                "updated_at", "1700000000000"
        ));
        assertSame(LocationResult.Miss.INSTANCE, cache.getLocation("D3"));
    }

    @Test
    @DisplayName("getLocation returns Found with parsed coordinates and timestamp when entry is valid")
    void validEntryReturnsFound() {
        when(hashOps.entries("driver:D1:location")).thenReturn(Map.of(
                "lat", "12.9716",
                "lon", "77.5946",
                "updated_at", "1700000000000"
        ));

        LocationResult result = cache.getLocation("D1");
        assertInstanceOf(LocationResult.Found.class, result);

        LocationResult.Found found = (LocationResult.Found) result;
        assertEquals(new GeoPoint(12.9716, 77.5946), found.location());
        assertEquals(1700000000000L, found.updatedAt());
    }

    @Test
    @DisplayName("getLocation returns RedisError (NOT Miss) on RedisConnectionFailureException")
    void redisConnectionFailureReturnsRedisError() {
        when(hashOps.entries("driver:D1:location"))
                .thenThrow(new RedisConnectionFailureException("Redis connection refused"));

        LocationResult result = cache.getLocation("D1");
        assertInstanceOf(LocationResult.RedisError.class, result);
        assertTrue(((LocationResult.RedisError) result).message().contains("connection failure"));
    }

    @Test
    @DisplayName("getLocation returns RedisError on malformed numeric data")
    void malformedDataReturnsRedisError() {
        when(hashOps.entries("driver:D1:location")).thenReturn(Map.of(
                "lat", "not-a-number",
                "lon", "77.5946",
                "updated_at", "1700000000000"
        ));

        LocationResult result = cache.getLocation("D1");
        assertInstanceOf(LocationResult.RedisError.class, result);
        assertTrue(((LocationResult.RedisError) result).message().contains("malformed data"));
    }

    @Test
    @DisplayName("getLocation returns RedisError on invalid coordinates (out of range)")
    void invalidCoordinatesReturnsRedisError() {
        when(hashOps.entries("driver:D1:location")).thenReturn(Map.of(
                "lat", "999.0", // invalid latitude > 90
                "lon", "77.5946",
                "updated_at", "1700000000000"
        ));

        LocationResult result = cache.getLocation("D1");
        assertInstanceOf(LocationResult.RedisError.class, result);
        assertTrue(((LocationResult.RedisError) result).message().contains("invalid coordinates"));
    }

    @Test
    @DisplayName("getLocation returns RedisError on unexpected generic Exception")
    void unexpectedExceptionReturnsRedisError() {
        when(hashOps.entries("driver:D1:location"))
                .thenThrow(new RuntimeException("Unexpected internal error"));

        LocationResult result = cache.getLocation("D1");
        assertInstanceOf(LocationResult.RedisError.class, result);
        assertTrue(((LocationResult.RedisError) result).message().contains("unexpected error"));
    }
}
