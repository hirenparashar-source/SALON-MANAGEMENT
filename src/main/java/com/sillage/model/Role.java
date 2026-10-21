package com.sillage.model;

/**
 * Access roles. Ordered from most to least privileged: {@code Role#includes(Role)} answers
 * "may this actor perform an action that requires {@code other}?".
 */
public enum Role {
    /** Owner / principal — everything, including settings, refunds and audit trail. */
    OWNER,
    /** Manager — day-to-day operations, no destructive data operations. */
    MANAGER,
    /** Front desk — booking, billing, customer records. */
    RECEPTION,
    /** Stylist — own calendar, own clients, service notes. */
    STYLIST,
    /** Registered client — own profile, bookings and receipts. */
    CLIENT,
    /** Browsing without an account. Read-only public data plus guest checkout. */
    GUEST;

    public boolean includes(Role required) {
        if (required == null) return true;
        return ordinal() <= required.ordinal();
    }

    public boolean isStaff() {
        return this == OWNER || this == MANAGER || this == RECEPTION || this == STYLIST;
    }

    public boolean canManageSettings() {
        return this == OWNER;
    }

    public boolean canRefund() {
        return this == OWNER || this == MANAGER;
    }

    public boolean canViewAllCustomers() {
        return isStaff();
    }

    public static Role parse(String value) {
        if (value == null || value.isBlank()) return GUEST;
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return GUEST;
        }
    }

    public String label() {
        return switch (this) {
            case OWNER -> "Owner";
            case MANAGER -> "Manager";
            case RECEPTION -> "Front desk";
            case STYLIST -> "Stylist";
            case CLIENT -> "Client";
            case GUEST -> "Guest";
        };
    }
}
