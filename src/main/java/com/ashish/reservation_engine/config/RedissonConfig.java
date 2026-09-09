package com.ashish.reservation_engine.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfig.class);

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${app.lock.watchdog-timeout-ms:30000}")
    private long watchdogTimeoutMs;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String scheme = sslEnabled ? "rediss://" : "redis://";
        String address = scheme + redisHost + ":" + redisPort;

        config.setLockWatchdogTimeout(watchdogTimeoutMs);

        var singleServerConfig = config.useSingleServer()
                .setAddress(address);

        if (StringUtils.hasText(redisPassword)) {
            singleServerConfig.setPassword(redisPassword);
        }

        log.info("Configuring RedissonClient connecting to {} with watchdogTimeoutMs={}", address, watchdogTimeoutMs);
        return Redisson.create(config);
    }
}

