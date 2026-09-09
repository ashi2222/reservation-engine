package com.ashish.reservation_engine.payment;

public record PaymentResult(boolean successful, String transactionId, String declineReason) {

    public static PaymentResult success(String transactionId) {
        return new PaymentResult(true, transactionId, null);
    }

    public static PaymentResult declined(String declineReason) {
        return new PaymentResult(false, null, declineReason);
    }
}

