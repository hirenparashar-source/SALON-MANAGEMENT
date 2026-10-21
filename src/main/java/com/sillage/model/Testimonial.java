package com.sillage.model;

import java.time.LocalDate;
import java.util.List;

/** A published client quote for the landing page. */
public record Testimonial(
        String id,
        String authorName,
        String handle,
        String quote,
        int rating,
        List<String> serviceIds,
        String staffId,
        LocalDate date,
        boolean approved,
        boolean featured,
        String source,
        String createdAt) {
}
