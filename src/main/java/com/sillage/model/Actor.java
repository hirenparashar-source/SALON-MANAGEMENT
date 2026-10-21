package com.sillage.model;

import com.sillage.core.ApiException;
import com.sillage.core.Util;

/**
 * Who is performing an action. Built by the API layer from the request and passed down into the
 * services so that auditing and permission checks never depend on HTTP plumbing.
 */
public record Actor(
        String id,
        String name,
        Role role,
        String ip,
        String userAgent,
        String customerId,
        String sessionId,
        boolean authenticated) {

    public static Actor anonymous(String ip, String userAgent) {
        return new Actor(null, "anonymous", Role.GUEST, ip, userAgent, null, null, false);
    }

    /**
     * The salon acting on its own behalf: used by the seeder and by public flows that have already
     * been authorised by some other proof (a booking code plus the phone number it was made with).
     */
    public static Actor system() {
        return new Actor("system", "Front desk", Role.MANAGER, "127.0.0.1", "sillage-internal", null, null, true);
    }

    public static Actor guest(SessionRecord session, String ip, String userAgent) {
        return new Actor(null, "guest", Role.GUEST, ip, userAgent,
                session == null ? null : session.customerId(),
                session == null ? null : session.id(), false);
    }

    public static Actor of(UserAccount account, SessionRecord session, String ip, String userAgent) {
        Role role = account == null ? Role.GUEST : account.role();
        if (account == null) return anonymous(ip, userAgent);
        return new Actor(account.id(), Util.nz(account.displayName(), account.username()), role, ip, userAgent,
                account.customerId(), session == null ? null : session.id(), true);
    }

    public boolean isStaff() { return role != null && role.isStaff(); }

    public boolean isClient() { return role == Role.CLIENT; }

    public boolean isGuest() { return role == Role.GUEST || role == null; }

    public boolean has(Role required) { return role != null && role.includes(required); }

    /** Throws 403 unless the actor holds at least {@code required}. */
    public Actor require(Role required) {
        if (!has(required)) throw ApiException.forbidden(labelFor(required) + " access is required for this action");
        return this;
    }

    public Actor requireStaff() {
        if (!isStaff()) throw ApiException.forbidden("This area is for salon staff");
        return this;
    }

    /** A client may only touch their own record. */
    public void requireSelfOrStaff(String customerId) {
        if (isStaff()) return;
        if (customerId != null && customerId.equals(this.customerId)) return;
        throw ApiException.forbidden("You can only view your own record");
    }

    public String label() { return Util.nz(name, role == null ? "anonymous" : role.label()); }

    private static String labelFor(Role role) {
        return role == null ? "Authenticated" : role.label();
    }
}
