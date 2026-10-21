package com.sillage.model;

import java.time.LocalDateTime;

/**
 * A server-side session. The {@code id} is the SHA-256 of the opaque cookie value, so a leaked
 * database file cannot be replayed as a session, and the raw token never touches disk.
 */
public record SessionRecord(
        String id,
        String userId,
        String displayName,
        Role role,
        String customerId,
        String kind,
        String ip,
        String userAgent,
        String csrfToken,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime lastSeenAt,
        int requestCount,
        boolean revoked,
        String revokedReason) {

    public static final String PASSWORD = "PASSWORD";
    public static final String GUEST = "GUEST";

    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isActive() {
        return !revoked && !isExpired();
    }

    public SessionRecord touched(LocalDateTime when, LocalDateTime newExpiry) {
        return new SessionRecord(id, userId, displayName, role, customerId, kind, ip, userAgent, csrfToken,
                createdAt, newExpiry, when, requestCount + 1, revoked, revokedReason);
    }

    public SessionRecord withExpiry(LocalDateTime newExpiry) {
        return new SessionRecord(id, userId, displayName, role, customerId, kind, ip, userAgent, csrfToken,
                createdAt, newExpiry, lastSeenAt, requestCount, revoked, revokedReason);
    }

    public SessionRecord revoked(String reason) {
        return new SessionRecord(id, userId, displayName, role, customerId, kind, ip, userAgent, csrfToken,
                createdAt, expiresAt, LocalDateTime.now(), requestCount, true, reason);
    }

    /** What the "active sessions" screen is allowed to show. */
    public SessionRecord redacted() {
        return new SessionRecord(id.substring(0, Math.min(8, id.length())) + "\u2026", userId, displayName, role,
                customerId, kind, ip, userAgent == null ? null : com.sillage.core.Util.truncate(userAgent, 80),
                null, createdAt, expiresAt, lastSeenAt, requestCount, revoked, revokedReason);
    }
}
