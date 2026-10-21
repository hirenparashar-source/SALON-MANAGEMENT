package com.sillage.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A booking. Covers scheduled appointments, walk-in queue tickets and the waitlist — they are
 * the same record with a different {@link AppointmentStatus}.
 */
public record Appointment(
        String id,
        String code,
        String customerId,
        String guestName,
        String guestPhone,
        String guestEmail,
        String staffId,
        List<Item> items,
        LocalDateTime start,
        LocalDateTime end,
        int durationMinutes,
        AppointmentStatus status,
        BigDecimal price,
        BigDecimal deposit,
        String notes,
        String internalNotes,
        String source,
        int queueNumber,
        String invoiceId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime confirmedAt,
        LocalDateTime completedAt,
        LocalDateTime cancelledAt,
        String cancellationReason,
        LocalDateTime reminderSentAt,
        Integer rating,
        String ratingComment,
        String createdBy) {

    /** One service line inside the booking, with its chosen tier. */
    public record Item(String serviceId, String variantId, String name, int durationMinutes, BigDecimal price) {}

    public boolean isGuest() {
        return customerId == null || customerId.isBlank();
    }

    public String customerLabel() {
        return isGuest() ? com.sillage.core.Util.nz(guestName, "Walk-in guest") : null;
    }

    public List<String> serviceIds() {
        if (items == null) return List.of();
        return items.stream().map(Item::serviceId).toList();
    }

    public List<String> serviceNames() {
        if (items == null) return List.of();
        return items.stream().map(Item::name).toList();
    }

    public Appointment withStatus(AppointmentStatus newStatus, LocalDateTime timestamp) {
        return new Appointment(id, code, customerId, guestName, guestPhone, guestEmail, staffId, items, start, end,
                durationMinutes, newStatus, price, deposit, notes, internalNotes, source, queueNumber, invoiceId,
                createdAt, timestamp,
                newStatus == AppointmentStatus.CONFIRMED ? timestamp : confirmedAt,
                newStatus.isBillable() ? timestamp : completedAt,
                newStatus == AppointmentStatus.CANCELLED || newStatus == AppointmentStatus.NO_SHOW ? timestamp : cancelledAt,
                cancellationReason, reminderSentAt, rating, ratingComment, createdBy);
    }

    public Appointment withWindow(LocalDateTime newStart, LocalDateTime newEnd, int newDuration) {
        return new Appointment(id, code, customerId, guestName, guestPhone, guestEmail, staffId, items, newStart, newEnd,
                newDuration, status, price, deposit, notes, internalNotes, source, queueNumber, invoiceId,
                createdAt, LocalDateTime.now(), confirmedAt, completedAt, cancelledAt, cancellationReason,
                reminderSentAt, rating, ratingComment, createdBy);
    }

    public Appointment withInvoice(String newInvoiceId, AppointmentStatus newStatus) {
        return new Appointment(id, code, customerId, guestName, guestPhone, guestEmail, staffId, items, start, end,
                durationMinutes, newStatus, price, deposit, notes, internalNotes, source, queueNumber, newInvoiceId,
                createdAt, LocalDateTime.now(), confirmedAt,
                newStatus != null && newStatus.isBillable() ? LocalDateTime.now() : completedAt,
                cancelledAt, cancellationReason, reminderSentAt, rating, ratingComment, createdBy);
    }

    public Appointment withStaff(String newStaffId) {
        return new Appointment(id, code, customerId, guestName, guestPhone, guestEmail, newStaffId, items, start, end,
                durationMinutes, status, price, deposit, notes, internalNotes, source, queueNumber, invoiceId,
                createdAt, LocalDateTime.now(), confirmedAt, completedAt, cancelledAt, cancellationReason,
                reminderSentAt, rating, ratingComment, createdBy);
    }

    public Appointment withReminder(LocalDateTime sentAt) {
        return new Appointment(id, code, customerId, guestName, guestPhone, guestEmail, staffId, items, start, end,
                durationMinutes, status, price, deposit, notes, internalNotes, source, queueNumber, invoiceId,
                createdAt, LocalDateTime.now(), confirmedAt, completedAt, cancelledAt, cancellationReason,
                sentAt, rating, ratingComment, createdBy);
    }

    public Appointment withRating(Integer newRating, String comment) {
        return new Appointment(id, code, customerId, guestName, guestPhone, guestEmail, staffId, items, start, end,
                durationMinutes, status, price, deposit, notes, internalNotes, source, queueNumber, invoiceId,
                createdAt, LocalDateTime.now(), confirmedAt, completedAt, cancelledAt, cancellationReason,
                reminderSentAt, newRating, comment, createdBy);
    }

    public Appointment withCancellation(String reason) {
        return new Appointment(id, code, customerId, guestName, guestPhone, guestEmail, staffId, items, start, end,
                durationMinutes, AppointmentStatus.CANCELLED, price, deposit, notes, internalNotes, source,
                queueNumber, invoiceId, createdAt, LocalDateTime.now(), confirmedAt, completedAt, LocalDateTime.now(),
                reason, reminderSentAt, rating, ratingComment, createdBy);
    }
}
