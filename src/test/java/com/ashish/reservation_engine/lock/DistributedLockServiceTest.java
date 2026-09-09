package com.ashish.reservation_engine.lock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DistributedLockServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @InjectMocks
    private RedissonDistributedLockService lockService;

    @Test
    @DisplayName("tryLock returns true when lock is successfully acquired")
    void testTryLockSuccess() throws InterruptedException {
        String lockName = "test-lock";
        when(redissonClient.getLock(lockName)).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);

        boolean acquired = lockService.tryLock(lockName);

        assertTrue(acquired);
        verify(lock).tryLock(0, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("tryLock returns false when lock is held by another instance")
    void testTryLockFailure() throws InterruptedException {
        String lockName = "test-lock";
        when(redissonClient.getLock(lockName)).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        boolean acquired = lockService.tryLock(lockName);

        assertFalse(acquired);
        verify(lock).tryLock(0, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("unlock releases lock only when held by current thread")
    void testUnlockWhenHeld() {
        String lockName = "test-lock";
        when(redissonClient.getLock(lockName)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        lockService.unlock(lockName);

        verify(lock).unlock();
    }

    @Test
    @DisplayName("unlock does NOT call unlock when lock is not held by current thread")
    void testUnlockWhenNotHeld() {
        String lockName = "test-lock";
        when(redissonClient.getLock(lockName)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        lockService.unlock(lockName);

        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("tryLock with blank name throws IllegalArgumentException")
    void testBlankLockName() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> lockService.tryLock(""));
    }
}
