package com.sillage.model;

import java.time.LocalDateTime;

/** An immutable stock ledger entry (purchase, sale, service consumption, damage, correction). */
public record StockMovement(
        String id,
        String productId,
        int delta,
        String reason,
        String refId,
        String note,
        LocalDateTime at,
        String byUserId,
        String byName,
        int balanceAfter) {

    public static final String PURCHASE = "PURCHASE";
    public static final String SALE = "SALE";
    public static final String SERVICE_USAGE = "SERVICE_USAGE";
    public static final String DAMAGE = "DAMAGE";
    public static final String CORRECTION = "CORRECTION";
    public static final String RETURN = "RETURN";
}
