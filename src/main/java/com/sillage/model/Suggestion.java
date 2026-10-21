package com.sillage.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One recommendation produced by the deterministic rule engine.
 *
 * <p>Every suggestion carries its own reasoning: {@code rules} lists the rule ids that fired and
 * {@code rationale} is a human-readable sentence built from the same numbers that produced
 * {@code score}. Nothing here comes from a model \u2014 it is counting, ratios and thresholds, so it
 * can be audited, tuned and explained to a client.
 */
public record Suggestion(
        String id,
        String engine,
        String kind,
        String title,
        String detail,
        String rationale,
        double score,
        String confidence,
        String audience,
        String entityId,
        String entityKind,
        String action,
        String actionLabel,
        Map<String, Object> payload,
        List<String> rules,
        String horizon,
        String impact,
        LocalDateTime generatedAt) {

    public static final String NEXT_SERVICE = "next-service";
    public static final String REBOOK = "rebook";
    public static final String BUNDLE = "bundle";
    public static final String UPSELL = "upsell";
    public static final String WINBACK = "winback";
    public static final String OCCASION = "occasion";
    public static final String INVENTORY = "inventory";
    public static final String STAFFING = "staffing";
    public static final String PRICING = "pricing";
    public static final String LOYALTY = "loyalty";
    public static final String RETENTION = "retention";

    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";

    public static String confidenceFor(double score) {
        if (score >= 0.72) return HIGH;
        if (score >= 0.45) return MEDIUM;
        return LOW;
    }

    public Suggestion withScore(double newScore) {
        return new Suggestion(id, engine, kind, title, detail, rationale, newScore, confidenceFor(newScore), audience,
                entityId, entityKind, action, actionLabel, payload, rules, horizon, impact, generatedAt);
    }
}
