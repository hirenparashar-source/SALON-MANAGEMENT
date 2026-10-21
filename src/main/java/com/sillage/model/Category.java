package com.sillage.model;

import java.util.List;

/**
 * A catalogue category — the "chapter" headings of the service menu.
 *
 * @param blurb   one-line editorial description shown on the public menu
 * @param accent  palette key used by the front end (brass, oxblood, verdigris, stone…)
 */
public record Category(
        String id,
        String name,
        String blurb,
        String accent,
        int order,
        boolean active,
        List<String> tags) {

    public static Category create(String id, String name, String blurb, String accent, int order) {
        return new Category(id, name, blurb, accent, order, true, List.of());
    }

    public Category withOrder(int newOrder) {
        return new Category(id, name, blurb, accent, newOrder, active, tags);
    }
}
