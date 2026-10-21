package com.sillage.search;

import com.sillage.core.Util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory full-text index: inverted postings, BM25 relevance, prefix completion,
 * typo tolerance (SymSpell-style deletion neighbourhoods) and substring matching over
 * identifier-like fields (phone numbers, invoice codes) via a trigram side index.
 *
 * <p>Everything is plain Java data structures — no Lucene, no database, no native code —
 * which is why the whole suite still runs from a single {@code java} command. Typical query
 * cost on the seeded dataset (a few thousand documents) is well under a millisecond; the
 * measured figure is returned to the client as telemetry.
 */
public final class SearchIndex {

    /** Index telemetry snapshot. */
    public record IndexStats(int documents, int tokens, long postings, long trigrams, double avgDocLength,
                             long estimatedBytes, long buildMillis, Map<String, Integer> byKind,
                             Map<String, Integer> fieldWeights) {}

    private static final double K1 = 1.35;
    private static final double B = 0.72;
    private static final double PREFIX_PENALTY = 0.62;
    private static final double FUZZY_PENALTY = 0.70;
    private static final double SUBSTRING_PENALTY = 0.45;
    private static final int MAX_PREFIX_CANDIDATES = 16;
    private static final int MAX_FUZZY_CANDIDATES = 8;
    private static final String[] FACET_ATTRS = {"kind", "status", "category", "tier", "role", "brand", "source", "staff", "payment"};

    private final Map<String, Integer> fieldWeights;
    private final Set<String> substringFields;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // parallel arrays indexed by internal doc slot
    private final List<SearchDocument> docs = new ArrayList<>();
    private final List<Integer> lengths = new ArrayList<>();
    private final List<Map<String, Integer>> tokenCounts = new ArrayList<>();
    private final Map<String, Integer> keyToSlot = new HashMap<>();

    private final Map<String, Map<Integer, Float>> postings = new HashMap<>();
    private final Map<String, Map<Integer, Integer>> trigrams = new HashMap<>();
    private final Map<String, Set<String>> deletionIndex = new HashMap<>();

    private long liveDocs;
    private long totalTokens;
    private long buildNanos;

    public SearchIndex(Map<String, Integer> fieldWeights, Set<String> substringFields) {
        this.fieldWeights = new LinkedHashMap<>(fieldWeights);
        this.substringFields = new LinkedHashSet<>(substringFields);
    }

