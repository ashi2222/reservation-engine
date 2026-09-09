package com.ashish.reservation_engine.messaging.consumer;

import com.ashish.reservation_engine.messaging.event.ReservationEventMessage;
import com.ashish.reservation_engine.payment.PaymentProcessorService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentWorker.class);

    private final PaymentProcessorService paymentProcessorService;
    private final ObjectMapper objectMapper;

    public PaymentWorker(PaymentProcessorService paymentProcessorService, ObjectMapper objectMapper) {
        this.paymentProcessorService = paymentProcessorService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.reservation-events:reservation-events}",
            groupId = "${app.kafka.consumer.payment-group-id:payment-worker-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeReservationEvent(ConsumerRecord<String, String> record) {
        log.info("PaymentWorker received event: partition={}, offset={}, key={}",
                record.partition(), record.offset(), record.key());

        try {
            ReservationEventMessage event = objectMapper.readValue(record.value(), ReservationEventMessage.class);

            if (!"RESERVATION_CREATED".equalsIgnoreCase(event.getEventType())) {
                log.debug("PaymentWorker skipping event type: {}", event.getEventType());
                return;
            }

            Long reservationId = null;
            if (event.getAggregateId() != null) {
                try {
                    reservationId = Long.valueOf(event.getAggregateId());
                } catch (NumberFormatException ignored) {
                }
            }

            if (reservationId == null && event.getEventPayload() != null && !event.getEventPayload().isBlank()) {
                try {
                    JsonNode node = objectMapper.readTree(event.getEventPayload());
                    JsonNode idNode = node.get("reservationId");
                    if (idNode != null && !idNode.isNull()) {
                        reservationId = idNode.asLong();
                    }
                } catch (Exception ignored) {
                }
            }

            if (reservationId == null) {
                log.error("PaymentWorker cannot resolve reservationId from payload: {}", record.value());
                return;
            }

            paymentProcessorService.processReservationPayment(reservationId);

        } catch (Exception ex) {
            log.error("PaymentWorker processing error at offset {}: {}", record.offset(), ex.getMessage(), ex);
            throw new RuntimeException("Payment processing failed for record at offset " + record.offset() + ": " + ex.getMessage(), ex);
        }
    }
}

