package com.sillage.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * A bookable service. Immutable: every edit produces a new record via a {@code with…} copy.
 *
 * @param editorial  the long-form magazine blurb used on the public menu
 * @param variants   length / density tiers (e.g. "Short", "Medium", "Long") with their uplift
 * @param staffIds   stylists certified for this service; empty means "anyone"
 * @param imageKey   optional artwork reference in {@code web/assets/img}
 */
public record ServiceItem(
        String id,
        String code,
        String name,
        String categoryId,
        String tagline,
        String description,
        String editorial,
        int durationMinutes,
        BigDecimal price,
        List<Variant> variants,
        List<String> staffIds,
        List<String> tags,
        String prepNotes,
        String aftercareNotes,
        String imageKey,
        boolean active,
        boolean onlineBookable,
        boolean featured,
        int timesBooked,
        double rating,
        String createdAt,
        String updatedAt) {

    /** A priced tier of a service. */
    public record Variant(String id, String label, int extraMinutes, BigDecimal extraPrice, String note) {
        public BigDecimal totalFor(BigDecimal basePrice) {
            return (basePrice == null ? BigDecimal.ZERO : basePrice)
                    .add(extraPrice == null ? BigDecimal.ZERO : extraPrice);
        }
    }

    public boolean hasVariants() {
        return variants != null && !variants.isEmpty();
    }

    public BigDecimal priceWith(String variantId) {
        if (variantId == null || variants == null) return price;
        for (Variant v : variants) if (variantId.equals(v.id())) return v.totalFor(price);
        return price;
    }

    public int durationWith(String variantId) {
        if (variantId == null || variants == null) return durationMinutes;
        for (Variant v : variants) if (variantId.equals(v.id())) return durationMinutes + v.extraMinutes();
        return durationMinutes;
    }

    public String variantLabel(String variantId) {
        if (variantId == null || variants == null) return null;
        for (Variant v : variants) if (variantId.equals(v.id())) return v.label();
        return null;
    }

    public boolean performedBy(String staffId) {
        return staffIds == null || staffIds.isEmpty() || staffIds.contains(staffId);
    }

    public ServiceItem withBookingCount(int newCount) {
        return new ServiceItem(id, code, name, categoryId, tagline, description, editorial, durationMinutes, price,
                variants, staffIds, tags, prepNotes, aftercareNotes, imageKey, active, onlineBookable, featured,
                newCount, rating, createdAt, updatedAt);
    }

    public ServiceItem withRating(double newRating) {
        return new ServiceItem(id, code, name, categoryId, tagline, description, editorial, durationMinutes, price,
                variants, staffIds, tags, prepNotes, aftercareNotes, imageKey, active, onlineBookable, featured,
                timesBooked, newRating, createdAt, updatedAt);
    }

    public ServiceItem withUpdated(String timestamp) {
        return new ServiceItem(id, code, name, categoryId, tagline, description, editorial, durationMinutes, price,
                variants, staffIds, tags, prepNotes, aftercareNotes, imageKey, active, onlineBookable, featured,
                timesBooked, rating, createdAt, timestamp);
    }
}