    public static SearchIndex salonDefault() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("name", 5);
        weights.put("code", 4);
        weights.put("phone", 4);
        weights.put("email", 3);
        weights.put("title", 5);
        weights.put("subtitle", 2);
        weights.put("tags", 3);
        weights.put("category", 2);
        weights.put("staff", 2);
        weights.put("brand", 2);
        weights.put("description", 1);
        weights.put("notes", 1);
        weights.put("items", 2);
        return new SearchIndex(weights, Set.of("phone", "code", "email"));
    }

    // ------------------------------------------------------------------ mutation

    public void add(SearchDocument doc) {
        if (doc == null || Util.blank(doc.id())) return;
        long start = System.nanoTime();
        lock.writeLock().lock();
        try {
            String key = key(doc.kind(), doc.id());
            Integer existing = keyToSlot.get(key);
            if (existing != null) detach(existing);

            int slot = docs.size();
            docs.add(doc);
            lengths.add(0);
            tokenCounts.add(new HashMap<>());
            keyToSlot.put(key, slot);

            Map<String, Integer> counts = tokenCounts.get(slot);
            for (Map.Entry<String, String> field : doc.fields().entrySet()) {
                int weight = fieldWeights.getOrDefault(field.getKey(), 1);
                for (String token : tokenize(field.getValue())) {
                    counts.merge(token, 1, Integer::sum);
                    if (substringFields.contains(field.getKey())) indexTrigrams(slot, token);
                }
            }
            // Titles are always searchable even when a caller forgets to map them to a weighted field.
            for (String token : tokenize(doc.title())) counts.merge(token, 1, Integer::sum);

            int length = counts.values().stream().mapToInt(Integer::intValue).sum();
            lengths.set(slot, length);
            totalTokens += length;
            liveDocs++;

            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                String token = entry.getKey();
                float tf = entry.getValue() * weightOf(token, doc);
                postings.computeIfAbsent(token, k -> new HashMap<>()).put(slot, tf);
                rememberDeletions(token);
            }
        } finally {
            lock.writeLock().unlock();
            buildNanos += System.nanoTime() - start;
        }
    }

    public void addAll(Collection<SearchDocument> documents) {
        for (SearchDocument doc : documents) add(doc);
    }

    public void remove(String kind, String id) {
        lock.writeLock().lock();
        try {
            Integer slot = keyToSlot.remove(key(kind, id));
            if (slot != null) detach(slot);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            docs.clear();
            lengths.clear();
            tokenCounts.clear();
            keyToSlot.clear();
            postings.clear();
            trigrams.clear();
            deletionIndex.clear();
            liveDocs = 0;
            totalTokens = 0;
            buildNanos = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void rebuild(Collection<SearchDocument> documents) {
        long start = System.nanoTime();
        clear();
        addAll(documents);
        buildNanos = System.nanoTime() - start;
    }

    private void detach(int slot) {
        SearchDocument doc = docs.get(slot);
        if (doc == null) return;
        Map<String, Integer> counts = tokenCounts.get(slot);
        for (String token : counts.keySet()) {
            Map<Integer, Float> posting = postings.get(token);
            if (posting != null) {
                posting.remove(slot);
                if (posting.isEmpty()) postings.remove(token);
            }
            for (String gram : trigramsOf(token)) {
                Map<Integer, Integer> grams = trigrams.get(gram);
                if (grams != null) {
                    grams.remove(slot);
                    if (grams.isEmpty()) trigrams.remove(gram);
                }
            }
        }
        totalTokens -= lengths.get(slot);
        liveDocs--;
        docs.set(slot, null);
        lengths.set(slot, 0);
        tokenCounts.set(slot, Map.of());
    }

    private void indexTrigrams(int slot, String token) {
        for (String gram : trigramsOf(token)) trigrams.computeIfAbsent(gram, k -> new HashMap<>()).merge(slot, 1, Integer::sum);
    }

    private static List<String> trigramsOf(String token) {
        String compact = Util.normalise(token).replace(" ", "");
        if (compact.length() < 3) return List.of(compact);
        List<String> out = new ArrayList<>(compact.length());
        for (int i = 0; i + 3 <= compact.length(); i++) out.add(compact.substring(i, i + 3));
        return out;
    }

    private void rememberDeletions(String token) {
        if (token.length() < 4 || token.length() > 24) return;
        for (String variant : deletions(token)) deletionIndex.computeIfAbsent(variant, k -> new LinkedHashSet<>()).add(token);
    }

    private static Set<String> deletions(String token) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < token.length(); i++) out.add(token.substring(0, i) + token.substring(i + 1));
        return out;
    }

    private float weightOf(String token, SearchDocument doc) {
        float best = 1f;
        for (Map.Entry<String, String> field : doc.fields().entrySet()) {
            String text = field.getValue();
            if (text == null) continue;
            if (Util.normalise(text).contains(token)) {
                int weight = fieldWeights.getOrDefault(field.getKey(), 1);
                if (weight > best) best = weight;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ queries

    public SearchResult search(SearchQuery query) {
        long start = System.nanoTime();
        String raw = query.text() == null ? "" : query.text().trim();
        List<String> queryTokens = tokenize(raw);
        List<String> expansions = new ArrayList<>();
        List<String> corrections = new ArrayList<>();
        Map<Integer, Double> scores = new HashMap<>();
        Map<Integer, Set<String>> tokenHits = new HashMap<>();

        lock.readLock().lock();
        try {
            int corpus = (int) liveDocs;
            double avgLength = liveDocs == 0 ? 1 : Math.max(1.0, (double) totalTokens / liveDocs);

            if (queryTokens.isEmpty()) {
                for (int slot = 0; slot < docs.size(); slot++) {
                    SearchDocument doc = docs.get(slot);
                    if (doc == null || !matches(doc, query)) continue;
                    scores.put(slot, 0.0);
                }
            } else {
                for (String token : queryTokens) {
                    List<Candidate> candidates = candidatesFor(token, corrections);
                    if (candidates.isEmpty()) continue;
                    for (Candidate candidate : candidates) {
                        double idf = Math.log(1 + (corpus - candidate.docs.size() + 0.5) / (candidate.docs.size() + 0.5));
                        expansions.add(candidate.token + (candidate.penalty < 1 ? (candidate.penalty == SUBSTRING_PENALTY ? "\u2282" : "*") : ""));
                        for (Map.Entry<Integer, Float> entry : candidate.docs.entrySet()) {
                            int slot = entry.getKey();
                            SearchDocument doc = docs.get(slot);
                            if (doc == null || !matches(doc, query)) continue;
                            float tf = entry.getValue();
                            double length = Math.max(1, lengths.get(slot));
                            double denominator = tf + K1 * (1 - B + B * (length / avgLength));
                            double score = idf * (tf * (K1 + 1)) / denominator * candidate.penalty * doc.boost();
                            scores.merge(slot, score, Double::sum);
                            tokenHits.computeIfAbsent(slot, k -> new LinkedHashSet<>()).add(candidate.token);
                        }
                    }
                }
            }

            List<Integer> slots = new ArrayList<>(scores.keySet());
            Comparator<Integer> comparator = comparatorFor(query, scores);
            slots.sort(comparator);

            int total = slots.size();
            int from = Math.min(query.offset(), total);
            int to = Math.min(from + query.limit(), total);
            List<SearchHit> hits = new ArrayList<>(to - from);
            Map<String, Map<String, Integer>> facets = facetsFor(slots);

            Set<String> effectiveTokens = new LinkedHashSet<>(queryTokens);
            for (String token : queryTokens) {
                for (Candidate c : candidatesFor(token, new ArrayList<>())) if (!c.token.equals(token)) effectiveTokens.add(c.token);
            }

            for (int slot : slots.subList(from, to)) {
                SearchDocument doc = docs.get(slot);
                Set<String> matchedTokens = tokenHits.getOrDefault(slot, effectiveTokens);
                Map<String, String> matched = new LinkedHashMap<>();
                String snippet = "";
                for (Map.Entry<String, String> field : doc.fields().entrySet()) {
                    String normalised = Util.normalise(field.getValue());
                    for (String token : matchedTokens) {
                        if (token.length() > 1 && normalised.contains(token)) {
                            matched.put(field.getKey(), token);
                            if (snippet.isEmpty() && fieldWeights.getOrDefault(field.getKey(), 1) >= 2) {
                                snippet = snippet(field.getValue(), token);
                            }
                            break;
                        }
                    }
                }
                if (snippet.isEmpty() && !matched.isEmpty()) {
                    for (Map.Entry<String, String> field : doc.fields().entrySet()) {
                        for (String token : matched.values()) {
                            if (Util.normalise(field.getValue()).contains(token)) {
                                snippet = snippet(field.getValue(), token);
                                break;
                            }
                        }
                        if (!snippet.isEmpty()) break;
                    }
                }
                hits.add(SearchHit.of(doc, scores.getOrDefault(slot, 0.0), matched, snippet, Util.titleCase(doc.kind())));
            }

            long took = (System.nanoTime() - start) / 1000;
            Map<String, Object> telemetry = new LinkedHashMap<>();
            telemetry.put("engine", "sillage-inverted-index/bm25");
            telemetry.put("params", "k1=" + K1 + ", b=" + B);
            telemetry.put("corpus", corpus);
            telemetry.put("vocabulary", postings.size());
            telemetry.put("postings", postings.values().stream().mapToLong(Map::size).sum());
            telemetry.put("avgDocLength", Math.round(avgLength * 100) / 100.0);
            telemetry.put("tookMicros", took);
            telemetry.put("strategy", queryTokens.isEmpty() ? "browse" : strategyOf(expansions, corrections));
            telemetry.put("scores", scores.size());
            return new SearchResult(hits, total, took, raw, queryTokens, dedupe(expansions), dedupe(corrections),
                    facets, telemetry, query.limit(), query.offset());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Instant type-ahead: matching tokens ranked by document frequency. */
    public List<Map<String, Object>> autocomplete(String prefix, int limit) {
        String needle = Util.normalise(prefix);
        if (needle.isEmpty()) return List.of();
        lock.readLock().lock();
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<String, Map<Integer, Float>> entry : postings.entrySet()) {
                String token = entry.getKey();
                if (!token.startsWith(needle)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("token", token);
                item.put("docs", entry.getValue().size());
                out.add(item);
                if (out.size() >= limit * 6) break;
            }
            out.sort(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("docs")).reversed()
                    .thenComparing(m -> String.valueOf(m.get("token"))));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        } finally {
            lock.readLock().unlock();
        }
    }

    private record Candidate(String token, double penalty, Map<Integer, Float> docs) {}

    private List<Candidate> candidatesFor(String token, List<String> corrections) {
        List<Candidate> out = new ArrayList<>();
        Map<Integer, Float> exact = postings.get(token);
        if (exact != null && !exact.isEmpty()) {
            out.add(new Candidate(token, 1.0, exact));
            return out; // exact wins: no fuzzy noise
        }
        int prefixCount = 0;
        for (Map.Entry<String, Map<Integer, Float>> entry : postings.entrySet()) {
            String candidate = entry.getKey();
            if (candidate.length() >= token.length() && candidate.startsWith(token) && !entry.getValue().isEmpty()) {
                out.add(new Candidate(candidate, PREFIX_PENALTY, entry.getValue()));
                if (++prefixCount >= MAX_PREFIX_CANDIDATES) break;
            }
        }
        if (!out.isEmpty()) return out;

        for (String fuzzy : fuzzyTokens(token)) {
            Map<Integer, Float> posting = postings.get(fuzzy);
            if (posting != null && !posting.isEmpty()) {
                out.add(new Candidate(fuzzy, FUZZY_PENALTY, posting));
                corrections.add(fuzzy);
                if (out.size() >= MAX_FUZZY_CANDIDATES) break;
            }
        }
        if (!out.isEmpty()) return out;

        if (token.length() >= 3) {
            Map<Integer, Float> substring = substringMatches(token);
            if (!substring.isEmpty()) out.add(new Candidate(token, SUBSTRING_PENALTY, substring));
        }
        return out;
    }

    private List<String> fuzzyTokens(String token) {
        List<String> raw = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(token);
        raw.addAll(deletionIndex.getOrDefault(token, Set.of()));
        for (String variant : deletions(token)) {
            if (postings.containsKey(variant)) raw.add(variant);
            raw.addAll(deletionIndex.getOrDefault(variant, Set.of()));
        }
        int maxDistance = token.length() >= 7 ? 2 : 1;
        List<String> out = new ArrayList<>();
        for (String candidate : raw) {
            if (!seen.add(candidate)) continue;
            if (Math.abs(candidate.length() - token.length()) > maxDistance) continue;
            if (levenshtein(token, candidate, maxDistance) <= maxDistance) out.add(candidate);
        }
        out.sort(Comparator.comparingInt((String s) -> postings.containsKey(s) ? -postings.get(s).size() : 0).thenComparing(s -> s));
        return out;
    }

    /** Trigram intersection + verification, so "9876" finds phone …9876543210. */
    private Map<Integer, Float> substringMatches(String token) {
        List<String> grams = trigramsOf(token);
        Map<Integer, Float> result = null;
        grams.sort(Comparator.comparingInt(g -> trigrams.getOrDefault(g, Map.of()).size()));
        for (String gram : grams) {
            Map<Integer, Integer> posting = trigrams.get(gram);
            if (posting == null || posting.isEmpty()) return Map.of();
            if (result == null) {
                result = new HashMap<>();
                for (Integer slot : posting.keySet()) result.put(slot, 1f);
            } else {
                result.keySet().retainAll(posting.keySet());
                if (result.isEmpty()) return Map.of();
            }
        }
        if (result == null) return Map.of();
        String needle = Util.normalise(token).replace(" ", "");
        Map<Integer, Float> verified = new HashMap<>();
        for (Integer slot : result.keySet()) {
            SearchDocument doc = docs.get(slot);
            if (doc == null) continue;
            for (String field : substringFields) {
                String value = doc.fields().get(field);
                if (value == null) continue;
                if (Util.normalise(value).replace(" ", "").contains(needle)) {
                    verified.put(slot, 1f * fieldWeights.getOrDefault(field, 2));
                    break;
                }
            }
        }
        return verified;
    }

    private boolean matches(SearchDocument doc, SearchQuery query) {
        if (!query.kinds().isEmpty() && !query.kinds().contains(doc.kind())) return false;
        for (Map.Entry<String, String> filter : query.filters().entrySet()) {
            String actual = doc.attrs().get(filter.getKey());
            if (actual == null) return false;
            if (filter.getValue().startsWith("!")) {
                if (actual.equalsIgnoreCase(filter.getValue().substring(1))) return false;
            } else if (!actual.equalsIgnoreCase(filter.getValue())) {
                return false;
            }
        }
        for (SearchQuery.Range range : query.ranges()) {
            String actual = doc.attrs().get(range.attr());
            if (actual == null) return false;
            if (range.min() != null && compareAttr(actual, range.min()) < 0) return false;
            if (range.max() != null && compareAttr(actual, range.max()) > 0) return false;
        }
        return true;
    }

    private static int compareAttr(String actual, String bound) {
        Double a = asNumber(actual);
        Double b = asNumber(bound);
        if (a != null && b != null) return Double.compare(a, b);
        return actual.compareToIgnoreCase(bound);
    }

    private static Double asNumber(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Comparator<Integer> comparatorFor(SearchQuery query, Map<Integer, Double> scores) {
        String sort = query.sort() == null ? "relevance" : query.sort().toLowerCase(Locale.ROOT);
        Comparator<Integer> byTitle = Comparator.comparing(slot -> nz(docs.get(slot).title()).toLowerCase(Locale.ROOT));
        switch (sort) {
            case "title":
                return byTitle;
            case "recent":
                return Comparator.<Integer, String>comparing(
                        slot -> nz(docs.get(slot).attrs().get("updated")), Comparator.reverseOrder()).thenComparing(byTitle);
            case "relevance":
                return Comparator.<Integer, Double>comparing(
                        slot -> scores.getOrDefault(slot, 0.0), Comparator.reverseOrder()).thenComparing(byTitle);
            default:
                String attr = sort.startsWith("attr:") ? sort.substring(5) : sort;
                Comparator<Integer> byAttr = (x, y) -> compareAttr(
                        nz(docs.get(x).attrs().get(attr)),
                        nz(docs.get(y).attrs().get(attr)));
                return (query.ascending() ? byAttr : byAttr.reversed()).thenComparing(byTitle);
        }
    }

    private Map<String, Map<String, Integer>> facetsFor(List<Integer> slots) {
        Map<String, Map<String, Integer>> facets = new LinkedHashMap<>();
        for (String attr : FACET_ATTRS) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int slot : slots) {
                SearchDocument doc = docs.get(slot);
                String value = doc.attrs().get(attr);
                if (value == null || value.isBlank()) continue;
                counts.merge(value, 1, Integer::sum);
            }
            if (!counts.isEmpty()) facets.put(attr, counts);
        }
        return facets;
    }

    private static String strategyOf(List<String> expansions, List<String> corrections) {
        if (!corrections.isEmpty()) return "fuzzy (typo tolerant)";
        for (String e : expansions) {
            if (e.endsWith("*")) return "prefix";
            if (e.endsWith("\u2282")) return "substring";
        }
        return "exact";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static String snippet(String text, String token) {
        if (text == null || text.isBlank()) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(token);
        if (at < 0) {
            String normalised = Util.normalise(text);
            int nat = normalised.indexOf(token);
            if (nat < 0) return Util.truncate(text, 90);
            at = Math.min(nat, text.length() - 1);
        }
        int from = Math.max(0, at - 32);
        int to = Math.min(text.length(), at + token.length() + 48);
        return (from > 0 ? "\u2026" : "") + text.substring(from, to).trim() + (to < text.length() ? "\u2026" : "");
    }

    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalised = Util.normalise(text);
        if (normalised.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String token : normalised.split(" ")) {
            if (!token.isEmpty()) out.add(token);
        }
        return out;
    }

    static int levenshtein(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) return max + 1;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                if (current[j] < rowMin) rowMin = current[j];
            }
            if (rowMin > max) return max + 1;
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static String key(String kind, String id) { return kind + "|" + id; }

    // ------------------------------------------------------------------ telemetry

    public IndexStats stats() {
        lock.readLock().lock();
        try {
            Map<String, Integer> byKind = new LinkedHashMap<>();
            for (SearchDocument doc : docs) if (doc != null) byKind.merge(doc.kind(), 1, Integer::sum);
            long postingCount = postings.values().stream().mapToLong(Map::size).sum();
            double avgLength = liveDocs == 0 ? 0 : (double) totalTokens / liveDocs;
            long estimatedBytes = (long) (docs.size() * 320L + postingCount * 28L + trigrams.size() * 64L + deletionIndex.size() * 48L);
            return new IndexStats((int) liveDocs, postings.size(), postingCount, trigrams.size(),
                    Math.round(avgLength * 100) / 100.0, estimatedBytes, buildNanos / 1_000_000,
                    byKind, Map.copyOf(fieldWeights));
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() { return (int) liveDocs; }
}
