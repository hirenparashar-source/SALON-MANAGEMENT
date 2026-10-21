package com.sillage.security;

import com.sillage.core.Log;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.Role;
import com.sillage.model.SessionRecord;
import com.sillage.model.UserAccount;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Server-side session store.
 *
 * <p>The cookie carries an opaque 256-bit token; only {@code SHA-256(token)} is persisted, so a
 * stolen data directory cannot be replayed as a session. Sessions slide on activity up to an
 * absolute lifetime, and can be revoked individually or wholesale (password change, "sign out
 * everywhere", account disable).
 */
public final class SessionManager {

    public static final String COOKIE = "sillage_sid";
    public static final String CSRF_COOKIE = "sillage_csrf";

    private static final Duration IDLE_TIMEOUT = Duration.ofHours(12);
    private static final Duration ABSOLUTE_TIMEOUT = Duration.ofDays(14);
    private static final Duration GUEST_TIMEOUT = Duration.ofHours(6);
    private static final Duration TOUCH_INTERVAL = Duration.ofSeconds(30);

    private final Repo<SessionRecord> sessions;

    public SessionManager(Repo<SessionRecord> sessions) {
        this.sessions = sessions;
    }

    /** A freshly minted token plus its record. */
    public record Issued(String token, SessionRecord session) {}

    public Issued issue(UserAccount user, String ip, String userAgent) {
        return create(user == null ? null : user.id(),
                user == null ? "Guest" : Util.nz(user.displayName(), user.username()),
                user == null ? Role.GUEST : user.role(),
                user == null ? null : user.customerId(),
                SessionRecord.PASSWORD, ip, userAgent,
                user == null ? GUEST_TIMEOUT : IDLE_TIMEOUT);
    }

    public Issued issueGuest(String ip, String userAgent, String customerId) {
        return create(null, "Guest", Role.GUEST, customerId, SessionRecord.GUEST, ip, userAgent, GUEST_TIMEOUT);
    }

    private Issued create(String userId, String displayName, Role role, String customerId, String kind,
                          String ip, String userAgent, Duration idle) {
        String token = Util.randomToken(32);
        String id = Util.sha256Hex(token);
        LocalDateTime now = LocalDateTime.now();
        SessionRecord record = new SessionRecord(id, userId, displayName, role, customerId, kind,
                truncate(ip), Util.truncate(userAgent, 200), Util.randomToken(18), now,
                now.plus(idle), now, 0, false, null);
        sessions.put(record);
        pruneIfHeavy();
        return new Issued(token, record);
    }

    public Optional<SessionRecord> resolve(String token) {
        if (Util.blank(token)) return Optional.empty();
        SessionRecord record = sessions.get(Util.sha256Hex(token));
        if (record == null) return Optional.empty();
        if (record.revoked()) return Optional.empty();
        if (record.isExpired()) {
            sessions.remove(record.id());
            return Optional.empty();
        }
        return Optional.of(record);
    }

    /** Slides the expiry, at most once per {@link #TOUCH_INTERVAL} to keep writes off the hot path. */
    public void touch(SessionRecord record) {
        if (record == null) return;
        LocalDateTime now = LocalDateTime.now();
        if (record.lastSeenAt() != null && Duration.between(record.lastSeenAt(), now).compareTo(TOUCH_INTERVAL) < 0) return;
        Duration idle = SessionRecord.GUEST.equals(record.kind()) ? GUEST_TIMEOUT : IDLE_TIMEOUT;
        LocalDateTime sliding = now.plus(idle);
        LocalDateTime absolute = record.createdAt().plus(ABSOLUTE_TIMEOUT);
        sessions.put(record.touched(now, sliding.isAfter(absolute) ? absolute : sliding));
    }

    public void revoke(String sessionId, String reason) {
        SessionRecord record = sessions.get(sessionId);
        if (record != null && !record.revoked()) sessions.put(record.revoked(reason));
    }

    public void revokeToken(String token, String reason) {
        if (Util.blank(token)) return;
        revoke(Util.sha256Hex(token), reason);
    }

    public int revokeAllForUser(String userId, String reason) {
        if (userId == null) return 0;
        int count = 0;
        for (SessionRecord record : sessions.where(s -> userId.equals(s.userId()) && !s.revoked())) {
            sessions.put(record.revoked(reason));
            count++;
        }
        if (count > 0) Log.info("revoked %d session(s) for user %s (%s)", count, userId, reason);
        return count;
    }

    public List<SessionRecord> forUser(String userId) {
        List<SessionRecord> out = new ArrayList<>();
        for (SessionRecord record : sessions.where(s -> userId.equals(s.userId()))) {
            if (record.isActive()) out.add(record.redacted());
        }
        out.sort((a, b) -> b.lastSeenAt().compareTo(a.lastSeenAt()));
        return out;
    }

    public int prune() {
        List<SessionRecord> stale = sessions.where(s -> s.isExpired() || (s.revoked()
                && s.lastSeenAt() != null && s.lastSeenAt().isBefore(LocalDateTime.now().minusDays(7))));
        for (SessionRecord record : stale) sessions.remove(record.id());
        return stale.size();
    }

    private void pruneIfHeavy() {
        if (sessions.size() > 500) prune();
    }

    public int activeSessions() {
        return (int) sessions.countWhere(SessionRecord::isActive);
    }

    // ------------------------------------------------------------------ cookies

    public String cookieHeader(String token, boolean secure, Duration maxAge) {
        StringBuilder sb = new StringBuilder();
        sb.append(COOKIE).append('=').append(token).append("; Path=/; HttpOnly; SameSite=Lax; Version=1");
        if (maxAge != null) sb.append("; Max-Age=").append(maxAge.toSeconds());
        if (secure) sb.append("; Secure");
        return sb.toString();
    }

    public String guestCookieHeader(String token, boolean secure) {
        return cookieHeader(token, secure, GUEST_TIMEOUT);
    }

    public String userCookieHeader(String token, boolean secure) {
        return cookieHeader(token, secure, ABSOLUTE_TIMEOUT);
    }

    public String clearCookieHeader(boolean secure) {
        StringBuilder sb = new StringBuilder();
        sb.append(COOKIE).append("=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        if (secure) sb.append("; Secure");
        return sb.toString();
    }

    public String csrfCookieHeader(String csrfToken, boolean secure) {
        StringBuilder sb = new StringBuilder();
        sb.append(CSRF_COOKIE).append('=').append(csrfToken).append("; Path=/; SameSite=Lax");
        if (secure) sb.append("; Secure");
        return sb.toString();
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
