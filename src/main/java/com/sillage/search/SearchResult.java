package com.sillage.search;

import java.util.List;
import java.util.Map;

/**
 * Search response: the page of hits plus everything the console needs to explain the result
 * (elapsed time, matched tokens, typo corrections, facet counts and index telemetry).
 */
public record SearchResult(
        List<SearchHit> hits,
        int total,
        long tookMicros,
        String query,
        List<String> tokens,
        List<String> expansions,
        List<String> didYouMean,
        Map<String, Map<String, Integer>> facets,
        Map<String, Object> telemetry,
        int limit,
        int offset) {

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    public static SearchResult empty(String query, long tookMicros) {
        return new SearchResult(List.of(), 0, tookMicros, query, List.of(), List.of(), List.of(), Map.of(), Map.of(), 0, 0);
    }
}
