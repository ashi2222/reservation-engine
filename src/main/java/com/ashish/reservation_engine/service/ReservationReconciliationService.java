package com.ashish.reservation_engine.service;

import com.ashish.reservation_engine.entity.Reservation;
import com.ashish.reservation_engine.entity.ReservationStatus;
import com.ashish.reservation_engine.entity.Resource;
import com.ashish.reservation_engine.redis.RedisResourceService;
import com.ashish.reservation_engine.repository.ReservationRepository;
import com.ashish.reservation_engine.repository.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class ReservationReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationReconciliationService.class);

    private static final List<ReservationStatus> ACTIVE_STATUSES = List.of(
            ReservationStatus.HELD,
            ReservationStatus.PAYMENT_PENDING,
            ReservationStatus.CONFIRMED
    );

    public record ReconciliationResult(
            Long resourceId,
            Integer totalCapacity,
            Integer activeReservedCapacity,
            Integer postgreSqlAvailableCapacity,
            Integer expectedAvailableCapacity,
            Integer redisCapacityBefore,
            Integer redisCapacityAfter,
            boolean driftDetected,
            int driftAmount,
            boolean corrected
    ) {
    }

    private final ResourceRepository resourceRepository;
    private final ReservationRepository reservationRepository;
    private final RedisResourceService redisResourceService;

    public ReservationReconciliationService(ResourceRepository resourceRepository,
                                            ReservationRepository reservationRepository,
                                            RedisResourceService redisResourceService) {
        this.resourceRepository = resourceRepository;
        this.reservationRepository = reservationRepository;
        this.redisResourceService = redisResourceService;
    }

    @Transactional
    public ReconciliationResult reconcileResource(Long resourceId) {
        if (resourceId == null) {
            throw new IllegalArgumentException("Resource ID cannot be null");
        }

        Resource resource = resourceRepository.findByIdForUpdate(resourceId)
                .orElseThrow(() -> new NoSuchElementException("Resource not found with id: " + resourceId));

        // 1. Calculate active capacity from authoritative PostgreSQL reservations (HELD, PAYMENT_PENDING, CONFIRMED)
        List<Reservation> activeReservations = reservationRepository.findByResourceIdAndStatusIn(resourceId, ACTIVE_STATUSES);
        int activeReservedCapacity = activeReservations.stream()
                .mapToInt(Reservation::getQuantity)
                .sum();

        int expectedAvailableCapacity = resource.getTotalCapacity() - activeReservedCapacity;

        // 2. Verify and enforce PostgreSQL internal invariant: availableCapacity + activeCapacity == totalCapacity
        boolean pgInconsistency = false;
        if (!resource.getAvailableCapacity().equals(expectedAvailableCapacity)) {
            log.warn("PostgreSQL internal capacity drift detected for resourceId={}: current availableCapacity={}, expected availableCapacity={}. Correcting PostgreSQL.",
                    resourceId, resource.getAvailableCapacity(), expectedAvailableCapacity);
            resource.setAvailableCapacity(expectedAvailableCapacity);
            resourceRepository.save(resource);
            pgInconsistency = true;
        }

        // 3. Read Redis capacity
        Integer redisCapacityBefore = redisResourceService.getAvailableCapacity(resourceId);

        // 4. Detect Redis capacity inconsistencies
        boolean redisDrift = (redisCapacityBefore == null) || (!redisCapacityBefore.equals(resource.getAvailableCapacity()));
        boolean driftDetected = pgInconsistency || redisDrift;

        int driftAmount = 0;
        if (redisCapacityBefore != null) {
            driftAmount = redisCapacityBefore - resource.getAvailableCapacity();
        }

        // 5. Correct Redis capacity from PostgreSQL authoritative state
        if (driftDetected) {
            log.info("Correcting Redis capacity for resourceId={}. Before={}, Authoritative={}",
                    resourceId, redisCapacityBefore, resource.getAvailableCapacity());
        }

        redisResourceService.syncResourceFromDatabase(resource);
        Integer redisCapacityAfter = redisResourceService.getAvailableCapacity(resourceId);

        return new ReconciliationResult(
                resourceId,
                resource.getTotalCapacity(),
                activeReservedCapacity,
                resource.getAvailableCapacity(),
                expectedAvailableCapacity,
                redisCapacityBefore,
                redisCapacityAfter,
                driftDetected,
                driftAmount,
                true
        );
    }

    @Transactional(readOnly = true)
    public List<Long> getResourceIdsBatch(Pageable pageable) {
        return resourceRepository.findAllResourceIds(pageable);
    }

    @Transactional
    public List<ReconciliationResult> reconcileAllResources() {
        List<Resource> allResources = resourceRepository.findAll();
        List<ReconciliationResult> results = new ArrayList<>();
        for (Resource resource : allResources) {
            results.add(reconcileResource(resource.getId()));
        }
        return results;
    }
}

