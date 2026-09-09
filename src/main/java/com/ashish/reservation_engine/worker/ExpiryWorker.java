package com.ashish.reservation_engine.worker;

import com.ashish.reservation_engine.lock.DistributedLockService;
import com.ashish.reservation_engine.service.ReservationExpirationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ExpiryWorker {

    private static final Logger log = LoggerFactory.getLogger(ExpiryWorker.class);

    private final ReservationExpirationService expirationService;
    private final DistributedLockService lockService;

    @Value("${app.worker.expiry.enabled:true}")
    private boolean enabled;

    @Value("${app.worker.expiry.batch-size:50}")
    private int batchSize;

    @Value("${app.worker.expiry.lock-name:reservation-engine:worker:expiry}")
    private String lockName;

    public ExpiryWorker(ReservationExpirationService expirationService,
                        DistributedLockService lockService) {
        this.expirationService = expirationService;
        this.lockService = lockService;
    }

    @Scheduled(fixedDelayString = "${app.worker.expiry.interval-ms:5000}")
    public void runExpiryJob() {
        if (!enabled) {
            return;
        }

        boolean acquired = lockService.tryLock(lockName);
        if (!acquired) {
            log.debug("Expiry worker skipped: distributed lock '{}' is held by another instance", lockName);
            return;
        }

        try {
            log.info("Acquired distributed lock '{}'. Executing expiry worker run.", lockName);
            processExpiredReservations();
            log.info("Expiry worker run completed.");
        } finally {
            lockService.unlock(lockName);
        }
    }

    public int processExpiredReservations() {
        try {
            int totalExpired = 0;
            int batchCount;
            Instant now = Instant.now();

            do {
                batchCount = expirationService.expireExpiredHoldsBatch(now, batchSize);
                totalExpired += batchCount;
            } while (batchCount == batchSize);

            if (totalExpired > 0) {
                log.info("ExpiryWorker completed run: successfully expired {} reservations", totalExpired);
            }
            return totalExpired;
        } catch (Exception ex) {
            log.error("Unexpected error in ExpiryWorker execution: {}", ex.getMessage(), ex);
            return 0;
        }
    }
}