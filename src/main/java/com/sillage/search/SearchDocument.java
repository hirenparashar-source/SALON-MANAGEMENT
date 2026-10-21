package com.sillage.search;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One indexable unit. Text fields are tokenised and weighted; attributes are stored verbatim
 * for filtering, sorting and faceting.
 *
 * @param id       entity id
 * @param kind     entity kind ({@code customer}, {@code service}, {@code invoice}, …)
 * @param title    primary display line
 * @param subtitle secondary display line
 * @param href     where the console should navigate on click
 * @param fields   weighted, tokenised text (keys must exist in the index weight table)
 * @param attrs    exact-match filter/sort/facet values
 * @param boost    per-document multiplier (VIP customer, featured service, …)
 */
public record SearchDocument(
        String id,
        String kind,
        String title,
        String subtitle,
        String href,
        Map<String, String> fields,
        Map<String, String> attrs,
        double boost) {

    public static Builder builder(String id, String kind) {
        return new Builder(id, kind);
    }

    public static final class Builder {
        private final String id;
        private final String kind;
        private String title = "";
        private String subtitle = "";
        private String href = "";
        private double boost = 1.0;
        private final Map<String, String> fields = new LinkedHashMap<>();
        private final Map<String, String> attrs = new LinkedHashMap<>();

        private Builder(String id, String kind) {
            this.id = id;
            this.kind = kind;
        }

        public Builder title(String value) { this.title = value == null ? "" : value; return this; }

        public Builder subtitle(String value) { this.subtitle = value == null ? "" : value; return this; }

        public Builder href(String value) { this.href = value == null ? "" : value; return this; }

        public Builder boost(double value) { this.boost = value; return this; }

        public Builder field(String name, String value) {
            if (value != null && !value.isBlank()) fields.put(name, value);
            return this;
        }

        public Builder fields(String name, Iterable<String> values) {
            if (values == null) return this;
            StringBuilder sb = new StringBuilder();
            for (String v : values) {
                if (v == null || v.isBlank()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(v);
            }
            return field(name, sb.toString());
        }

        public Builder attr(String name, Object value) {
            if (value == null) return this;
            String s = String.valueOf(value);
            if (!s.isBlank()) attrs.put(name, s);
            return this;
        }

        public SearchDocument build() {
            return new SearchDocument(id, kind, title, subtitle, href, Map.copyOf(fields), Map.copyOf(attrs), boost);
        }
    }
}
