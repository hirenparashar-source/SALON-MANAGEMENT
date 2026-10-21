package com.sillage.model;

/** Gateway-side payment state machine. */
public enum PaymentStatus {
    /** Authorised but not yet captured (card pre-auth). */
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    FAILED,
    /** Awaiting the customer's 3-D Secure / UPI collect approval. */
    PENDING_AUTHENTICATION,
    EXPIRED,
    VOID;

    public boolean isSettled() {
        return this == CAPTURED;
    }

    public boolean isRefundable() {
        return this == CAPTURED || this == PARTIALLY_REFUNDED;
    }

    public static PaymentStatus parse(String value) {
        if (value == null || value.isBlank()) return FAILED;
        try {
            return PaymentStatus.valueOf(value.trim().toUpperCase().replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return FAILED;
        }
    }

    public String label() {
        return switch (this) {
            case AUTHORIZED -> "Authorised";
            case CAPTURED -> "Captured";
            case PARTIALLY_REFUNDED -> "Partially refunded";
            case REFUNDED -> "Refunded";
            case FAILED -> "Failed";
            case PENDING_AUTHENTICATION -> "Awaiting authentication";
            case EXPIRED -> "Expired";
            case VOID -> "Void";
        };
    }
}
