package com.ashish.reservation_engine.messaging.consumer;

import com.ashish.reservation_engine.messaging.event.ReservationEventMessage;
import com.ashish.reservation_engine.notification.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public NotificationWorker(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.reservation-events:reservation-events}",
            groupId = "${app.kafka.consumer.notification-group-id:notification-worker-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeReservationEvent(ConsumerRecord<String, String> record) {
        log.info("NotificationWorker received event: partition={}, offset={}, key={}",
                record.partition(), record.offset(), record.key());

        try {
            ReservationEventMessage event = objectMapper.readValue(record.value(), ReservationEventMessage.class);

            Long reservationId = null;
            if (event.getAggregateId() != null) {
                try {
                    reservationId = Long.valueOf(event.getAggregateId());
                } catch (NumberFormatException ignored) {
                }
            }

            String userId = null;
            Integer quantity = null;
            if (event.getEventPayload() != null && !event.getEventPayload().isBlank()) {
                try {
                    JsonNode node = objectMapper.readTree(event.getEventPayload());
                    JsonNode resIdNode = node.get("reservationId");
                    if (reservationId == null && resIdNode != null && !resIdNode.isNull()) {
                        reservationId = resIdNode.asLong();
                    }
                    JsonNode userIdNode = node.get("userId");
                    if (userIdNode != null && !userIdNode.isNull()) {
                        userId = userIdNode.asString();
                    }
                    JsonNode qtyNode = node.get("quantity");
                    if (qtyNode != null && !qtyNode.isNull()) {
                        quantity = qtyNode.asInt();
                    }
                } catch (Exception ignored) {
                }
            }

            notificationService.sendReservationCreatedNotification(
                    reservationId, event.getResourceId(), userId, quantity);

        } catch (Exception ex) {
            log.error("NotificationWorker processing error at offset {}: {}", record.offset(), ex.getMessage(), ex);
            throw new RuntimeException("Notification processing failed for record at offset " + record.offset() + ": " + ex.getMessage(), ex);
        }
    }
}

