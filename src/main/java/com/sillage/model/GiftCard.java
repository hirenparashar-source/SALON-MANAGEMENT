package com.sillage.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A stored-value gift card, redeemable against any invoice. */
public record GiftCard(
        String id,
        String code,
        String purchaserName,
        String purchaserPhone,
        String recipientName,
        String message,
        BigDecimal amount,
        BigDecimal balance,
        String status,
        LocalDate expiresOn,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt,
        String purchasedBy,
        String invoiceId,
        int redemptions) {

    public static final String ACTIVE = "ACTIVE";
    public static final String EXHAUSTED = "EXHAUSTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String BLOCKED = "BLOCKED";

    public boolean isUsable() {
        if (!ACTIVE.equals(status)) return false;
        if (expiresOn != null && LocalDate.now().isAfter(expiresOn)) return false;
        return balance != null && balance.signum() > 0;
    }

    public GiftCard afterRedemption(BigDecimal used) {
        BigDecimal remaining = (balance == null ? BigDecimal.ZERO : balance).subtract(used);
        return new GiftCard(id, code, purchaserName, purchaserPhone, recipientName, message, amount, remaining,
                remaining.signum() <= 0 ? EXHAUSTED : status, expiresOn, createdAt, LocalDateTime.now(),
                purchasedBy, invoiceId, redemptions + 1);
    }
}
