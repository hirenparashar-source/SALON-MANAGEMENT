package com.sillage.model;

import java.util.List;

/**
 * A lookbook entry — the editorial side of the catalogue ("Autumn Copper Balayage").
 * Looks link back to the services required to recreate them, so the public menu reads like a
 * magazine spread instead of a price list.
 */
public record Look(
        String id,
        String title,
        String season,
        String stylistId,
        List<String> serviceIds,
        List<String> palette,
        String notes,
        String story,
        String imageKey,
        int minutes,
        boolean featured,
        boolean active,
        String createdAt) {

    public Look withFeatured(boolean value) {
        return new Look(id, title, season, stylistId, serviceIds, palette, notes, story, imageKey, minutes, value, active, createdAt);
    }
}
