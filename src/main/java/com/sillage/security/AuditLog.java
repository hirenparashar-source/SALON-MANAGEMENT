package com.sillage.security;

import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.AuditEvent;
import com.sillage.model.Role;
import com.sillage.model.SessionRecord;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Append-only audit trail: who did what, from where, and what the system thought about it.
 *
 * <p>Kept to {@code maxEntries} records (oldest dropped) so the file cannot grow without bound,
 * with severity weighting that protects security events from being evicted first.
 */
public final class AuditLog {

    private static final int DEFAULT_MAX_ENTRIES = 6000;

    private final Repo<AuditEvent> repo;
    private final int maxEntries;

    public AuditLog(Repo<AuditEvent> repo) {
        this(repo, DEFAULT_MAX_ENTRIES);
    }

    public AuditLog(Repo<AuditEvent> repo, int maxEntries) {
        this.repo = repo;
        this.maxEntries = maxEntries;
    }

    public AuditEvent record(String action, String entityKind, String entityId, String summary,
                             String severity, SessionRecord actor, String ip, String userAgent,
                             Map<String, Object> detail) {
        AuditEvent event = new AuditEvent(Util.id("aud"), LocalDateTime.now(),
                actor == null ? null : actor.userId(),
                actor == null ? "anonymous" : Util.nz(actor.displayName(), "system"),
                actor == null ? Role.GUEST : actor.role(),
                action, entityKind, entityId, summary, ip, Util.truncate(userAgent, 160),
                Util.nz(severity, AuditEvent.INFO),
                detail == null ? Map.of() : detail);
        repo.put(event);
        if (repo.size() > maxEntries) prune();
        return event;
    }

    public AuditEvent record(String action, String entityKind, String entityId, String summary, SessionRecord actor) {
        return record(action, entityKind, entityId, summary, AuditEvent.INFO, actor, null, null, null);
    }

    public AuditEvent system(String action, String summary) {
        return record(action, "system", null, summary, AuditEvent.INFO, null, "127.0.0.1", "sillage", null);
    }

    public AuditEvent security(String action, String summary, String ip, Map<String, Object> detail) {
        return record(action, "security", null, summary, AuditEvent.WARN, null, ip, null, detail);
    }

    private void prune() {
        List<AuditEvent> all = repo.all();
        if (all.size() <= maxEntries) return;
        all.sort(Comparator.comparing(AuditEvent::at));
        List<AuditEvent> retained = new ArrayList<>();
        int drop = all.size() - maxEntries;
        int dropped = 0;
        for (AuditEvent event : all) {
            boolean critical = AuditEvent.CRITICAL.equals(event.severity()) || AuditEvent.WARN.equals(event.severity());
            if (dropped < drop && !critical) {
                repo.remove(event.id());
                dropped++;
                continue;
            }
            retained.add(event);
        }
        // If everything left is WARN/CRITICAL we still must respect the cap.
        while (repo.size() > maxEntries * 1.5) {
            AuditEvent oldest = retained.isEmpty() ? null : retained.get(0);
            if (oldest == null) break;
            repo.remove(oldest.id());
            retained.remove(0);
        }
    }

    public int size() { return repo.size(); }

    public int cap() { return maxEntries; }

    /** Enforces the retention cap on demand and reports how much was dropped. */
    public int pruneToCap() {
        int before = repo.size();
        prune();
        return before - repo.size();
    }

    public List<AuditEvent> recent(int limit) {
        List<AuditEvent> all = repo.all();
        all.sort(Comparator.comparing(AuditEvent::at).reversed());
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    public List<AuditEvent> query(String text, String severity, String action, String entityKind, int limit) {
        String needle = Util.normalise(text);
        List<AuditEvent> out = new ArrayList<>();
        for (AuditEvent event : repo.all()) {
            if (Util.present(severity) && !severity.equalsIgnoreCase(event.severity())) continue;
            if (Util.present(action) && !event.action().toLowerCase(Locale.ROOT).contains(action.toLowerCase(Locale.ROOT))) continue;
            if (Util.present(entityKind) && !entityKind.equalsIgnoreCase(event.entityKind())) continue;
            if (!needle.isEmpty()) {
                String haystack = Util.normalise(Util.join(List.of(
                        Util.nz(event.action()), Util.nz(event.summary()), Util.nz(event.actorName()),
                        Util.nz(event.entityKind()), Util.nz(event.entityId()), Util.nz(event.ip())), " "));
                boolean matched = false;
                for (String token : needle.split(" ")) if (haystack.contains(token)) { matched = true; break; }
                if (!matched) continue;
            }
            out.add(event);
        }
        out.sort(Comparator.comparing(AuditEvent::at).reversed());
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    public List<AuditEvent> forEntity(String entityKind, String entityId) {
        List<AuditEvent> out = repo.where(e -> entityKind.equals(e.entityKind()) && entityId.equals(e.entityId()));
        out.sort(Comparator.comparing(AuditEvent::at).reversed());
        return out;
    }

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        Map<String, Integer> byAction = new LinkedHashMap<>();
        Map<String, Integer> byDay = new LinkedHashMap<>();
        int total = 0;
        LocalDateTime latest = null;
        for (AuditEvent event : repo.all()) {
            total++;
            bySeverity.merge(Util.nz(event.severity(), AuditEvent.INFO), 1, Integer::sum);
            byAction.merge(Util.nz(event.action()), 1, Integer::sum);
            if (event.at() != null) {
                byDay.merge(event.at().toLocalDate().toString(), 1, Integer::sum);
                if (latest == null || event.at().isAfter(latest)) latest = event.at();
            }
        }
        out.put("total", total);
        out.put("bySeverity", bySeverity);
        out.put("byAction", topOf(byAction, 12));
        out.put("lastEventAt", latest == null ? null : latest.toString());
        out.put("today", byDay.getOrDefault(LocalDate.now().toString(), 0));
        out.put("retainedMax", maxEntries);
        return out;
    }

    public String toCsv(int limit) {
        List<List<String>> rows = new ArrayList<>();
        for (AuditEvent event : recent(limit)) {
            rows.add(List.of(
                    event.at() == null ? "" : event.at().toString(),
                    Util.nz(event.severity()),
                    Util.nz(event.action()),
                    Util.nz(event.actorName()),
                    event.role() == null ? "" : event.role().name(),
                    Util.nz(event.entityKind()),
                    Util.nz(event.entityId()),
                    Util.nz(event.summary()),
                    Util.nz(event.ip())));
        }
        return Util.toCsv(List.of("at", "severity", "action", "actor", "role", "entity_kind", "entity_id", "summary", "ip"), rows);
    }

    private static Map<String, Integer> topOf(Map<String, Integer> counts, int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) out.put(entries.get(i).getKey(), entries.get(i).getValue());
        return out;
    }
}
