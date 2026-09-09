package com.ashish.reservation_engine.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RedissonDistributedLockService implements DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(RedissonDistributedLockService.class);

    private final RedissonClient redissonClient;

    public RedissonDistributedLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public boolean tryLock(String lockName) {
        return tryLock(lockName, 0, TimeUnit.SECONDS);
    }

    @Override
    public boolean tryLock(String lockName, long waitTime, TimeUnit timeUnit) {
        if (lockName == null || lockName.isBlank()) {
            throw new IllegalArgumentException("Lock name cannot be null or blank");
        }
        try {
            RLock lock = redissonClient.getLock(lockName);
            boolean acquired = lock.tryLock(waitTime, timeUnit);
            if (acquired) {
                log.info("Acquired distributed lock '{}'", lockName);
            } else {
                log.debug("Distributed lock '{}' is currently held by another instance. Acquisition skipped.", lockName);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Thread interrupted while attempting to acquire distributed lock '{}'", lockName);
            return false;
        } catch (Exception ex) {
            log.error("Failed to acquire distributed lock '{}': {}", lockName, ex.getMessage(), ex);
            return false;
        }
    }

    @Override
    public void unlock(String lockName) {
        if (lockName == null || lockName.isBlank()) {
            return;
        }
        try {
            RLock lock = redissonClient.getLock(lockName);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("Released distributed lock '{}'", lockName);
            } else {
                log.debug("Current thread does not hold distributed lock '{}'. Skipping release.", lockName);
            }
        } catch (Exception ex) {
            log.error("Failed to release distributed lock '{}': {}", lockName, ex.getMessage(), ex);
        }
    }

    @Override
    public boolean isLocked(String lockName) {
        if (lockName == null || lockName.isBlank()) {
            return false;
        }
        try {
            return redissonClient.getLock(lockName).isLocked();
        } catch (Exception ex) {
            log.error("Failed to check lock status for '{}': {}", lockName, ex.getMessage(), ex);
            return false;
        }
    }

    @Override
    public boolean isHeldByCurrentThread(String lockName) {
        if (lockName == null || lockName.isBlank()) {
            return false;
        }
        try {
            return redissonClient.getLock(lockName).isHeldByCurrentThread();
        } catch (Exception ex) {
            log.error("Failed to check thread ownership for distributed lock '{}': {}", lockName, ex.getMessage(), ex);
            return false;
        }
    }
}

