package com.aagosh.dispatch;

import com.aagosh.dispatch.api.DriverController;
import com.aagosh.dispatch.api.GlobalExceptionHandler;
import com.aagosh.dispatch.api.RideController;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.persistence.AssignmentPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class DispatchApplicationTests {

    @MockitoBean
    private AssignmentPersistenceService persistenceService;

    @MockitoBean
    private com.aagosh.dispatch.cache.RedisLocationCache redisLocationCache;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DriverRegistry driverRegistry;

    @Test
    void contextLoads() {
    }

    @Test
    void driverRegistryIsASpringManagedBean() {
        assertNotNull(driverRegistry,
                "DriverRegistry must be auto-wired as a Spring component");
    }

    @Test
    void driverControllerIsRegisteredInApplicationContext() {
        assertNotNull(context.getBean(DriverController.class),
                "DriverController must be a Spring-managed @RestController");
    }

    @Test
    void rideControllerIsRegisteredInApplicationContext() {
        assertNotNull(context.getBean(RideController.class),
                "RideController must be a Spring-managed @RestController");
    }

    @Test
    void globalExceptionHandlerIsRegisteredInApplicationContext() {
        assertNotNull(context.getBean(GlobalExceptionHandler.class),
                "GlobalExceptionHandler must be a Spring-managed @RestControllerAdvice");
    }

    @Test
    void driverPingConsumerIsRegisteredInApplicationContext() {
        assertNotNull(context.getBean(com.aagosh.dispatch.messaging.DriverPingConsumer.class),
                "DriverPingConsumer must be a Spring-managed @Component");
    }

    @Test
    void rideRequestConsumerIsRegisteredInApplicationContext() {
        assertNotNull(context.getBean(com.aagosh.dispatch.messaging.RideRequestConsumer.class),
                "RideRequestConsumer must be a Spring-managed @Component");
    }
}
