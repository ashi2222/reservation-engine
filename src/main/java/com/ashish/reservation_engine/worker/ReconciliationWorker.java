package com.ashish.reservation_engine.worker;

import com.ashish.reservation_engine.lock.DistributedLockService;
import com.ashish.reservation_engine.service.ReservationReconciliationService;
import com.ashish.reservation_engine.service.ReservationReconciliationService.ReconciliationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationWorker.class);

    public record ReconciliationSummary(
            int resourcesExamined,
            int resourcesConsistent,
            int driftDetectedCount,
            int driftRepairedCount,
            int failureCount
    ) {
    }

    private final ReservationReconciliationService reconciliationService;
    private final DistributedLockService lockService;

    @Value("${app.worker.reconciliation.enabled:true}")
    private boolean enabled;

    @Value("${app.worker.reconciliation.batch-size:50}")
    private int batchSize;

    @Value("${app.worker.reconciliation.lock-name:reservation-engine:worker:reconciliation}")
    private String lockName;

    public ReconciliationWorker(ReservationReconciliationService reconciliationService,
                                DistributedLockService lockService) {
        this.reconciliationService = reconciliationService;
        this.lockService = lockService;
    }

    @Scheduled(fixedDelayString = "${app.worker.reconciliation.interval-ms:30000}")
    public void runReconciliationJob() {
        if (!enabled) {
            log.trace("ReconciliationWorker is disabled. Skipping scheduled execution.");
            return;
        }

        boolean acquired = lockService.tryLock(lockName);
        if (!acquired) {
            log.debug("Reconciliation worker skipped: distributed lock '{}' is held by another instance", lockName);
            return;
        }

        try {
            log.info("Acquired distributed lock '{}'. Executing reconciliation worker run.", lockName);
            processReconciliation();
            log.info("Reconciliation worker run completed.");
        } finally {
            lockService.unlock(lockName);
        }
    }

    public ReconciliationSummary processReconciliation() {
        log.info("Reconciliation worker started run");

        int safeBatchSize = batchSize > 0 ? batchSize : 50;
        int page = 0;
        int totalExamined = 0;
        int consistentCount = 0;
        int driftDetectedCount = 0;
        int driftRepairedCount = 0;
        int failureCount = 0;

        try {
            while (true) {
                Pageable pageable = PageRequest.of(page, safeBatchSize);
                List<Long> resourceIds = reconciliationService.getResourceIdsBatch(pageable);

                if (resourceIds == null || resourceIds.isEmpty()) {
                    break;
                }

                for (Long resourceId : resourceIds) {
                    totalExamined++;
                    try {
                        ReconciliationResult result = reconciliationService.reconcileResource(resourceId);
                        if (result.driftDetected()) {
                            driftDetectedCount++;
                            if (result.corrected()) {
                                driftRepairedCount++;
                            }
                            log.info("Drift detected and repaired for resourceId={}: pgAvailable={}, redisBefore={}, redisAfter={}",
                                    resourceId, result.postgreSqlAvailableCapacity(), result.redisCapacityBefore(), result.redisCapacityAfter());
                        } else {
                            consistentCount++;
                            log.debug("Resource id={} is already consistent (availableCapacity={})",
                                    resourceId, result.postgreSqlAvailableCapacity());
                        }
                    } catch (Exception ex) {
                        failureCount++;
                        log.error("Failed to reconcile resourceId={}: {}", resourceId, ex.getMessage(), ex);
                    }
                }

                if (resourceIds.size() < safeBatchSize) {
                    break;
                }
                page++;
            }
        } catch (Exception ex) {
            log.error("Unexpected error during ReconciliationWorker execution: {}", ex.getMessage(), ex);
        }

        log.info("Reconciliation worker completed run: examined={}, consistent={}, driftDetected={}, repaired={}, failures={}",
                totalExamined, consistentCount, driftDetectedCount, driftRepairedCount, failureCount);

        return new ReconciliationSummary(totalExamined, consistentCount, driftDetectedCount, driftRepairedCount, failureCount);
    }
}