package com.ashish.reservation_engine.worker;

import com.ashish.reservation_engine.lock.DistributedLockService;
import com.ashish.reservation_engine.service.ReservationExpirationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpiryWorkerTest {

    @Mock
    private ReservationExpirationService expirationService;

    @Mock
    private DistributedLockService lockService;

    @InjectMocks
    private ExpiryWorker expiryWorker;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(expiryWorker, "enabled", true);
        ReflectionTestUtils.setField(expiryWorker, "batchSize", 50);
        ReflectionTestUtils.setField(expiryWorker, "lockName", "reservation-engine:worker:expiry");
    }

    @Test
    @DisplayName("Scheduled run acquires lock, executes expiration, and releases lock")
    void testScheduledRunAcquiresLockAndExpires() {
        when(lockService.tryLock("reservation-engine:worker:expiry")).thenReturn(true);
        when(expirationService.expireExpiredHoldsBatch(any(), anyInt())).thenReturn(0);

        expiryWorker.runExpiryJob();

        verify(lockService).tryLock("reservation-engine:worker:expiry");
        verify(expirationService).expireExpiredHoldsBatch(any(), anyInt());
        verify(lockService).unlock("reservation-engine:worker:expiry");
    }

    @Test
    @DisplayName("Scheduled run skips execution when distributed lock cannot be acquired")
    void testScheduledRunSkipsWhenLockNotAcquired() {
        when(lockService.tryLock("reservation-engine:worker:expiry")).thenReturn(false);

        expiryWorker.runExpiryJob();

        verify(lockService).tryLock("reservation-engine:worker:expiry");
        verify(expirationService, never()).expireExpiredHoldsBatch(any(), anyInt());
        verify(lockService, never()).unlock(any());
    }

    @Test
    @DisplayName("Scheduled run skips execution when enabled is false")
    void testDisabledWorkerSkipsExecution() {
        ReflectionTestUtils.setField(expiryWorker, "enabled", false);

        expiryWorker.runExpiryJob();

        verify(lockService, never()).tryLock(any());
        verify(expirationService, never()).expireExpiredHoldsBatch(any(), anyInt());
    }

    @Test
    @DisplayName("processExpiredReservations iterates until batch returns less than batch size")
    void testProcessExpiredReservationsBatches() {
        ReflectionTestUtils.setField(expiryWorker, "batchSize", 10);
        when(expirationService.expireExpiredHoldsBatch(any(), anyInt()))
                .thenReturn(10)
                .thenReturn(5);

        int total = expiryWorker.processExpiredReservations();

        assertEquals(15, total);
    }
}

