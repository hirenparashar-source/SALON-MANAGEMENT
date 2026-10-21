package com.sillage.service;

import com.sillage.model.Actor;
import com.sillage.model.AuditEvent;
import com.sillage.security.AuditLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actor-aware facade over the audit trail. */
public final class AuditService {

    private final AuditLog log;

    public AuditService(AuditLog log) {
        this.log = log;
    }

    public AuditEvent record(String action, String entityKind, String entityId, String summary, Actor actor) {
        return record(action, entityKind, entityId, summary, actor, AuditEvent.INFO, null);
    }

    public AuditEvent record(String action, String entityKind, String entityId, String summary, Actor actor,
                             String severity, Map<String, Object> detail) {
        Map<String, Object> safeDetail = detail == null ? new LinkedHashMap<>() : new LinkedHashMap<>(detail);
        return log.record(action, entityKind, entityId, summary, severity,
                actor == null ? null : new com.sillage.model.SessionRecord(
                        actor.sessionId(), actor.id(), actor.name(), actor.role(), actor.customerId(),
                        com.sillage.model.SessionRecord.PASSWORD, actor.ip(), actor.userAgent(), null,
                        null, null, null, 0, false, null),
                actor == null ? null : actor.ip(),
                actor == null ? null : actor.userAgent(),
                safeDetail);
    }

    public AuditEvent security(String action, String summary, String ip, Map<String, Object> detail) {
        return log.security(action, summary, ip, detail);
    }

    public AuditEvent system(String action, String summary) {
        return log.system(action, summary);
    }

    public List<AuditEvent> forEntity(String entityKind, String entityId) {
        return log.forEntity(entityKind, entityId);
    }

    public List<AuditEvent> query(String text, String severity, String action, String entityKind, int limit) {
        return log.query(text, severity, action, entityKind, limit);
    }

    public List<AuditEvent> recent(int limit) {
        return log.recent(limit);
    }

    public Map<String, Object> summary() {
        return log.summary();
    }

    public String csv(int limit) {
        return log.toCsv(limit);
    }

    public int count() {
        return log.size();
    }

    public int cap() {
        return log.cap();
    }

    /** Trims the trail to its retention cap; returns the number of events dropped. */
    public int trim() {
        return log.pruneToCap();
    }
}
