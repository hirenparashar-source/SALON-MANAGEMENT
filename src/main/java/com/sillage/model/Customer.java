package com.sillage.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The customer record: identity, contact, consent, loyalty position and the style profile the
 * (deliberately non-AI) recommendation engine reasons over.
 */
public record Customer(
        String id,
        String code,
        String firstName,
        String lastName,
        String phone,
        String email,
        String gender,
        LocalDate birthday,
        String address,
        String city,
        String pincode,
        List<String> tags,
        String notes,
        String allergies,
        boolean marketingConsent,
        boolean dataConsent,
        String tier,
        int points,
        BigDecimal lifetimeValue,
        int visits,
        LocalDateTime firstVisitAt,
        LocalDateTime lastVisitAt,
        String source,
        String preferredStaffId,
        String preferredTime,
        String userId,
        StyleProfile styleProfile,
        double averageRating,
        int noShows,
        int cancellations,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean archived) {

    /** Deterministic inputs for the suggestion rules — no model weights, just facts. */
    public record StyleProfile(
            String hairType,
            String texture,
            String length,
            String scalpCondition,
            String skinType,
            String preferredFinish,
            List<String> goals,
            List<String> avoids,
            int maintenanceAppetite,
            BigDecimal budgetBand) {}

    public String fullName() {
        String name = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        return name.isEmpty() ? (email == null || email.isBlank() ? "Guest" : email) : name;
    }

    public String displayName() {
        return fullName();
    }

    public String initials() {
        return com.sillage.core.Util.initials(fullName());
    }

    public boolean isBirthdayWithin(int days) {
        if (birthday == null) return false;
        LocalDate now = LocalDate.now();
        LocalDate next = birthday.withYear(now.getYear());
        if (next.isBefore(now)) next = next.plusYears(1);
        long delta = java.time.temporal.ChronoUnit.DAYS.between(now, next);
        return delta >= 0 && delta <= days;
    }

    public long daysSinceLastVisit() {
        if (lastVisitAt == null) return -1;
        return java.time.temporal.ChronoUnit.DAYS.between(lastVisitAt, LocalDateTime.now());
    }

    public Customer withPoints(int newPoints) {
        return new Customer(id, code, firstName, lastName, phone, email, gender, birthday, address, city, pincode,
                tags, notes, allergies, marketingConsent, dataConsent, tier, newPoints, lifetimeValue, visits,
                firstVisitAt, lastVisitAt, source, preferredStaffId, preferredTime, userId, styleProfile,
                averageRating, noShows, cancellations, createdAt, updatedAt, archived);
    }

    public Customer withTier(String newTier) {
        return new Customer(id, code, firstName, lastName, phone, email, gender, birthday, address, city, pincode,
                tags, notes, allergies, marketingConsent, dataConsent, newTier, points, lifetimeValue, visits,
                firstVisitAt, lastVisitAt, source, preferredStaffId, preferredTime, userId, styleProfile,
                averageRating, noShows, cancellations, createdAt, updatedAt, archived);
    }

    public Customer withVisit(BigDecimal spend, LocalDateTime when, String newTier, int newPoints) {
        BigDecimal value = (lifetimeValue == null ? BigDecimal.ZERO : lifetimeValue).add(spend == null ? BigDecimal.ZERO : spend);
        return new Customer(id, code, firstName, lastName, phone, email, gender, birthday, address, city, pincode,
                tags, notes, allergies, marketingConsent, dataConsent, newTier == null ? tier : newTier,
                newPoints, value, visits + 1,
                firstVisitAt == null ? when : firstVisitAt, when, source, preferredStaffId, preferredTime, userId,
                styleProfile, averageRating, noShows, cancellations, createdAt, LocalDateTime.now(), archived);
    }
}
