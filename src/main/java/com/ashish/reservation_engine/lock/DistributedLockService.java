package com.ashish.reservation_engine.lock;

import java.util.concurrent.TimeUnit;

public interface DistributedLockService {

    boolean tryLock(String lockName);

    boolean tryLock(String lockName, long waitTime, TimeUnit timeUnit);

    void unlock(String lockName);

    boolean isLocked(String lockName);

    boolean isHeldByCurrentThread(String lockName);
}

