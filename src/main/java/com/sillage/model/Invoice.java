package com.sillage.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A settled (or settling) fiscal document. Payments live inside the invoice so the receipt is a
 * single self-contained record — money never has two sources of truth.
 */
public record Invoice(
        String id,
        String number,
        String customerId,
        String customerName,
        String customerPhone,
        String customerEmail,
        List<String> appointmentIds,
        List<Line> lines,
        BigDecimal subtotal,
        BigDecimal discount,
        String promoCode,
        BigDecimal loyaltyRedemption,
        int pointsRedeemed,
        BigDecimal gratuity,
        BigDecimal taxable,
        BigDecimal cgst,
        BigDecimal sgst,
        BigDecimal taxTotal,
        BigDecimal roundOff,
        BigDecimal total,
        BigDecimal paid,
        BigDecimal balance,
        InvoiceStatus status,
        List<Payment> payments,
        int pointsEarned,
        String notes,
        String footerNote,
        LocalDateTime createdAt,
        LocalDateTime settledAt,
        String createdBy,
        String staffId,
        String fiscalYear,
        String gstin,
        String source,
        String receiptCode,
        boolean printed,
        int printCount) {

    /** One printed line: service, retail product, add-on, discount or tip. */
    public record Line(
            String id,
            String kind,
            String refId,
            String name,
            String detail,
            int qty,
            BigDecimal unitPrice,
            BigDecimal taxPct,
            BigDecimal discount,
            BigDecimal amount,
            String staffId) {

        public static final String SERVICE = "SERVICE";
        public static final String PRODUCT = "PRODUCT";
        public static final String ADDON = "ADDON";
        public static final String DISCOUNT = "DISCOUNT";
        public static final String GRATUITY = "GRATUITY";
        public static final String LOYALTY = "LOYALTY";

        public boolean isDeduction() {
            return DISCOUNT.equals(kind) || LOYALTY.equals(kind);
        }

        public boolean isTaxable() {
            return SERVICE.equals(kind) || PRODUCT.equals(kind) || ADDON.equals(kind) || GRATUITY.equals(kind);
        }

        public Line withAmount(BigDecimal newAmount) {
            return new Line(id, kind, refId, name, detail, qty, unitPrice, taxPct, discount, newAmount, staffId);
        }
    }

    public List<Line> serviceLines() {
        List<Line> out = new ArrayList<>();
        for (Line line : safe(lines)) if (Line.SERVICE.equals(line.kind()) || Line.ADDON.equals(line.kind())) out.add(line);
        return out;
    }

    public List<Line> productLines() {
        List<Line> out = new ArrayList<>();
        for (Line line : safe(lines)) if (Line.PRODUCT.equals(line.kind())) out.add(line);
        return out;
    }

    public boolean isSettled() {
        return status == InvoiceStatus.PAID;
    }

    public int paymentCount() {
        return payments == null ? 0 : payments.size();
    }

    public BigDecimal refundedTotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Payment p : safe(payments)) {
            if (p.status() == PaymentStatus.REFUNDED) sum = sum.add(p.amount());
            else if (p.refunded() != null) sum = sum.add(p.refunded());
        }
        return sum;
    }

    public String primaryMethod() {
        for (Payment p : safe(payments)) {
            if (p.status() == PaymentStatus.CAPTURED || p.status() == PaymentStatus.AUTHORIZED) return p.method().label();
        }
        return safe(payments).isEmpty() ? "\u2014" : safe(payments).get(0).method().label();
    }

    public Invoice withPayments(List<Payment> newPayments, BigDecimal newPaid, BigDecimal newBalance, InvoiceStatus newStatus) {
        return new Invoice(id, number, customerId, customerName, customerPhone, customerEmail, appointmentIds, lines,
                subtotal, discount, promoCode, loyaltyRedemption, pointsRedeemed, gratuity, taxable, cgst, sgst,
                taxTotal, roundOff, total, newPaid, newBalance, newStatus, newPayments, pointsEarned, notes,
                footerNote, createdAt, newStatus == InvoiceStatus.PAID ? LocalDateTime.now() : settledAt, createdBy,
                staffId, fiscalYear, gstin, source, receiptCode, printed, printCount);
    }

    public Invoice withStatus(InvoiceStatus newStatus) {
        return new Invoice(id, number, customerId, customerName, customerPhone, customerEmail, appointmentIds, lines,
                subtotal, discount, promoCode, loyaltyRedemption, pointsRedeemed, gratuity, taxable, cgst, sgst,
                taxTotal, roundOff, total, paid, balance, newStatus, payments, pointsEarned, notes, footerNote,
                createdAt, newStatus == InvoiceStatus.PAID ? LocalDateTime.now() : settledAt, createdBy, staffId,
                fiscalYear, gstin, source, receiptCode, printed, printCount);
    }

    public Invoice withPrinted() {
        return new Invoice(id, number, customerId, customerName, customerPhone, customerEmail, appointmentIds, lines,
                subtotal, discount, promoCode, loyaltyRedemption, pointsRedeemed, gratuity, taxable, cgst, sgst,
                taxTotal, roundOff, total, paid, balance, status, payments, pointsEarned, notes, footerNote,
                createdAt, settledAt, createdBy, staffId, fiscalYear, gstin, source, receiptCode, true, printCount + 1);
    }

    public Invoice withNotes(String newNotes) {
        return new Invoice(id, number, customerId, customerName, customerPhone, customerEmail, appointmentIds, lines,
                subtotal, discount, promoCode, loyaltyRedemption, pointsRedeemed, gratuity, taxable, cgst, sgst,
                taxTotal, roundOff, total, paid, balance, status, payments, pointsEarned, newNotes, footerNote,
                createdAt, settledAt, createdBy, staffId, fiscalYear, gstin, source, receiptCode, printed, printCount);
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
