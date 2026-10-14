package com.aagosh.dispatch.config;

import com.aagosh.dispatch.cache.RedisLocationCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Spring configuration for Redis infrastructure beans.
 *
 * <h2>StringRedisTemplate</h2>
 * <p>Spring Boot's {@code RedisAutoConfiguration} provides a {@code StringRedisTemplate} bean
 * automatically when {@code spring-boot-starter-data-redis} is on the classpath and
 * {@code spring.data.redis.host} is configured. No explicit bean is required here.
 *
 * <h2>Lua script</h2>
 * <p>A single {@link RedisScript} bean wrapping {@link RedisLocationCache#LUA_SCRIPT} is
 * registered so that Spring Data Redis can SHA-cache it across executions.
 */
@Configuration
public class RedisConfig {

    /**
     * Lua script bean for the atomic conditional location write.
     *
     * <p>The script is defined in {@link RedisLocationCache#LUA_SCRIPT} and returns a
     * {@link Long}: {@code 1} on accepted write, {@code 0} on rejection (stale timestamp).
     */
    @Bean
    public RedisScript<Long> locationScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(RedisLocationCache.LUA_SCRIPT);
        script.setResultType(Long.class);
        return script;
    }
}
