package com.sillage.model;

/** Tender types accepted at the desk and online. */
public enum PaymentMethod {
    CARD,
    UPI,
    NETBANKING,
    WALLET,
    CASH,
    GIFT_CARD,
    LOYALTY,
    /** Pay-at-salon: booking confirmed without money moving online. */
    PAY_AT_SALON;

    public boolean isOnline() {
        return this == CARD || this == UPI || this == NETBANKING || this == WALLET;
    }

    public boolean needsGateway() {
        return isOnline();
    }

    public static PaymentMethod parse(String value) {
        if (value == null || value.isBlank()) return CASH;
        String normalised = value.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        if (normalised.equals("PAY_AT_SALON") || normalised.equals("PAY_LATER") || normalised.equals("SALON")) return PAY_AT_SALON;
        try {
            return PaymentMethod.valueOf(normalised);
        } catch (IllegalArgumentException e) {
            return CASH;
        }
    }

    public String label() {
        return switch (this) {
            case CARD -> "Card";
            case UPI -> "UPI";
            case NETBANKING -> "Net banking";
            case WALLET -> "Wallet";
            case CASH -> "Cash";
            case GIFT_CARD -> "Gift card";
            case LOYALTY -> "Loyalty points";
            case PAY_AT_SALON -> "Pay at salon";
        };
    }
}
