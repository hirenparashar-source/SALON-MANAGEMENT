package com.sillage.model;

/** Invoice lifecycle. */
public enum InvoiceStatus {
    /** Being built at the desk; not yet issued. */
    DRAFT,
    UNPAID,
    PARTIAL,
    PAID,
    /** Fully or partially refunded. */
    REFUNDED,
    VOID;

    public boolean isOpen() {
        return this == UNPAID || this == PARTIAL || this == DRAFT;
    }

    public static InvoiceStatus parse(String value) {
        if (value == null || value.isBlank()) return DRAFT;
        try {
            return InvoiceStatus.valueOf(value.trim().toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return DRAFT;
        }
    }

    public String label() {
        return switch (this) {
            case DRAFT -> "Draft";
            case UNPAID -> "Unpaid";
            case PARTIAL -> "Part paid";
            case PAID -> "Settled";
            case REFUNDED -> "Refunded";
            case VOID -> "Void";
        };
    }
}
