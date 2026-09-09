package com.ashish.reservation_engine.repository;

import com.ashish.reservation_engine.entity.OutboxEvent;
import com.ashish.reservation_engine.entity.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    List<OutboxEvent> findByStatusAndRetryCountLessThanOrderByCreatedAtAsc(
            OutboxStatus status, int maxRetries, Pageable pageable);

    List<OutboxEvent> findByAggregateTypeAndAggregateId(String aggregateType, String aggregateId);

    @Transactional
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = :targetStatus, e.publishedAt = :publishedAt WHERE e.id = :id AND e.status = :expectedStatus")
    int markAsPublishedConditionally(@Param("id") Long id,
                                    @Param("expectedStatus") OutboxStatus expectedStatus,
                                    @Param("targetStatus") OutboxStatus targetStatus,
                                    @Param("publishedAt") Instant publishedAt);

    @Transactional
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = :status, e.publishedAt = :publishedAt WHERE e.id = :id")
    int markAsPublished(@Param("id") Long id, @Param("status") OutboxStatus status, @Param("publishedAt") Instant publishedAt);

    @Transactional
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.retryCount = e.retryCount + 1, e.lastError = :lastError WHERE e.id = :id AND e.status = 'PENDING'")
    int recordRetryFailure(@Param("id") Long id, @Param("lastError") String lastError);
}

