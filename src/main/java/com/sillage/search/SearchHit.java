package com.sillage.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A single ranked match. */
public record SearchHit(
        String id,
        String kind,
        String title,
        String subtitle,
        String href,
        double score,
        Map<String, String> attrs,
        Map<String, String> matched,
        String snippet,
        String label) {

    public static SearchHit of(SearchDocument doc, double score, Map<String, String> matched, String snippet, String label) {
        return new SearchHit(doc.id(), doc.kind(), doc.title(), doc.subtitle(), doc.href(),
                round(score), Map.copyOf(doc.attrs()), new LinkedHashMap<>(matched), snippet, label);
    }

    public List<String> matchedTokens() {
        return List.copyOf(matched.values());
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
