package com.ashish.reservation_engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public RedisScript<List> admissionRedisScript() {
        Resource scriptSource = new ClassPathResource("scripts/admission.lua");
        return RedisScript.of(scriptSource, List.class);
    }

    @Bean
    public RedisScript<Long> restoreCapacityRedisScript() {
        Resource scriptSource = new ClassPathResource("scripts/restore_capacity.lua");
        return RedisScript.of(scriptSource, Long.class);
    }
}
