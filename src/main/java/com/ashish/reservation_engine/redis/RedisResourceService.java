package com.ashish.reservation_engine.redis;

import com.ashish.reservation_engine.entity.Resource;
import com.ashish.reservation_engine.repository.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class RedisResourceService {

    private static final Logger log = LoggerFactory.getLogger(RedisResourceService.class);

    private final StringRedisTemplate redisTemplate;
    private final ResourceRepository resourceRepository;
    private final RedisScript<Long> restoreCapacityRedisScript;

    public RedisResourceService(StringRedisTemplate redisTemplate,
                                ResourceRepository resourceRepository,
                                @Qualifier("restoreCapacityRedisScript") RedisScript<Long> restoreCapacityRedisScript) {
        this.redisTemplate = redisTemplate;
        this.resourceRepository = resourceRepository;
        this.restoreCapacityRedisScript = restoreCapacityRedisScript;
    }

    public void initializeCapacity(Long resourceId, Integer availableCapacity) {
        if (resourceId == null) {
            throw new IllegalArgumentException("Resource ID cannot be null");
        }
        if (availableCapacity == null || availableCapacity < 0) {
            throw new IllegalArgumentException("Available capacity cannot be null or negative");
        }
        String key = RedisKeyBuilder.buildResourceCapacityKey(resourceId);
        redisTemplate.opsForValue().set(key, String.valueOf(availableCapacity));
    }

    public Integer getAvailableCapacity(Long resourceId) {
        if (resourceId == null) {
            throw new IllegalArgumentException("Resource ID cannot be null");
        }
        String key = RedisKeyBuilder.buildResourceCapacityKey(resourceId);
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Integer.valueOf(val) : null;
    }

    public boolean hasResourceCapacity(Long resourceId) {
        if (resourceId == null) {
            return false;
        }
        String key = RedisKeyBuilder.buildResourceCapacityKey(resourceId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public Long restoreCapacity(Long resourceId, Integer quantity, Integer totalCapacity) {
        if (resourceId == null || quantity == null || quantity <= 0) {
            return null;
        }
        String key = RedisKeyBuilder.buildResourceCapacityKey(resourceId);
        List<String> keys = List.of(key);
        String[] args = new String[]{
                String.valueOf(quantity),
                totalCapacity != null ? String.valueOf(totalCapacity) : "0"
        };
        try {
            Long result = redisTemplate.execute(restoreCapacityRedisScript, keys, (Object[]) args);
            if (result != null && result == -1L) {
                log.warn("Capacity key {} did not exist during restore. Syncing from PostgreSQL.", key);
                syncResourceFromDatabase(resourceId);
                return (long) getAvailableCapacity(resourceId);
            }
            return result;
        } catch (Exception ex) {
            log.error("Failed to execute restoreCapacity script for resourceId={}, quantity={}", resourceId, quantity, ex);
            throw new IllegalStateException("Redis capacity restoration failed: " + ex.getMessage(), ex);
        }
    }

    public void restoreCapacity(Long resourceId, Integer quantity) {
        restoreCapacity(resourceId, quantity, null);
    }

    public void syncResourceFromDatabase(Resource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("Resource cannot be null");
        }
        initializeCapacity(resource.getId(), resource.getAvailableCapacity());
    }

    public void syncResourceFromDatabase(Long resourceId) {
        if (resourceId == null) {
            throw new IllegalArgumentException("Resource ID cannot be null");
        }
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new NoSuchElementException("Resource not found with id: " + resourceId));
        syncResourceFromDatabase(resource);
    }

    public void deleteResourceCapacity(Long resourceId) {
        if (resourceId != null) {
            String key = RedisKeyBuilder.buildResourceCapacityKey(resourceId);
            redisTemplate.delete(key);
        }
    }
}
