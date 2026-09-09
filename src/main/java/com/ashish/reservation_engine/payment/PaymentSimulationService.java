package com.ashish.reservation_engine.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PaymentSimulationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSimulationService.class);

    public enum SimulationMode {
        DEFAULT,
        FORCE_SUCCESS,
        FORCE_DECLINE,
        FORCE_PROCESSING_FAILURE
    }

    private volatile SimulationMode defaultMode = SimulationMode.DEFAULT;
    private final Map<Long, SimulationMode> reservationOverrides = new ConcurrentHashMap<>();

    public void setGlobalMode(SimulationMode mode) {
        this.defaultMode = mode;
    }

    public void setOverrideForReservation(Long reservationId, SimulationMode mode) {
        reservationOverrides.put(reservationId, mode);
    }

    public void clearOverrides() {
        reservationOverrides.clear();
        this.defaultMode = SimulationMode.DEFAULT;
    }

    public PaymentResult processPayment(Long reservationId, String userId, Integer quantity) {
        SimulationMode mode = reservationOverrides.getOrDefault(reservationId, defaultMode);

        if (mode == SimulationMode.FORCE_PROCESSING_FAILURE) {
            throw new PaymentProcessingException("Simulated technical processing failure for reservation: " + reservationId);
        }
        if (mode == SimulationMode.FORCE_DECLINE) {
            log.info("Payment declined (forced override) for reservation id={}", reservationId);
            return PaymentResult.declined("Simulated card decline: insufficient funds");
        }
        if (mode == SimulationMode.FORCE_SUCCESS) {
            log.info("Payment succeeded (forced override) for reservation id={}", reservationId);
            return PaymentResult.success("TXN-SIM-" + reservationId);
        }

        // Deterministic convention based on userId
        if (userId != null) {
            String lower = userId.toLowerCase();
            if (lower.contains("fail") || lower.contains("error") || lower.contains("dlq")) {
                throw new PaymentProcessingException("Simulated technical payment gateway failure for user: " + userId);
            }
            if (lower.contains("decline")) {
                log.info("Payment declined by user convention for reservation id={}, user={}", reservationId, userId);
                return PaymentResult.declined("Card declined: credit limit exceeded");
            }
        }

        log.info("Payment processed successfully for reservation id={}, user={}, quantity={}", reservationId, userId, quantity);
        return PaymentResult.success("TXN-SIM-" + reservationId);
    }
}

