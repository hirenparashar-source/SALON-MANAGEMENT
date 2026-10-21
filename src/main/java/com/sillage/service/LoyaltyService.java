package com.sillage.service;

import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.BusinessSettings;
import com.sillage.model.Customer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Membership points and tiers. Deterministic arithmetic only: spend &times; rate &times; tier
 * multiplier, with tiers awarded purely on lifetime value.
 */
public final class LoyaltyService {

    private final Repo<Customer> customers;
    private final SettingsService settings;
    private final SearchService search;

    public LoyaltyService(Repo<Customer> customers, SettingsService settings, SearchService search) {
        this.customers = customers;
        this.settings = settings;
        this.search = search;
    }

    /** Cash value of a point balance. */
    public BigDecimal value(int points, BusinessSettings config) {
        BigDecimal perPoint = config.loyaltyPointValue() == null ? BigDecimal.ONE : config.loyaltyPointValue();
        return Money.scale(BigDecimal.valueOf(Math.max(0, points)).multiply(perPoint));
    }

    /** Points needed for a given cash value. */
    public int pointsFor(BigDecimal amount, BusinessSettings config) {
        BigDecimal perPoint = config.loyaltyPointValue() == null ? BigDecimal.ONE : config.loyaltyPointValue();
        if (perPoint.signum() <= 0) return 0;
        return Money.nz(amount).divide(perPoint, 0, RoundingMode.DOWN).intValue();
    }

    /** Points earned on a spend, including the tier multiplier. */
    public int earn(BigDecimal spend, Customer customer, BusinessSettings config) {
        if (!config.loyaltyEnabled() || spend == null || spend.signum() <= 0) return 0;
        double perHundred = config.loyaltyPointsPerHundred() <= 0 ? 1 : config.loyaltyPointsPerHundred();
        BusinessSettings.Tier tier = tierFor(customer, config);
        double multiplier = tier == null ? 1 : Math.max(0, tier.pointsMultiplier());
        BigDecimal raw = spend.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(perHundred))
                .multiply(BigDecimal.valueOf(multiplier));
        return raw.setScale(0, RoundingMode.DOWN).intValue();
    }

    public BusinessSettings.Tier tierFor(Customer customer, BusinessSettings config) {
        if (customer == null) return null;
        return config.tierFor(customer.lifetimeValue());
    }

    public String tierName(Customer customer, BusinessSettings config) {
        BusinessSettings.Tier tier = tierFor(customer, config);
        return tier == null ? Util.nz(customer == null ? null : customer.tier(), "Atelier") : tier.name();
    }

    /** Applies points + lifetime value + tier after a settled invoice, returning the new record. */
    public Customer afterPurchase(Customer customer, BigDecimal spend, int pointsRedeemed, BusinessSettings config) {
        if (customer == null) return null;
        int earned = earn(spend, customer, config);
        int balance = Math.max(0, customer.points() - Math.max(0, pointsRedeemed) + earned);
        Customer updated = customer.withVisit(Money.scale(spend), LocalDateTime.now(), tierName(customer, config), balance);
        customers.put(updated);
        search.index(updated);
        return updated;
    }

    /** What the client portal shows about membership. */
    public Map<String, Object> summary(Customer customer) {
        BusinessSettings config = settings.current();
        Map<String, Object> out = new LinkedHashMap<>();
        if (customer == null) {
            out.put("enabled", config.loyaltyEnabled());
            out.put("tiers", settings.loyaltyView(config).get("tiers"));
            return out;
        }
        BusinessSettings.Tier tier = tierFor(customer, config);
        BigDecimal lifetime = Money.scale(customer.lifetimeValue());
        BusinessSettings.Tier next = null;
        if (config.loyaltyTiers() != null) {
            for (BusinessSettings.Tier candidate : config.loyaltyTiers()) {
                BigDecimal min = candidate.minSpend() == null ? BigDecimal.ZERO : candidate.minSpend();
                if (min.compareTo(lifetime) > 0 && (next == null || min.compareTo(next.minSpend()) < 0)) next = candidate;
            }
        }
        out.put("tier", tier == null ? Util.nz(customer.tier(), "Atelier") : tier.name());
        out.put("perk", tier == null ? null : tier.perk());
        out.put("accent", tier == null ? "stone" : tier.accent());
        out.put("points", customer.points());
        out.put("value", value(customer.points(), config));
        out.put("lifetimeValue", lifetime);
        out.put("nextTier", next == null ? null : Util.map("name", next.name(), "minSpend", next.minSpend(),
                "spendNeeded", Money.subtract(next.minSpend(), lifetime), "perk", next.perk()));
        out.put("multiplier", tier == null ? 1.0 : tier.pointsMultiplier());
        out.put("pointsPerHundred", config.loyaltyPointsPerHundred());
        out.put("pointValue", config.loyaltyPointValue());
        out.put("expiryMonths", config.loyaltyExpiryMonths());
        out.put("tiers", settings.loyaltyView(config).get("tiers"));
        out.put("visits", customer.visits());
        return out;
    }

    /** Leaderboard for the desk: who is closest to the next tier. */
    public List<Map<String, Object>> leaderboard(int limit) {
        BusinessSettings config = settings.current();
        List<Customer> all = customers.where(c -> !c.archived());
        all.sort((a, b) -> Money.compare(Money.scale(b.lifetimeValue()), Money.scale(a.lifetimeValue())));
        return all.stream().limit(limit).map(c -> Util.map(
                "id", c.id(), "name", c.fullName(), "tier", tierName(c, config),
                "points", c.points(), "value", value(c.points(), config),
                "lifetimeValue", Money.scale(c.lifetimeValue()), "visits", c.visits())).toList();
    }
}
