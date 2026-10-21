package com.sillage.service;

import com.sillage.core.ApiException;
import com.sillage.core.JsonMapper;
import com.sillage.core.Log;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.Actor;
import com.sillage.model.BusinessSettings;
import com.sillage.model.Role;
import com.sillage.model.Staff;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the singleton {@link BusinessSettings} record: reading it, patching it safely and handing
 * out the next invoice number from a persisted counter.
 */
public final class SettingsService {

    private static final String ID = "default";

    private final Repo<BusinessSettings> repo;
    private final Object counterLock = new Object();

    public SettingsService(Repo<BusinessSettings> repo) {
        this.repo = repo;
    }

    /** Never returns null: the first call materialises the defaults and persists them. */
    public BusinessSettings current() {
        BusinessSettings settings = repo.get(ID);
        if (settings != null) return settings;
        synchronized (repo) {
            settings = repo.get(ID);
            if (settings == null) {
                settings = BusinessSettings.defaults();
                repo.put(settings);
                Log.info("settings initialised with defaults");
            }
        }
        return settings;
    }

    public BusinessSettings replace(BusinessSettings settings, Actor actor) {
        BusinessSettings stamped = settings.touched(actor == null ? "system" : actor.label());
        repo.put(stamped);
        return stamped;
    }

    /**
     * PATCH semantics: the incoming map is merged over the current settings, re-materialised into
     * the record and validated. Unknown keys are dropped; malformed values are rejected with
     * field-level errors rather than silently ignored.
     */
    public BusinessSettings patch(Map<String, Object> patch, Actor actor) {
        if (actor != null) actor.require(Role.OWNER);
        BusinessSettings current = current();
        Map<String, Object> merged = JsonMapper.merge(JsonMapper.toMap(current), patch == null ? Map.of() : patch);
        merged.remove("id");
        merged.put("id", ID);
        BusinessSettings candidate;
        try {
            candidate = JsonMapper.from(merged, BusinessSettings.class, null);
        } catch (RuntimeException e) {
            throw new ApiException(422, "VALIDATION", "Those settings could not be applied: " + e.getMessage());
        }
        validate(candidate);
        BusinessSettings saved = candidate.touched(actor == null ? "system" : actor.label());
        repo.put(saved);
        return saved;
    }

