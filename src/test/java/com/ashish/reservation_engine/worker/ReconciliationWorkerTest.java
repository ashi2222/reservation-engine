package com.ashish.reservation_engine.worker;

import com.ashish.reservation_engine.lock.DistributedLockService;
import com.ashish.reservation_engine.service.ReservationReconciliationService;
import com.ashish.reservation_engine.service.ReservationReconciliationService.ReconciliationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationWorkerTest {

    @Mock
    private ReservationReconciliationService reconciliationService;

    @Mock
    private DistributedLockService lockService;

    @InjectMocks
    private ReconciliationWorker reconciliationWorker;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(reconciliationWorker, "enabled", true);
        ReflectionTestUtils.setField(reconciliationWorker, "batchSize", 2);
        ReflectionTestUtils.setField(reconciliationWorker, "lockName", "reservation-engine:worker:reconciliation");
    }

    @Test
    @DisplayName("Scheduled run acquires lock, executes reconciliation, and releases lock")
    void testScheduledRunAcquiresLockAndReconciles() {
        when(lockService.tryLock("reservation-engine:worker:reconciliation")).thenReturn(true);
        when(reconciliationService.getResourceIdsBatch(PageRequest.of(0, 2)))
                .thenReturn(Collections.emptyList());

        reconciliationWorker.runReconciliationJob();

        verify(lockService).tryLock("reservation-engine:worker:reconciliation");
        verify(reconciliationService).getResourceIdsBatch(PageRequest.of(0, 2));
        verify(lockService).unlock("reservation-engine:worker:reconciliation");
    }

    @Test
    @DisplayName("Scheduled run skips execution when distributed lock cannot be acquired")
    void testScheduledRunSkipsWhenLockNotAcquired() {
        when(lockService.tryLock("reservation-engine:worker:reconciliation")).thenReturn(false);

        reconciliationWorker.runReconciliationJob();

        verify(lockService).tryLock("reservation-engine:worker:reconciliation");
        verify(reconciliationService, never()).getResourceIdsBatch(any());
        verify(lockService, never()).unlock(any());
    }

    @Test
    @DisplayName("Worker reconciles resources across multiple batches and records metrics")
    void testReconciliationBatchSuccess() {
        when(reconciliationService.getResourceIdsBatch(PageRequest.of(0, 2)))
                .thenReturn(List.of(1L, 2L));
        when(reconciliationService.getResourceIdsBatch(PageRequest.of(1, 2)))
                .thenReturn(List.of(3L));

        // Resource 1: consistent
        when(reconciliationService.reconcileResource(1L)).thenReturn(
                new ReconciliationResult(1L, 100, 20, 80, 80, 80, 80, false, 0, true)
        );

        // Resource 2: drift detected and corrected
        when(reconciliationService.reconcileResource(2L)).thenReturn(
                new ReconciliationResult(2L, 50, 10, 40, 40, 35, 40, true, -5, true)
        );

        // Resource 3: consistent
        when(reconciliationService.reconcileResource(3L)).thenReturn(
                new ReconciliationResult(3L, 20, 0, 20, 20, 20, 20, false, 0, true)
        );

        ReconciliationWorker.ReconciliationSummary summary = reconciliationWorker.processReconciliation();

        assertEquals(3, summary.resourcesExamined());
        assertEquals(2, summary.resourcesConsistent());
        assertEquals(1, summary.driftDetectedCount());
        assertEquals(1, summary.driftRepairedCount());
        assertEquals(0, summary.failureCount());

        verify(reconciliationService).reconcileResource(1L);
        verify(reconciliationService).reconcileResource(2L);
        verify(reconciliationService).reconcileResource(3L);
    }

    @Test
    @DisplayName("Failure reconciling one resource does not abort reconciliation of remaining resources")
    void testIndividualResourceFailureIsolation() {
        when(reconciliationService.getResourceIdsBatch(PageRequest.of(0, 2)))
                .thenReturn(List.of(10L, 20L));

        when(reconciliationService.reconcileResource(10L))
                .thenThrow(new RuntimeException("Database lock timeout on resource 10"));

        when(reconciliationService.reconcileResource(20L)).thenReturn(
                new ReconciliationResult(20L, 100, 10, 90, 90, 90, 90, false, 0, true)
        );

        ReconciliationWorker.ReconciliationSummary summary = reconciliationWorker.processReconciliation();

        assertEquals(2, summary.resourcesExamined());
        assertEquals(1, summary.resourcesConsistent());
        assertEquals(0, summary.driftDetectedCount());
        assertEquals(0, summary.driftRepairedCount());
        assertEquals(1, summary.failureCount());

        verify(reconciliationService).reconcileResource(10L);
        verify(reconciliationService).reconcileResource(20L);
    }

    @Test
    @DisplayName("Worker skips execution when enabled flag is false")
    void testDisabledWorkerSkipsExecution() {
        ReflectionTestUtils.setField(reconciliationWorker, "enabled", false);

        reconciliationWorker.runReconciliationJob();

        verify(lockService, never()).tryLock(any());
        verify(reconciliationService, never()).getResourceIdsBatch(any());
        verify(reconciliationService, never()).reconcileResource(any());
    }

    @Test
    @DisplayName("Empty database yields 0 examined resources")
    void testEmptyResources() {
        when(reconciliationService.getResourceIdsBatch(PageRequest.of(0, 2)))
                .thenReturn(Collections.emptyList());

        ReconciliationWorker.ReconciliationSummary summary = reconciliationWorker.processReconciliation();

        assertEquals(0, summary.resourcesExamined());
        assertEquals(0, summary.resourcesConsistent());
        assertEquals(0, summary.driftDetectedCount());
        assertEquals(0, summary.driftRepairedCount());
        assertEquals(0, summary.failureCount());
    }
}

