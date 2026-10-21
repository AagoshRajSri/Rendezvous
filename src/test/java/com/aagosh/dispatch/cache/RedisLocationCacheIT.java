package com.aagosh.dispatch.cache;

import com.aagosh.dispatch.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link RedisLocationCache} against a real Redis instance
 * managed by Testcontainers.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Basic write and read-back via StringRedisTemplate</li>
 *   <li>Stale update rejection by Lua script (older timestamp)</li>
 *   <li>Equal timestamp rejection</li>
 *   <li>Strictly newer update acceptance</li>
 *   <li>Key TTL expiry (30-second window verified with short actual TTL in a fast-path check)</li>
 *   <li>Concurrent writes - final value must be the one with the highest timestamp</li>
 * </ul>
 *
 * <p>These tests are skipped when Docker is not available
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@SuppressWarnings("resource")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {RedisLocationCacheIT.TestConfig.class},
        properties = {
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"
        }
)
class RedisLocationCacheIT {

    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.context.annotation.Import({RedisConfig.class, RedisLocationCache.class})
    static class TestConfig {
    }

    private static final int REDIS_PORT = 6379;

    @Container
    @SuppressWarnings("rawtypes")
    static GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port",
                () -> redisContainer.getMappedPort(REDIS_PORT));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisLocationCache redisLocationCache;

    @BeforeEach
    void flushRedis() {
        // Flush all keys before each test for isolation
        redisTemplate.getConnectionFactory()
                     .getConnection()
                     .serverCommands()
                     .flushAll();
    }

    // -------------------------------------------------------------------------
    // Basic write
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("1. Write stores lat, lon, updated_at in Redis hash")
    void writeStoresHashFields() {
        redisLocationCache.updateLocation("D1", 17.3850, 78.4867, 1000L);

        String key = RedisLocationCache.keyFor("D1");
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

        assertEquals("17.385", entries.get(RedisLocationCache.FIELD_LAT));
        assertEquals("78.4867", entries.get(RedisLocationCache.FIELD_LON));
        assertEquals("1000", entries.get(RedisLocationCache.FIELD_UPDATED_AT));
    }

    @Test
    @DisplayName("2. Key TTL is set to 30 seconds after write")
    void writeSetsExpiry() {
        redisLocationCache.updateLocation("D1", 17.0, 78.0, 1000L);

        String key = RedisLocationCache.keyFor("D1");
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);

        assertNotNull(ttl, "TTL must be set");
        assertTrue(ttl > 0 && ttl <= RedisLocationCache.LOCATION_TTL_SECONDS,
                "TTL must be between 1 and " + RedisLocationCache.LOCATION_TTL_SECONDS
                + " seconds, was: " + ttl);
    }

    // -------------------------------------------------------------------------
    // Lua conditional guard
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("3. Stale update (older timestamp) is rejected by Lua script")
    void staleUpdateIsRejected() {
        redisLocationCache.updateLocation("D2", 17.5, 78.5, 2000L);
        redisLocationCache.updateLocation("D2", 10.0, 10.0, 500L);  // stale

        String key = RedisLocationCache.keyFor("D2");
        String lat = (String) redisTemplate.opsForHash().get(key, RedisLocationCache.FIELD_LAT);
        assertEquals("17.5", lat, "Stale update must not overwrite newer cached location");
    }

    @Test
    @DisplayName("4. Equal timestamp update is rejected by Lua script")
    void equalTimestampUpdateIsRejected() {
        redisLocationCache.updateLocation("D3", 17.5, 78.5, 1000L);
        redisLocationCache.updateLocation("D3", 20.0, 20.0, 1000L); // same ts

        String key = RedisLocationCache.keyFor("D3");
        String lat = (String) redisTemplate.opsForHash().get(key, RedisLocationCache.FIELD_LAT);
        assertEquals("17.5", lat, "Equal-timestamp update must not overwrite stored location");
    }

    @Test
    @DisplayName("5. Strictly newer update overwrites older cached location")
    void newerUpdateOverwrites() {
        redisLocationCache.updateLocation("D4", 17.5, 78.5, 1000L);
        redisLocationCache.updateLocation("D4", 18.0, 79.0, 2000L); // newer

        String key = RedisLocationCache.keyFor("D4");
        String lat = (String) redisTemplate.opsForHash().get(key, RedisLocationCache.FIELD_LAT);
        assertEquals("18.0", lat, "Newer update must overwrite older cached location");
    }

    @Test
    @DisplayName("6. First write to a key (no prior entry) always succeeds")
    void firstWriteSucceeds() {
        redisLocationCache.updateLocation("D5", 15.0, 75.0, 100L);

        String key = RedisLocationCache.keyFor("D5");
        assertTrue(redisTemplate.hasKey(key), "Key must exist after first write");
    }

    // -------------------------------------------------------------------------
    // TTL refresh on accepted update
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("7. Accepted update refreshes TTL back to 30 seconds")
    void acceptedUpdateRefreshesTtl() {
        redisLocationCache.updateLocation("D6", 17.0, 78.0, 1000L);
        String key = RedisLocationCache.keyFor("D6");
        // Explicitly drain TTL to 10 seconds without sleeping
        redisTemplate.expire(key, Duration.ofSeconds(10));
        Long drainedTtl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(drainedTtl);
        assertTrue(drainedTtl <= 10, "TTL must be <= 10 after manual drain");

        redisLocationCache.updateLocation("D6", 18.0, 79.0, 2000L); // newer - accepted

        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        // Should be reset close to 30 again - allow 2 seconds grace
        assertTrue(ttl > 27, "Accepted update must reset TTL close to 30s, was: " + ttl);
    }

    @Test
    @DisplayName("8. Rejected stale update does not modify TTL of existing key")
    void rejectedUpdateDoesNotTouchTtl() {
        redisLocationCache.updateLocation("D7", 17.0, 78.0, 2000L);
        String key = RedisLocationCache.keyFor("D7");
        // Explicitly set TTL to 15 seconds without sleeping
        redisTemplate.expire(key, Duration.ofSeconds(15));
        Long ttlBeforeStale = redisTemplate.getExpire(key, TimeUnit.SECONDS);

        redisLocationCache.updateLocation("D7", 10.0, 10.0, 500L); // stale

        Long ttlAfterStale = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttlAfterStale);
        // TTL must not have been reset to 30 by the stale update
        assertTrue(ttlAfterStale <= ttlBeforeStale,
                "Stale update must not reset TTL; before=" + ttlBeforeStale + " after=" + ttlAfterStale);
    }

    // -------------------------------------------------------------------------
    // Concurrency
    // -------------------------------------------------------------------------

    /**
     * 50 threads concurrently write the same key with distinct timestamps 1..50.
     * The Lua script serialises these on the Redis server, so the final stored
     * {@code updated_at} must be 50 (the highest).
     */
    @Test
    @DisplayName("9. Concurrent writes: only the highest timestamp survives")
    void concurrentWritesYieldHighestTimestamp() throws Exception {
        int threads = 50;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);
        List<Thread> workers = new ArrayList<>(threads);

        for (int i = 1; i <= threads; i++) {
            final long ts = i;
            final double lat = i * 0.01;
            Thread t = new Thread(() -> {
                try {
                    ready.countDown();
                    start.await();
                    redisLocationCache.updateLocation("D_CONCURRENT", lat, 78.0, ts);
                } catch (Exception ex) {
                    errors.incrementAndGet();
                }
            });
            workers.add(t);
            t.start();
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Thread t : workers) t.join(10_000);

        assertEquals(0, errors.get(), "No thread must throw during concurrent writes");

        String key = RedisLocationCache.keyFor("D_CONCURRENT");
        String storedTs = (String) redisTemplate.opsForHash().get(key, RedisLocationCache.FIELD_UPDATED_AT);
        assertNotNull(storedTs);
        assertEquals(50L, Long.parseLong(storedTs),
                "After concurrent writes with ts 1-50, stored ts must be 50");
    }

    // -------------------------------------------------------------------------
    // Out-of-order pings
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("10. Out-of-order pings: earlier ping delivered after later ping is rejected")
    void outOfOrderPingIsRejected() {
        // Later ping arrives first
        redisLocationCache.updateLocation("D8", 18.0, 79.0, 2000L);
        // Earlier ping arrives second (simulates network reorder)
        redisLocationCache.updateLocation("D8", 17.0, 78.0, 1000L);

        String key = RedisLocationCache.keyFor("D8");
        String lat = (String) redisTemplate.opsForHash().get(key, RedisLocationCache.FIELD_LAT);
        String storedTs = (String) redisTemplate.opsForHash().get(key, RedisLocationCache.FIELD_UPDATED_AT);

        assertEquals("18.0", lat, "Earlier out-of-order ping must not overwrite fresher location");
        assertEquals("2000", storedTs);
    }

    // -------------------------------------------------------------------------
    // Interleaved registry and Redis updates
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("11. Sequential simulation: Thread 1 accepts locally at ts=1000, pauses; Thread 2 accepts and writes Redis at ts=2000; Thread 1 resumes and Redis rejects stale write")
    void simulatedSequentialInterleavingRegistryAndRedis() {
        com.aagosh.dispatch.domain.DriverRegistry registry = new com.aagosh.dispatch.domain.DriverRegistry();
        String driverId = "D_SEQ_INTERLEAVED";

        // Pre-register driver at initial location
        registry.register(new com.aagosh.dispatch.domain.Driver(
                driverId,
                new com.aagosh.dispatch.domain.GeoPoint(17.0, 78.0),
                new com.aagosh.dispatch.domain.DriverState(),
                500L));
        redisLocationCache.updateLocation(driverId, 17.0, 78.0, 500L);

        // Step 1: Thread 1 processes earlier ping (ts=1000). Registry accepts.
        boolean accepted1 = registry.updateLocation(
                driverId, new com.aagosh.dispatch.domain.GeoPoint(18.0, 78.0), 1000L);
        assertTrue(accepted1, "Thread 1 update at ts=1000 must be accepted locally");
        // Thread 1 pauses here before writing to Redis...

        // Step 2: Thread 2 processes newer ping (ts=2000). Registry accepts and writes to Redis.
        boolean accepted2 = registry.updateLocation(
                driverId, new com.aagosh.dispatch.domain.GeoPoint(19.0, 79.0), 2000L);
        assertTrue(accepted2, "Thread 2 update at ts=2000 must be accepted locally");
        redisLocationCache.updateLocation(driverId, 19.0, 79.0, 2000L);

        // Step 3: Thread 1 resumes and attempts writing its delayed ts=1000 update to Redis.
        redisLocationCache.updateLocation(driverId, 18.0, 78.0, 1000L);

        // Verify Redis retains Thread 2's location (ts=2000) because Lua guard rejected ts=1000
        String key = RedisLocationCache.keyFor(driverId);
        Map<Object, Object> redisEntries = redisTemplate.opsForHash().entries(key);
        assertEquals("19.0", redisEntries.get(RedisLocationCache.FIELD_LAT),
                "Redis must retain Thread 2's latitude (ts=2000), not Thread 1's stale delayed write");
        assertEquals("79.0", redisEntries.get(RedisLocationCache.FIELD_LON));
        assertEquals("2000", redisEntries.get(RedisLocationCache.FIELD_UPDATED_AT));

        // Verify local DriverRegistry also retains Thread 2's location (ts=2000)
        com.aagosh.dispatch.domain.Driver stored = registry.findById(driverId).orElseThrow();
        assertEquals(19.0, stored.location().latitude());
        assertEquals(79.0, stored.location().longitude());
        assertEquals(2000L, stored.updatedAt());
    }

    @Test
    @DisplayName("12. Deterministic concurrent two-thread interleaving with CountDownLatch: Thread A accepts locally at ts=1000, pauses; Thread B accepts and writes Redis at ts=2000; Thread A resumes and Redis rejects stale write without refreshing TTL")
    void concurrentTwoThreadInterleavingWithLatches() throws Exception {
        com.aagosh.dispatch.domain.DriverRegistry registry = new com.aagosh.dispatch.domain.DriverRegistry();
        String driverId = "D_THREAD_INTERLEAVED";

        // Pre-register driver at initial location
        registry.register(new com.aagosh.dispatch.domain.Driver(
                driverId,
                new com.aagosh.dispatch.domain.GeoPoint(17.0, 78.0),
                new com.aagosh.dispatch.domain.DriverState(),
                500L));
        redisLocationCache.updateLocation(driverId, 17.0, 78.0, 500L);

        CountDownLatch threadAAcceptedLocally = new CountDownLatch(1);
        CountDownLatch threadBWrittenRedis    = new CountDownLatch(1);
        List<Throwable> workerErrors          = new java.util.concurrent.CopyOnWriteArrayList<>();

        // Thread A processes ping with ts=1000
        Thread threadA = new Thread(() -> {
            try {
                boolean accepted = registry.updateLocation(
                        driverId, new com.aagosh.dispatch.domain.GeoPoint(18.0, 78.0), 1000L);
                if (!accepted) {
                    throw new AssertionError("Thread A update at ts=1000 must be accepted locally");
                }
                // Signal that Thread A accepted locally in registry
                threadAAcceptedLocally.countDown();

                // Wait until Thread B has updated registry AND written to Redis
                if (!threadBWrittenRedis.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for Thread B to complete Redis write");
                }

                // Resume: Thread A attempts its stale ts=1000 write to Redis
                redisLocationCache.updateLocation(driverId, 18.0, 78.0, 1000L);
            } catch (Throwable t) {
                workerErrors.add(t);
            }
        }, "worker-thread-A");

        // Thread B processes ping with ts=2000
        Thread threadB = new Thread(() -> {
            try {
                // Wait until Thread A has accepted in registry
                if (!threadAAcceptedLocally.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for Thread A to accept locally");
                }

                // Thread B updates local registry with ts=2000
                boolean accepted = registry.updateLocation(
                        driverId, new com.aagosh.dispatch.domain.GeoPoint(19.0, 79.0), 2000L);
                if (!accepted) {
                    throw new AssertionError("Thread B update at ts=2000 must be accepted locally");
                }

                // Thread B writes newer location to Redis
                redisLocationCache.updateLocation(driverId, 19.0, 79.0, 2000L);
            } catch (Throwable t) {
                workerErrors.add(t);
            }
        }, "worker-thread-B");

        threadA.start();
        threadB.start();

        // Wait until Thread B finishes updating local registry and writing to Redis
        threadAAcceptedLocally.await(10, TimeUnit.SECONDS);
        // Give Thread B a brief moment to complete its Redis write
        threadB.join(5_000);

        String key = RedisLocationCache.keyFor(driverId);
        // Deterministically set TTL to 15 seconds without sleeping
        redisTemplate.expire(key, Duration.ofSeconds(15));
        Long ttlBeforeStaleWrite = redisTemplate.getExpire(key, TimeUnit.SECONDS);

        // Now signal Thread A to proceed with its stale ts=1000 write to Redis
        threadBWrittenRedis.countDown();
        threadA.join(10_000);

        // Verify no thread threw exceptions
        assertTrue(workerErrors.isEmpty(), "Worker threads must succeed without errors: " + workerErrors);
        assertFalse(threadA.isAlive(), "Thread A must be terminated");
        assertFalse(threadB.isAlive(), "Thread B must be terminated");

        // Verify Redis retained Thread B's location (ts=2000)
        Map<Object, Object> redisEntries = redisTemplate.opsForHash().entries(key);
        assertEquals("19.0", redisEntries.get(RedisLocationCache.FIELD_LAT),
                "Redis must retain Thread B's latitude (ts=2000), not Thread A's stale delayed write");
        assertEquals("79.0", redisEntries.get(RedisLocationCache.FIELD_LON));
        assertEquals("2000", redisEntries.get(RedisLocationCache.FIELD_UPDATED_AT));

        // Verify stale write did NOT reset or refresh the TTL
        Long ttlAfterStaleWrite = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttlBeforeStaleWrite);
        assertNotNull(ttlAfterStaleWrite);
        assertTrue(ttlAfterStaleWrite <= ttlBeforeStaleWrite,
                "Stale Redis write must not refresh TTL; before=" + ttlBeforeStaleWrite
                + " after=" + ttlAfterStaleWrite);

        // Verify local DriverRegistry also retained Thread B's location (ts=2000)
        com.aagosh.dispatch.domain.Driver stored = registry.findById(driverId).orElseThrow();
        assertEquals(19.0, stored.location().latitude());
        assertEquals(79.0, stored.location().longitude());
        assertEquals(2000L, stored.updatedAt());
    }
}
