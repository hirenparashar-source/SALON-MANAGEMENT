package com.sillage.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the business owner can tune, in one singleton record.
 *
 * <p>Updates arrive as a partial JSON patch: the API merges the patch into the current map and
 * re-materialises the record, so new settings fields never need a migration.
 */
public record BusinessSettings(
        String id,

        // ---- identity -------------------------------------------------------
        String salonName,
        String legalName,
        String tagline,
        int established,
        String about,
        String addressLine,
        String city,
        String state,
        String pincode,
        String country,
        String phone,
        String whatsapp,
        String email,
        String website,
        String gstin,
        Map<String, String> socials,

        // ---- money ----------------------------------------------------------
        String currency,
        String currencySymbol,
        BigDecimal defaultTaxPct,
        String taxLabel,
        String taxSplit,
        boolean pricesInclusiveOfTax,
        String invoicePrefix,
        Map<String, Integer> invoiceCounters,
        BigDecimal roundTo,
        boolean autoRound,

        // ---- operations -----------------------------------------------------
        List<Staff.DayHours> hours,
        List<Holiday> holidays,
        int slotMinutes,
        int bufferMinutes,
        int minAdvanceMinutes,
        int maxAdvanceDays,
        int cancellationHours,
        int graceMinutes,
        boolean allowWalkIns,
        boolean allowGuestCheckout,
        boolean requireDeposit,
        BigDecimal depositPct,

        // ---- loyalty --------------------------------------------------------
        boolean loyaltyEnabled,
        double loyaltyPointsPerHundred,
        BigDecimal loyaltyPointValue,
        List<Tier> loyaltyTiers,
        int loyaltyExpiryMonths,

        // ---- payments -------------------------------------------------------
        String gatewayName,
        String gatewayMode,
        boolean cardEnabled,
        boolean upiEnabled,
        boolean netbankingEnabled,
        boolean walletEnabled,
        boolean cashEnabled,
        boolean giftCardEnabled,
        int intentTtlMinutes,
        int otpLength,
        boolean simulateGatewayFailures,

        // ---- notifications --------------------------------------------------
        boolean notificationsEnabled,
        List<String> notificationChannels,
        String notificationSenderId,
        Map<String, String> templates,

        // ---- experience -----------------------------------------------------
        Map<String, Object> theme,
        Map<String, Object> accessibility,
        Map<String, Boolean> features,
        String locale,
        String timezone,

        // ---- house rules ----------------------------------------------------
        Map<String, String> policies,

        LocalDateTime updatedAt,
        String updatedBy) {

    /** A named membership tier. */
    public record Tier(String id, String name, BigDecimal minSpend, double pointsMultiplier, String perk, String accent) {}

    /** A closed day. {@code recurring} marks an annual holiday. */
    public record Holiday(LocalDate date, String name, boolean recurring) {}

    public static final String SPLIT_CGST_SGST = "CGST_SGST";
    public static final String SPLIT_IGST = "IGST";
    public static final String SPLIT_SINGLE = "SINGLE";

    public boolean isClosedOn(LocalDate date) {
        if (holidays == null || date == null) return false;
        for (Holiday h : holidays) {
            if (h.date() == null) continue;
            if (h.date().equals(date)) return true;
            if (h.recurring() && h.date().getMonthValue() == date.getMonthValue() && h.date().getDayOfMonth() == date.getDayOfMonth()) return true;
        }
        return false;
    }

    public Staff.DayHours hoursFor(LocalDate date) {
        if (hours == null || date == null) return null;
        String day = date.getDayOfWeek().name();
        for (Staff.DayHours h : hours) if (day.equalsIgnoreCase(h.day())) return h;
        return null;
    }

    public String holidayNameOn(LocalDate date) {
        if (holidays == null) return null;
        for (Holiday h : holidays) {
            if (h.date() == null) continue;
            boolean match = h.date().equals(date)
                    || (h.recurring() && h.date().getMonthValue() == date.getMonthValue() && h.date().getDayOfMonth() == date.getDayOfMonth());
            if (match) return h.name();
        }
        return null;
    }

    public Tier tierFor(BigDecimal lifetimeValue) {
        if (loyaltyTiers == null || loyaltyTiers.isEmpty()) return null;
        Tier best = loyaltyTiers.get(0);
        for (Tier tier : loyaltyTiers) {
            BigDecimal min = tier.minSpend() == null ? BigDecimal.ZERO : tier.minSpend();
            if (lifetimeValue != null && lifetimeValue.compareTo(min) >= 0) best = tier;
        }
        return best;
    }

    public Tier tierByName(String name) {
        if (loyaltyTiers == null || name == null) return null;
        for (Tier tier : loyaltyTiers) if (name.equalsIgnoreCase(tier.name())) return tier;
        return null;
    }

    public boolean featureEnabled(String key) {
        return features != null && Boolean.TRUE.equals(features.get(key));
    }

    public String template(String key) {
        return templates == null ? null : templates.get(key);
    }

    public BusinessSettings touched(String by) {
        return new BusinessSettings(id, salonName, legalName, tagline, established, about, addressLine, city, state,
                pincode, country, phone, whatsapp, email, website, gstin, socials, currency, currencySymbol,
                defaultTaxPct, taxLabel, taxSplit, pricesInclusiveOfTax, invoicePrefix, invoiceCounters, roundTo,
                autoRound, hours, holidays, slotMinutes, bufferMinutes, minAdvanceMinutes, maxAdvanceDays,
                cancellationHours, graceMinutes, allowWalkIns, allowGuestCheckout, requireDeposit, depositPct,
                loyaltyEnabled, loyaltyPointsPerHundred, loyaltyPointValue, loyaltyTiers, loyaltyExpiryMonths,
                gatewayName, gatewayMode, cardEnabled, upiEnabled, netbankingEnabled, walletEnabled, cashEnabled,
                giftCardEnabled, intentTtlMinutes, otpLength, simulateGatewayFailures, notificationsEnabled,
                notificationChannels, notificationSenderId, templates, theme, accessibility, features, locale,
                timezone, policies, LocalDateTime.now(), by);
    }

    public BusinessSettings withInvoiceCounters(Map<String, Integer> counters) {
        return new BusinessSettings(id, salonName, legalName, tagline, established, about, addressLine, city, state,
                pincode, country, phone, whatsapp, email, website, gstin, socials, currency, currencySymbol,
                defaultTaxPct, taxLabel, taxSplit, pricesInclusiveOfTax, invoicePrefix, counters, roundTo, autoRound,
                hours, holidays, slotMinutes, bufferMinutes, minAdvanceMinutes, maxAdvanceDays, cancellationHours,
                graceMinutes, allowWalkIns, allowGuestCheckout, requireDeposit, depositPct, loyaltyEnabled,
                loyaltyPointsPerHundred, loyaltyPointValue, loyaltyTiers, loyaltyExpiryMonths, gatewayName,
                gatewayMode, cardEnabled, upiEnabled, netbankingEnabled, walletEnabled, cashEnabled, giftCardEnabled,
                intentTtlMinutes, otpLength, simulateGatewayFailures, notificationsEnabled, notificationChannels,
                notificationSenderId, templates, theme, accessibility, features, locale, timezone, policies,
                LocalDateTime.now(), updatedBy);
    }

    /** The factory defaults — a Ludhiana flagship salon, GST-registered, INR. */
    public static BusinessSettings defaults() {
        Map<String, String> socials = new LinkedHashMap<>();
        socials.put("instagram", "https://instagram.com/sillage.studio");
        socials.put("facebook", "https://facebook.com/sillage.studio");
        socials.put("pinterest", "https://pinterest.com/sillagestudio");

        List<Staff.DayHours> hours = List.of(
                new Staff.DayHours("MONDAY", "10:00", "20:00", true, "Closed for deep clean"),
                new Staff.DayHours("TUESDAY", "10:00", "20:00", false, null),
                new Staff.DayHours("WEDNESDAY", "10:00", "20:00", false, null),
                new Staff.DayHours("THURSDAY", "10:00", "21:00", false, "Late night chair"),
                new Staff.DayHours("FRIDAY", "10:00", "21:00", false, null),
                new Staff.DayHours("SATURDAY", "09:00", "21:00", false, null),
                new Staff.DayHours("SUNDAY", "09:00", "18:00", false, null));

        List<Holiday> holidays = List.of(
                new Holiday(LocalDate.of(LocalDate.now().getYear(), 1, 26), "Republic Day", true),
                new Holiday(LocalDate.of(LocalDate.now().getYear(), 8, 15), "Independence Day", true),
                new Holiday(LocalDate.of(LocalDate.now().getYear(), 10, 2), "Gandhi Jayanti", true),
                new Holiday(LocalDate.of(LocalDate.now().getYear(), 11, 1), "Diwali", false),
                new Holiday(LocalDate.of(LocalDate.now().getYear(), 12, 25), "Christmas", true));

        List<Tier> tiers = List.of(
                new Tier("t1", "Atelier", BigDecimal.ZERO, 1.0, "1 point per \u20B9100", "stone"),
                new Tier("t2", "Maison", new BigDecimal("25000"), 1.25, "Priority chairs + birthday gloss", "brass"),
                new Tier("t3", "Priv\u00e9", new BigDecimal("75000"), 1.5, "Private suite, home pickup, 10% retail", "oxblood"));

        Map<String, Integer> counters = new LinkedHashMap<>();
        counters.put(fiscalYear(), 0);

        Map<String, String> templates = new LinkedHashMap<>();
        templates.put("booking.confirmed", "Sillage \u2014 your chair is held, {customer}. {service} with {staff} on {date} at {time}. Reply C to change.");
        templates.put("booking.reminder", "Tomorrow at {time}, {customer}: {service} with {staff}. Arrive 10 minutes early for a consultation.");
        templates.put("booking.cancelled", "Your appointment on {date} has been released. Call {phone} to rebook \u2014 we keep the chair warm for 24 hours.");
        templates.put("invoice.paid", "Thank you, {customer}. Receipt {number} for {amount} is settled. Points balance: {points}.");
        templates.put("invoice.refund", "Refund of {amount} against {number} has been issued to {method}. It lands in 3\u20135 working days.");
        templates.put("loyalty.tier", "You have been moved to {tier}, {customer}. {perk}");
        templates.put("birthday", "Happy birthday, {customer}. A complimentary gloss and blow-dry awaits you this month.");
        templates.put("winback", "It has been {days} days, {customer}. Your usual {service} slot is open on {date}.");
        templates.put("feedback", "How was {service} with {staff}? Reply 1\u20135 \u2014 it takes ten seconds and it shapes the floor.");

        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("mode", "paper");
        theme.put("accent", "brass");
        theme.put("density", "comfortable");
        theme.put("displayFace", "Lora");
        theme.put("textFace", "Jost");
        theme.put("monoFace", "IBM Plex Mono");
        theme.put("showTicker", true);
        theme.put("artwork", "editorial");

        Map<String, Object> accessibility = new LinkedHashMap<>();
        accessibility.put("fontScale", 100);
        accessibility.put("contrast", "normal");
        accessibility.put("reduceMotion", false);
        accessibility.put("dyslexiaFont", false);
        accessibility.put("textSpacing", false);
        accessibility.put("largeTargets", false);
        accessibility.put("underlineLinks", false);
        accessibility.put("focusRing", "always");
        accessibility.put("screenReaderHints", true);
        accessibility.put("monoChrome", false);
        accessibility.put("captions", true);
        accessibility.put("announceUpdates", true);

        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("onlineBooking", true);
        features.put("guestCheckout", true);
        features.put("walkInQueue", true);
        features.put("loyalty", true);
        features.put("giftCards", true);
        features.put("inventory", true);
        features.put("suggestions", true);
        features.put("analytics", true);
        features.put("notifications", true);
        features.put("deposits", false);
        features.put("waitlist", true);
        features.put("tips", true);

        Map<String, String> policies = new LinkedHashMap<>();
        policies.put("cancellation", "Cancel or reschedule free of charge up to 6 hours before your chair time. Inside that window we retain the deposit, if one was taken.");
        policies.put("lateArrival", "We hold your slot for 15 minutes. Beyond that the service may be shortened or re-booked, at the floor manager's discretion.");
        policies.put("consultation", "Every colour service begins with a strand test and a patch test on the first visit. Allergy information is kept on your record.");
        policies.put("privacy", "Your record is stored on this machine only. Contact details are used for appointment reminders and, with consent, seasonal notes. Ask the desk for an export or erasure at any time.");
        policies.put("giftCards", "Gift cards are valid for 24 months from purchase and may be split across visits.");
        policies.put("children", "Under-12 services are offered on the ground floor chairs between 10:00 and 16:00.");
        policies.put("receipt", "Thank you \u2014 we look forward to your next visit.");

        return new BusinessSettings(
                "default",
                "Sillage",
                "Sillage Studio Pvt. Ltd.",
                "Maison de coiffure & beaut\u00e9",
                1987,
                "Three floors of quiet luxury above the rose garden: nine chairs, one colour bar, and a booking book that has been kept by hand since 1987 \u2014 now with a computer behind it.",
                "12 Fountain Lane, Civil Lines",
                "Ludhiana",
                "Punjab",
                "141001",
                "India",
                "+91 98765 43210",
                "+91 98765 43210",
                "studio@sillage.example",
                "https://sillage.example",
                "03AABCS1429B1ZX",
                socials,
                "INR",
                "\u20B9",
                new BigDecimal("18"),
                "GST",
                SPLIT_CGST_SGST,
                false,
                "SIL",
                counters,
                BigDecimal.ONE,
                true,
                hours,
                holidays,
                15,
                10,
                60,
                60,
                6,
                15,
                true,
                true,
                false,
                new BigDecimal("20"),
                true,
                1.0,
                new BigDecimal("1"),
                tiers,
                24,
                "Sillage Pay",
                "TEST",
                true,
                true,
                true,
                true,
                true,
                true,
                20,
                6,
                true,
                true,
                List.of("SMS", "EMAIL", "WHATSAPP"),
                "SILLAGE",
                templates,
                theme,
                accessibility,
                features,
                "en-IN",
                "Asia/Kolkata",
                policies,
                LocalDateTime.now(),
                "system");
    }

    public static String fiscalYear() {
        LocalDate today = LocalDate.now();
        int start = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        return "FY" + start + "-" + String.valueOf(start + 1).substring(2);
    }
}
