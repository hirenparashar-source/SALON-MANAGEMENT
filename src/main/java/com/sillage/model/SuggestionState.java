package com.sillage.model;

import java.time.LocalDateTime;

/** Staff feedback on a suggestion, so the engine can suppress or promote repeats. */
public record SuggestionState(
        String id,
        String key,
        String status,
        String note,
        String entityId,
        String kind,
        LocalDateTime at,
        String byName) {

    public static final String DISMISSED = "DISMISSED";
    public static final String SAVED = "SAVED";
    public static final String DONE = "DONE";
}
