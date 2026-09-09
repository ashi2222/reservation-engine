package com.ashish.reservation_engine.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class RedisAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(RedisAdmissionService.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> admissionRedisScript;

    public RedisAdmissionService(StringRedisTemplate redisTemplate,
                                 @Qualifier("admissionRedisScript") RedisScript<List> admissionRedisScript) {
        this.redisTemplate = redisTemplate;
        this.admissionRedisScript = admissionRedisScript;
    }

    public RedisAdmissionResult admitReservation(Long resourceId, String userId, Integer quantity, String idempotencyKey, Duration holdDuration) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId cannot be null");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be null or blank");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey cannot be null or blank");
        }

        long ttlSeconds = holdDuration != null ? holdDuration.toSeconds() : 600;

        String idempKey = RedisKeyBuilder.buildIdempotencyKey(idempotencyKey);
        String capacityKey = RedisKeyBuilder.buildResourceCapacityKey(resourceId);
        String holdKey = RedisKeyBuilder.buildReservationHoldKey(idempotencyKey);

        List<String> keys = List.of(idempKey, capacityKey, holdKey);
        String[] args = new String[]{
                String.valueOf(quantity),
                String.valueOf(ttlSeconds),
                String.valueOf(resourceId),
                userId,
                idempotencyKey
        };

        try {
            @SuppressWarnings("unchecked")
            List<Object> result = redisTemplate.execute(admissionRedisScript, keys, (Object[]) args);

            if (result == null || result.isEmpty()) {
                return RedisAdmissionResult.error("Empty response from Redis admission script");
            }

            String status = String.valueOf(result.get(0));
            switch (status) {
                case "NEWLY_ADMITTED":
                    String payload = result.size() > 1 ? String.valueOf(result.get(1)) : null;
                    Integer remaining = result.size() > 2 ? Integer.valueOf(String.valueOf(result.get(2))) : null;
                    return RedisAdmissionResult.newlyAdmitted(payload, remaining);

                case "IDEMPOTENT":
                    String existingPayload = result.size() > 1 ? String.valueOf(result.get(1)) : null;
                    return RedisAdmissionResult.idempotent(existingPayload);

                case "INSUFFICIENT_CAPACITY":
                    Integer current = result.size() > 1 ? Integer.valueOf(String.valueOf(result.get(1))) : 0;
                    return RedisAdmissionResult.insufficientCapacity(current);

                case "RESOURCE_NOT_FOUND":
                    String msg = result.size() > 1 ? String.valueOf(result.get(1)) : "Resource not found in Redis";
                    return RedisAdmissionResult.resourceNotFound(msg);

                case "INVALID_QUANTITY":
                    String invMsg = result.size() > 1 ? String.valueOf(result.get(1)) : "Invalid quantity";
                    return RedisAdmissionResult.invalidQuantity(invMsg);

                default:
                    return RedisAdmissionResult.error("Unexpected admission status: " + status);
            }
        } catch (Exception ex) {
            log.error("Failed to execute Redis admission script for resourceId={}, idempotencyKey={}", resourceId, idempotencyKey, ex);
            return RedisAdmissionResult.error("Redis execution error: " + ex.getMessage());
        }
    }

    public void compensateAdmission(Long resourceId, Integer quantity, String idempotencyKey) {
        log.warn("Triggering Redis admission compensation for resourceId={}, quantity={}, idempotencyKey={}", resourceId, quantity, idempotencyKey);
        try {
            String capacityKey = RedisKeyBuilder.buildResourceCapacityKey(resourceId);
            String idempKey = RedisKeyBuilder.buildIdempotencyKey(idempotencyKey);
            String holdKey = RedisKeyBuilder.buildReservationHoldKey(idempotencyKey);

            if (quantity != null && quantity > 0) {
                redisTemplate.opsForValue().increment(capacityKey, quantity);
            }
            redisTemplate.delete(List.of(idempKey, holdKey));
            log.info("Redis admission compensation completed successfully for idempotencyKey={}", idempotencyKey);
        } catch (Exception ex) {
            log.error("Failed to compensate Redis admission for resourceId={}, idempotencyKey={}", resourceId, idempotencyKey, ex);
        }
    }
}
