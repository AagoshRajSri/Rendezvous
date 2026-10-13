package com.aagosh.dispatch.config;

import com.aagosh.dispatch.api.RideController;
import com.aagosh.dispatch.domain.Assignment;
import com.aagosh.dispatch.domain.Driver;
import com.aagosh.dispatch.domain.DriverRegistry;
import com.aagosh.dispatch.domain.RideRequest;
import com.aagosh.dispatch.matching.AuctionMatcher;
import com.aagosh.dispatch.matching.CostFunction;
import com.aagosh.dispatch.matching.DispatchCostConfig;
import com.aagosh.dispatch.matching.Matcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DispatchConfig.class, DriverRegistry.class, RideController.class);

    @Test
    @DisplayName("Default configuration injects AuctionMatcher and default cost config when no custom beans exist")
    void defaultConfigurationInjectsDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            RideController controller = context.getBean(RideController.class);
            Matcher injectedMatcher = (Matcher) ReflectionTestUtils.getField(controller, "matcher");
            DispatchCostConfig injectedCostConfig = (DispatchCostConfig) ReflectionTestUtils.getField(controller, "costConfig");

            assertThat(injectedMatcher).isInstanceOf(AuctionMatcher.class);
            assertThat(injectedCostConfig).isEqualTo(DispatchCostConfig.DEFAULT);
        });
    }

    @Test
    @DisplayName("User-supplied custom Matcher bean is selected without ambiguous injection")
    void customMatcherOverridesDefaultWithoutAmbiguity() {
        contextRunner.withUserConfiguration(CustomMatcherConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            RideController controller = context.getBean(RideController.class);
            Matcher injectedMatcher = (Matcher) ReflectionTestUtils.getField(controller, "matcher");

            assertThat(injectedMatcher).isInstanceOf(CustomTestMatcher.class);
        });
    }

    @Test
    @DisplayName("User-supplied custom DispatchCostConfig bean is selected without ambiguous injection")
    void customCostConfigOverridesDefaultWithoutAmbiguity() {
        contextRunner.withUserConfiguration(CustomCostConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            RideController controller = context.getBean(RideController.class);
            DispatchCostConfig injectedCostConfig = (DispatchCostConfig) ReflectionTestUtils.getField(controller, "costConfig");

            assertThat(injectedCostConfig).isNotEqualTo(DispatchCostConfig.DEFAULT);
            assertThat(injectedCostConfig.wEta()).isEqualTo(5.0);
        });
    }

    @Configuration
    static class CustomMatcherConfig {
        @Bean
        public Matcher customMatcher() {
            return new CustomTestMatcher();
        }
    }

    @Configuration
    static class CustomCostConfig {
        @Bean
        public DispatchCostConfig customCostConfig() {
            return new DispatchCostConfig(5.0, 1.0, 0.0, 45.0, 1.5, 0.75, 15);
        }
    }

    static class CustomTestMatcher implements Matcher {
        @Override
        public List<Assignment> match(List<RideRequest> requests, List<Driver> drivers, CostFunction costFunction) {
            return List.of();
        }
    }
}
