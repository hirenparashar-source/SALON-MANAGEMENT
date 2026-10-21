package com.sillage.model;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * An account holder. Password material is deliberately <em>not</em> part of this record: it lives
 * in {@link Credential}, a collection no API endpoint can read back.
 */
public record UserAccount(
        String id,
        String username,
        String email,
        String displayName,
        Role role,
        String customerId,
        String staffId,
        String phone,
        boolean active,
        boolean emailVerified,
        boolean mustChangePassword,
        int failedAttempts,
        LocalDateTime lockedUntil,
        LocalDateTime lastLoginAt,
        String lastLoginIp,
        int loginCount,
        Map<String, Object> preferences,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public boolean isLocked() {
        return lockedUntil != null && LocalDateTime.now().isBefore(lockedUntil);
    }

    public String initials() {
        return com.sillage.core.Util.initials(displayName == null || displayName.isBlank() ? username : displayName);
    }

    public UserAccount withLogin(String ip) {
        return new UserAccount(id, username, email, displayName, role, customerId, staffId, phone, active,
                emailVerified, mustChangePassword, 0, null, LocalDateTime.now(), ip, loginCount + 1, preferences,
                createdAt, LocalDateTime.now());
    }

    public UserAccount withFailedAttempt(LocalDateTime lockUntil) {
        return new UserAccount(id, username, email, displayName, role, customerId, staffId, phone, active,
                emailVerified, mustChangePassword, failedAttempts + 1, lockUntil, lastLoginAt, lastLoginIp,
                loginCount, preferences, createdAt, LocalDateTime.now());
    }

    public UserAccount withPreferences(Map<String, Object> newPreferences) {
        return new UserAccount(id, username, email, displayName, role, customerId, staffId, phone, active,
                emailVerified, mustChangePassword, failedAttempts, lockedUntil, lastLoginAt, lastLoginIp, loginCount,
                newPreferences, createdAt, LocalDateTime.now());
    }

    public UserAccount withRole(Role newRole) {
        return new UserAccount(id, username, email, displayName, newRole, customerId, staffId, phone, active,
                emailVerified, mustChangePassword, failedAttempts, lockedUntil, lastLoginAt, lastLoginIp, loginCount,
                preferences, createdAt, LocalDateTime.now());
    }

    public UserAccount withPasswordChanged() {
        return new UserAccount(id, username, email, displayName, role, customerId, staffId, phone, active,
                emailVerified, false, 0, null, lastLoginAt, lastLoginIp, loginCount, preferences, createdAt,
                LocalDateTime.now());
    }
}
