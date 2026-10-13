package com.aagosh.dispatch.config;

import com.aagosh.dispatch.matching.AuctionMatcher;
import com.aagosh.dispatch.matching.DispatchCostConfig;
import com.aagosh.dispatch.matching.Matcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;

/**
 * Spring configuration providing default fallback beans for dispatch matching.
 */
@Configuration
public class DispatchConfig {

    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public Matcher matcher() {
        return new AuctionMatcher();
    }

    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public DispatchCostConfig dispatchCostConfig() {
        return DispatchCostConfig.DEFAULT;
    }
}