    private void validate(BusinessSettings settings) {
        List<String> problems = new ArrayList<>();
        if (Util.blank(settings.salonName())) problems.add("The salon name cannot be empty");
        if (settings.slotMinutes() < 5 || settings.slotMinutes() > 120) problems.add("Slot length must be between 5 and 120 minutes");
        if (settings.bufferMinutes() < 0 || settings.bufferMinutes() > 90) problems.add("Chair buffer must be between 0 and 90 minutes");
        if (settings.defaultTaxPct() == null || settings.defaultTaxPct().signum() < 0
                || settings.defaultTaxPct().compareTo(new BigDecimal("40")) > 0) problems.add("Tax rate must be between 0% and 40%");
        if (settings.currency() == null || !settings.currency().matches("[A-Z]{3}")) problems.add("Currency must be a 3-letter ISO code");
        if (settings.otpLength() < 4 || settings.otpLength() > 8) problems.add("OTP length must be between 4 and 8");
        if (settings.intentTtlMinutes() < 1 || settings.intentTtlMinutes() > 240) problems.add("Payment intent lifetime must be between 1 and 240 minutes");
        if (settings.maxAdvanceDays() < 1 || settings.maxAdvanceDays() > 365) problems.add("Booking horizon must be between 1 and 365 days");
        if (settings.hours() != null) {
            for (Staff.DayHours hours : settings.hours()) {
                if (hours.closed() || hours.start() == null || hours.end() == null) continue;
                LocalTime start = parseTime(hours.start());
                LocalTime end = parseTime(hours.end());
                if (start == null || end == null) { problems.add("Opening hours must use HH:mm"); break; }
                if (!end.isAfter(start)) { problems.add("Closing time must be after opening time for " + Util.titleCase(hours.day())); break; }
            }
        }
        if (settings.loyaltyTiers() != null) {
            for (BusinessSettings.Tier tier : settings.loyaltyTiers()) {
                if (Util.blank(tier.name())) problems.add("Every loyalty tier needs a name");
                if (tier.pointsMultiplier() < 0 || tier.pointsMultiplier() > 10) problems.add("Tier multipliers must be between 0 and 10");
            }
        }
        if (!problems.isEmpty()) throw ApiException.validation(problems);
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value.length() == 5 ? value + ":00" : value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Next sequential invoice number for the running fiscal year, e.g. {@code SIL/FY2026-27/00042}. */
    /**
     * Allocates the next invoice number. The counter is always read from the store rather than from
     * the caller's snapshot, so a long-running job (seeding, a bulk import) cannot allocate the same
     * number twice.
     */
    public String nextInvoiceNumber(BusinessSettings settings) {
        synchronized (counterLock) {
            BusinessSettings current = current();
            String fiscal = BusinessSettings.fiscalYear();
            Map<String, Integer> counters = new LinkedHashMap<>(current.invoiceCounters() == null ? Map.of() : current.invoiceCounters());
            int next = counters.getOrDefault(fiscal, 0) + 1;
            counters.put(fiscal, next);
            repo.put(current.withInvoiceCounters(counters));
            return "%s/%s/%05d".formatted(Util.nz(current.invoicePrefix(), "SIL"), fiscal, next);
        }
    }

    public String previewInvoiceNumber() {
        BusinessSettings current = current();
        String fiscal = BusinessSettings.fiscalYear();
        int next = (current.invoiceCounters() == null ? 0 : current.invoiceCounters().getOrDefault(fiscal, 0)) + 1;
        return "%s/%s/%05d".formatted(Util.nz(current.invoicePrefix(), "SIL"), fiscal, next);
    }

    /** The subset the public landing page and booking flow may see. */
    public Map<String, Object> publicView() {
        BusinessSettings s = current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("salonName", s.salonName());
        out.put("legalName", s.legalName());
        out.put("tagline", s.tagline());
        out.put("established", s.established());
        out.put("about", s.about());
        out.put("address", Util.join(List.of(Util.nz(s.addressLine()), Util.nz(s.city()), Util.nz(s.state()), Util.nz(s.pincode())), ", ").replace(", , ", ", "));
        out.put("addressLine", s.addressLine());
        out.put("city", s.city());
        out.put("state", s.state());
        out.put("pincode", s.pincode());
        out.put("country", s.country());
        out.put("phone", s.phone());
        out.put("whatsapp", s.whatsapp());
        out.put("email", s.email());
        out.put("website", s.website());
        out.put("socials", s.socials());
        out.put("currency", s.currency());
        out.put("currencySymbol", s.currencySymbol());
        out.put("taxLabel", s.taxLabel());
        out.put("taxPct", s.defaultTaxPct());
        out.put("pricesInclusiveOfTax", s.pricesInclusiveOfTax());
        out.put("hours", hoursView(s));
        out.put("openNow", openNowStatus(s));
        out.put("holidays", s.holidays());
        out.put("slotMinutes", s.slotMinutes());
        out.put("minAdvanceMinutes", s.minAdvanceMinutes());
        out.put("maxAdvanceDays", s.maxAdvanceDays());
        out.put("cancellationHours", s.cancellationHours());
        out.put("graceMinutes", s.graceMinutes());
        out.put("allowGuestCheckout", s.allowGuestCheckout());
        out.put("allowWalkIns", s.allowWalkIns());
        out.put("requireDeposit", s.requireDeposit());
        out.put("depositPct", s.depositPct());
        out.put("loyalty", loyaltyView(s));
        out.put("paymentMethods", paymentMethods(s));
        out.put("gateway", Util.map("name", s.gatewayName(), "mode", s.gatewayMode(), "testMode", "TEST".equalsIgnoreCase(s.gatewayMode())));
        out.put("policies", s.policies());
        out.put("theme", s.theme());
        out.put("accessibility", s.accessibility());
        out.put("features", publicFeatures(s));
        out.put("locale", s.locale());
        out.put("timezone", s.timezone());
        return out;
    }

    /** The full record, for the owner's settings console. */
    public Map<String, Object> adminView() {
        BusinessSettings s = current();
        Map<String, Object> out = JsonMapper.toMap(s);
        out.put("invoicePreview", previewInvoiceNumber());
        out.put("fiscalYear", BusinessSettings.fiscalYear());
        out.put("hours", hoursView(s));
        out.put("paymentMethods", paymentMethods(s));
        return out;
    }

    public Map<String, Object> hoursView(BusinessSettings s) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> days = new ArrayList<>();
        Map<String, Staff.DayHours> byDay = new LinkedHashMap<>();
        if (s.hours() != null) for (Staff.DayHours hours : s.hours()) byDay.put(hours.day().toUpperCase(), hours);
        for (DayOfWeek day : DayOfWeek.values()) {
            Staff.DayHours hours = byDay.get(day.name());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("day", day.name());
            entry.put("label", day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH));
            entry.put("short", Util.dayName(day));
            if (hours == null || hours.closed()) {
                entry.put("closed", true);
                entry.put("start", null);
                entry.put("end", null);
                entry.put("note", hours == null ? null : hours.note());
            } else {
                entry.put("closed", false);
                entry.put("start", hours.start());
                entry.put("end", hours.end());
                entry.put("note", hours.note());
                entry.put("display", pretty(hours.start()) + " \u2013 " + pretty(hours.end()));
            }
            entry.put("today", day == LocalDate.now().getDayOfWeek());
            days.add(entry);
        }
        out.put("days", days);
        out.put("today", LocalDate.now().getDayOfWeek().name());
        return out;
    }

    public Map<String, Object> openNowStatus(BusinessSettings s) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        Map<String, Object> out = new LinkedHashMap<>();
        if (s.isClosedOn(today)) {
            out.put("open", false);
            out.put("reason", "Closed \u00b7 " + Util.nz(s.holidayNameOn(today), "holiday"));
            return out;
        }
        Staff.DayHours hours = s.hoursFor(today);
        if (hours == null || hours.closed() || hours.start() == null || hours.end() == null) {
            out.put("open", false);
            out.put("reason", "Closed today");
            return out;
        }
        LocalTime start = parseTime(hours.start());
        LocalTime end = parseTime(hours.end());
        boolean open = start != null && end != null && !now.isBefore(start) && now.isBefore(end);
        out.put("open", open);
        out.put("start", hours.start());
        out.put("end", hours.end());
        out.put("reason", open ? "Open until " + pretty(hours.end()) : (now.isBefore(start) ? "Opens at " + pretty(hours.start()) : "Closed for today"));
        return out;
    }

    public Map<String, Object> loyaltyView(BusinessSettings s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", s.loyaltyEnabled());
        out.put("pointsPerHundred", s.loyaltyPointsPerHundred());
        out.put("pointValue", s.loyaltyPointValue());
        out.put("expiryMonths", s.loyaltyExpiryMonths());
        List<Map<String, Object>> tiers = new ArrayList<>();
        if (s.loyaltyTiers() != null) {
            List<BusinessSettings.Tier> sorted = new ArrayList<>(s.loyaltyTiers());
            sorted.sort(Comparator.comparing(t -> t.minSpend() == null ? BigDecimal.ZERO : t.minSpend()));
            for (BusinessSettings.Tier tier : sorted) {
                tiers.add(Util.map("id", tier.id(), "name", tier.name(),
                        "minSpend", tier.minSpend(), "multiplier", tier.pointsMultiplier(),
                        "perk", tier.perk(), "accent", tier.accent()));
            }
        }
        out.put("tiers", tiers);
        return out;
    }

    public List<Map<String, Object>> paymentMethods(BusinessSettings s) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (s.cardEnabled()) out.add(Util.map("id", "CARD", "label", "Card", "hint", "Visa, Mastercard, RuPay, Amex", "online", true));
        if (s.upiEnabled()) out.add(Util.map("id", "UPI", "label", "UPI", "hint", "Any UPI id or collect request", "online", true));
        if (s.netbankingEnabled()) out.add(Util.map("id", "NETBANKING", "label", "Net banking", "hint", "58 banks", "online", true));
        if (s.walletEnabled()) out.add(Util.map("id", "WALLET", "label", "Wallet", "hint", "Paytm, PhonePe, Amazon Pay", "online", true));
        if (s.cashEnabled()) out.add(Util.map("id", "CASH", "label", "Cash", "hint", "At the desk", "online", false));
        if (s.giftCardEnabled()) out.add(Util.map("id", "GIFT_CARD", "label", "Gift card", "hint", "Split across visits", "online", false));
        out.add(Util.map("id", "PAY_AT_SALON", "label", "Pay at salon", "hint", "Reserve now, settle at the desk", "online", false));
        return out;
    }

    private Map<String, Object> publicFeatures(BusinessSettings s) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (s.features() == null) return out;
        for (String key : List.of("onlineBooking", "guestCheckout", "walkInQueue", "loyalty", "giftCards", "waitlist", "deposits", "tips")) {
            out.put(key, s.features().getOrDefault(key, false));
        }
        return out;
    }

    private static String pretty(String hhmm) {
        LocalTime time = parseTime(hhmm);
        return time == null ? Util.nz(hhmm) : time.format(DateTimeFormatter.ofPattern("h:mm a"));
    }

    public BigDecimal taxRate() {
        BigDecimal rate = current().defaultTaxPct();
        return Money.scale(rate == null ? BigDecimal.ZERO : rate);
    }
}
