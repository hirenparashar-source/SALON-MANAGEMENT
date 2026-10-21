package com.sillage.model;

import java.time.LocalDateTime;

/**
 * A queued outbound message (SMS / e-mail / WhatsApp). Nothing leaves the machine: the transport
 * is a local outbox that renders the template and records what <em>would</em> be sent, which keeps
 * the suite runnable with zero third-party credentials while still exercising the whole flow.
 */
public record Notification(
        String id,
        String channel,
        String to,
        String subject,
        String body,
        String template,
        String refKind,
        String refId,
        String status,
        String error,
        String customerId,
        LocalDateTime createdAt,
        LocalDateTime sentAt,
        String createdBy,
        boolean dryRun) {

    public static final String SMS = "SMS";
    public static final String EMAIL = "EMAIL";
    public static final String WHATSAPP = "WHATSAPP";

    public static final String QUEUED = "QUEUED";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    public Notification sent() {
        return new Notification(id, channel, to, subject, body, template, refKind, refId, SENT, null, customerId,
                createdAt, LocalDateTime.now(), createdBy, dryRun);
    }

    public Notification failed(String reason) {
        return new Notification(id, channel, to, subject, body, template, refKind, refId, FAILED, reason, customerId,
                createdAt, LocalDateTime.now(), createdBy, dryRun);
    }
}
