package com.sillage.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A short-lived payment intent — the server-side half of the checkout handshake.
 *
 * <p>Flow: {@code POST /api/payments/intent} creates one, the client collects tender details and
 * calls {@code POST /api/payments/confirm} with the {@code clientSecret}. Card payments get a
 * second {@code PENDING_AUTHENTICATION} state that the mock 3-D Secure step resolves with an OTP.
 */
public record PaymentIntent(
        String id,
        String clientSecret,
        String invoiceId,
        String invoiceNumber,
        String appointmentId,
        String customerId,
        BigDecimal amount,
        String currency,
        PaymentMethod method,
        String status,
        String challengeId,
        String otpHash,
        int attempts,
        int otpAttempts,
        String lastError,
        String idempotencyKey,
        String ip,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime settledAt,
        String paymentId,
        boolean testMode) {

    public static final String CREATED = "CREATED";
    public static final String REQUIRES_AUTHENTICATION = "REQUIRES_AUTHENTICATION";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isLive() {
        return !isExpired() && (CREATED.equals(status) || REQUIRES_AUTHENTICATION.equals(status));
    }

    public PaymentIntent withStatus(String newStatus, String error) {
        return new PaymentIntent(id, clientSecret, invoiceId, invoiceNumber, appointmentId, customerId, amount,
                currency, method, newStatus, challengeId, otpHash, attempts, otpAttempts, error, idempotencyKey, ip,
                createdAt, expiresAt, SUCCEEDED.equals(newStatus) ? LocalDateTime.now() : settledAt, paymentId, testMode);
    }

    public PaymentIntent withChallenge(String newChallengeId, String newOtpHash) {
        return new PaymentIntent(id, clientSecret, invoiceId, invoiceNumber, appointmentId, customerId, amount,
                currency, method, REQUIRES_AUTHENTICATION, newChallengeId, newOtpHash, attempts, otpAttempts,
                lastError, idempotencyKey, ip, createdAt, expiresAt, settledAt, paymentId, testMode);
    }

    public PaymentIntent withAttempt(String error) {
        return new PaymentIntent(id, clientSecret, invoiceId, invoiceNumber, appointmentId, customerId, amount,
                currency, method, status, challengeId, otpHash, attempts + 1, otpAttempts, error, idempotencyKey, ip,
                createdAt, expiresAt, settledAt, paymentId, testMode);
    }

    public PaymentIntent withOtpAttempt() {
        return new PaymentIntent(id, clientSecret, invoiceId, invoiceNumber, appointmentId, customerId, amount,
                currency, method, status, challengeId, otpHash, attempts, otpAttempts + 1, lastError, idempotencyKey,
                ip, createdAt, expiresAt, settledAt, paymentId, testMode);
    }

    public PaymentIntent withPayment(String newPaymentId) {
        return new PaymentIntent(id, clientSecret, invoiceId, invoiceNumber, appointmentId, customerId, amount,
                currency, method, SUCCEEDED, challengeId, otpHash, attempts, otpAttempts, lastError, idempotencyKey,
                ip, createdAt, expiresAt, LocalDateTime.now(), newPaymentId, testMode);
    }
}
