package com.sillage.model;

/** Lifecycle of a booking. */
public enum AppointmentStatus {
    /** Online request awaiting confirmation. */
    REQUESTED,
    CONFIRMED,
    /** Guest has checked in at the desk. */
    ARRIVED,
    IN_CHAIR,
    COMPLETED,
    CANCELLED,
    NO_SHOW,
    /** Waiting for a free chair (walk-in queue). */
    WAITLISTED;

    public boolean isClosed() {
        return this == COMPLETED || this == CANCELLED || this == NO_SHOW;
    }

    public boolean occupiesChair() {
        return this == CONFIRMED || this == ARRIVED || this == IN_CHAIR || this == COMPLETED || this == REQUESTED;
    }

    public boolean isBillable() {
        return this == COMPLETED;
    }

    public static AppointmentStatus parse(String value) {
        if (value == null || value.isBlank()) return REQUESTED;
        try {
            return AppointmentStatus.valueOf(value.trim().toUpperCase().replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return REQUESTED;
        }
    }

    public String label() {
        return switch (this) {
            case REQUESTED -> "Requested";
            case CONFIRMED -> "Confirmed";
            case ARRIVED -> "Arrived";
            case IN_CHAIR -> "In chair";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
            case NO_SHOW -> "No show";
            case WAITLISTED -> "Waitlisted";
        };
    }
}
