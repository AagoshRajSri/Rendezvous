package com.aagosh.dispatch.domain;

import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DriverStateConcurrencyTest {

    @RepeatedTest(20)
    void onlyOneOfFiftyContendersCanClaimDriver() throws Exception {
        int contenders = 50;

        DriverState driver = new DriverState();
        ExecutorService executor = Executors.newFixedThreadPool(contenders);

        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successfulClaims = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < contenders; i++) {
                final String requestId = "request-" + i;

                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    if (driver.tryClaim(requestId)) {
                        successfulClaims.incrementAndGet();
                    }

                    return null;
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS),
                    "Workers did not become ready in time");

            start.countDown();

            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            assertEquals(1, successfulClaims.get());
            assertEquals(MatchStatus.CLAIMED, driver.getStatus());
            assertNotNull(driver.getClaimedByRequestId());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Executor did not terminate");
        }
    }
}
