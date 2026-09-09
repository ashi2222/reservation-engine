package com.ashish.reservation_engine.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final Map<String, String> notificationLog = new ConcurrentHashMap<>();

    public void sendReservationCreatedNotification(Long reservationId, Long resourceId, String userId, Integer quantity) {
        if (userId != null && userId.contains("notification-error")) {
            throw new NotificationProcessingException("Simulated notification service outage for user: " + userId);
        }
        String msg = String.format("Notification [RESERVATION_CREATED]: Reservation %d for resource %d by user %s (qty: %d) processed.",
                reservationId, resourceId, userId, quantity);
        log.info(msg);
        notificationLog.put("reservation-" + reservationId, msg);
    }

    public void sendNotification(String eventType, Long reservationId, String userId, String details) {
        if (userId != null && userId.contains("notification-error")) {
            throw new NotificationProcessingException("Simulated notification service outage for user: " + userId);
        }
        String msg = String.format("Notification [%s]: Reservation %d for user %s - %s",
                eventType, reservationId, userId, details);
        log.info(msg);
        notificationLog.put("reservation-" + reservationId + "-" + eventType, msg);
    }

    public Map<String, String> getNotificationLog() {
        return notificationLog;
    }
}

