package com.sillage.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What to look for, and how to slice it. */
public final class SearchQuery {

    /** Inclusive range on an attribute; bounds may be null (open ended). */
    public record Range(String attr, String min, String max) {}

    private final String text;
    private final Set<String> kinds;
    private final Map<String, String> filters;
    private final List<Range> ranges;
    private final int limit;
    private final int offset;
    private final String sort;
    private final String sortField;
    private final boolean ascending;

    private SearchQuery(Builder b) {
        this.text = b.text;
        this.kinds = Set.copyOf(b.kinds);
        this.filters = Map.copyOf(b.filters);
        this.ranges = List.copyOf(b.ranges);
        this.limit = b.limit;
        this.offset = b.offset;
        this.sort = b.sort;
        this.sortField = b.sortField;
        this.ascending = b.ascending;
    }

    public String text() { return text; }
    public Set<String> kinds() { return kinds; }
    public Map<String, String> filters() { return filters; }
    public List<Range> ranges() { return ranges; }
    public int limit() { return limit; }
    public int offset() { return offset; }
    public String sort() { return sort; }
    public String sortField() { return sortField; }
    public boolean ascending() { return ascending; }

    public static Builder builder() { return new Builder(); }

    public static SearchQuery of(String text) { return builder().text(text).build(); }

    public static final class Builder {
        private String text = "";
        private final Set<String> kinds = new LinkedHashSet<>();
        private final Map<String, String> filters = new LinkedHashMap<>();
        private final List<Range> ranges = new ArrayList<>();
        private int limit = 25;
        private int offset = 0;
        private String sort = "relevance";
        private String sortField = "updated";
        private boolean ascending = false;

        public Builder text(String value) { this.text = value == null ? "" : value; return this; }
        public Builder kind(String value) { if (value != null && !value.isBlank()) kinds.add(value); return this; }
        public Builder kinds(Collection<String> values) { if (values != null) values.forEach(this::kind); return this; }
        public Builder filter(String attr, String value) {
            if (value != null && !value.isBlank()) filters.put(attr, value);
            return this;
        }
        public Builder range(String attr, String min, String max) {
            if (attr != null && (min != null || max != null)) ranges.add(new Range(attr, min, max));
            return this;
        }
        public Builder limit(int value) { this.limit = Math.max(1, Math.min(500, value)); return this; }
        public Builder offset(int value) { this.offset = Math.max(0, value); return this; }
        public Builder sort(String value) { this.sort = value == null ? "relevance" : value; return this; }
        public Builder sortField(String value) { if (value != null && !value.isBlank()) this.sortField = value; return this; }
        public Builder ascending(boolean value) { this.ascending = value; return this; }
        public SearchQuery build() { return new SearchQuery(this); }
    }
}
