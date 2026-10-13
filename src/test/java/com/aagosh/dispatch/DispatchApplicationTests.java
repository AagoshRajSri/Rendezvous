package com.aagosh.dispatch;

import com.aagosh.dispatch.domain.DriverRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class DispatchApplicationTests {

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

}
