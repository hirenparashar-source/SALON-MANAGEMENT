package com.sillage.model;

import java.time.LocalDateTime;
import java.util.Map;

/** An append-only security / operations audit trail entry. */
public record AuditEvent(
        String id,
        LocalDateTime at,
        String actorId,
        String actorName,
        Role role,
        String action,
        String entityKind,
        String entityId,
        String summary,
        String ip,
        String userAgent,
        String severity,
        Map<String, Object> detail) {

    public static final String INFO = "INFO";
    public static final String WARN = "WARN";
    public static final String CRITICAL = "CRITICAL";

    public static AuditEvent of(String action, String entityKind, String entityId, String summary, String severity) {
        return new AuditEvent(com.sillage.core.Util.id("aud"), LocalDateTime.now(), null, null, null, action,
                entityKind, entityId, summary, null, null, severity, Map.of());
    }
}
