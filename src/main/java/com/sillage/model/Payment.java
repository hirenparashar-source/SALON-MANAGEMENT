package com.sillage.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A single tender against an invoice.
 *
 * <p><strong>Card data never lands here.</strong> The gateway tokenises the PAN and stores only
 * {@code cardBrand}, {@code cardLast4}, {@code cardExpiry} and an opaque {@code cardToken} —
 * see {@link com.sillage.service.PaymentGateway}.
 */
public record Payment(
        String id,
        PaymentMethod method,
        BigDecimal amount,
        PaymentStatus status,
        String gatewayRef,
        String authCode,
        String cardBrand,
        String cardLast4,
        String cardExpiry,
        String cardToken,
        String upiVpa,
        String bank,
        String wallet,
        String giftCardCode,
        String idempotencyKey,
        boolean threeDsVerified,
        String failureCode,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime capturedAt,
        BigDecimal refunded,
        List<Refund> refunds,
        String takenBy,
        String note,
        int riskScore,
        String invoiceNumber) {

    /** A refund leg against this payment. */
    public record Refund(String id, BigDecimal amount, String reason, String gatewayRef, LocalDateTime at, String by) {}

    public boolean isSuccessful() {
        return status == PaymentStatus.CAPTURED || status == PaymentStatus.AUTHORIZED;
    }

    public String tenderLabel() {
        return switch (method) {
            case CARD -> "Card \u2022\u2022\u2022\u2022 " + (cardLast4 == null ? "" : cardLast4) + (cardBrand == null ? "" : " (" + cardBrand + ")");
            case UPI -> "UPI " + (upiVpa == null ? "" : upiVpa);
            case NETBANKING -> "Net banking" + (bank == null ? "" : " \u00b7 " + bank);
            case WALLET -> "Wallet" + (wallet == null ? "" : " \u00b7 " + wallet);
            case GIFT_CARD -> "Gift card" + (giftCardCode == null ? "" : " " + giftCardCode);
            default -> method.label();
        };
    }

    public Payment withRefund(Refund refund, BigDecimal totalRefunded, PaymentStatus newStatus) {
        List<Refund> updated = refunds == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(refunds);
        updated.add(refund);
        return new Payment(id, method, amount, newStatus, gatewayRef, authCode, cardBrand, cardLast4, cardExpiry,
                cardToken, upiVpa, bank, wallet, giftCardCode, idempotencyKey, threeDsVerified, failureCode,
                failureReason, createdAt, capturedAt, totalRefunded, updated, takenBy, note, riskScore, invoiceNumber);
    }

    public Payment withStatus(PaymentStatus newStatus) {
        return new Payment(id, method, amount, newStatus, gatewayRef, authCode, cardBrand, cardLast4, cardExpiry,
                cardToken, upiVpa, bank, wallet, giftCardCode, idempotencyKey, threeDsVerified, failureCode,
                failureReason, createdAt, capturedAt, refunded, refunds, takenBy, note, riskScore, invoiceNumber);
    }
}
