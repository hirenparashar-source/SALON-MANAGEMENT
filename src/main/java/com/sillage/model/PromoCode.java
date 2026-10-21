package com.sillage.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A promotional code applied at checkout. */
public record PromoCode(
        String id,
        String code,
        String description,
        String kind,
        BigDecimal value,
        BigDecimal minSpend,
        BigDecimal maxDiscount,
        List<String> categories,
        List<String> serviceIds,
        LocalDate validFrom,
        LocalDate validTo,
        int usageLimit,
        int usedCount,
        boolean active,
        boolean stackable,
        String createdAt) {

    public static final String PERCENT = "PERCENT";
    public static final String FLAT = "FLAT";
    public static final String FREE_ADDON = "FREE_ADDON";

    public boolean isUsable(LocalDate today) {
        if (!active) return false;
        if (usageLimit > 0 && usedCount >= usageLimit) return false;
        if (validFrom != null && today.isBefore(validFrom)) return false;
        if (validTo != null && today.isAfter(validTo)) return false;
        return true;
    }

    public PromoCode used() {
        return new PromoCode(id, code, description, kind, value, minSpend, maxDiscount, categories, serviceIds,
                validFrom, validTo, usageLimit, usedCount + 1, active, stackable, createdAt);
    }
}
