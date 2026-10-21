package com.sillage.service;

import com.sillage.core.Log;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.Appointment;
import com.sillage.model.BusinessSettings;
import com.sillage.model.Customer;
import com.sillage.model.Invoice;
import com.sillage.model.Notification;
import com.sillage.model.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders message templates and writes them to a local outbox.
 *
 * <p>No SMS or mail provider is called: the transport is intentionally a dry run so the suite runs
 * with zero credentials. Every message is stored with its rendered body, channel and delivery
 * state, exactly as a real integration would record it — swapping in a provider means
 * implementing one method, {@link #transmit(Notification)}.
 */
public final class NotificationService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM");

    private final Repo<Notification> outbox;
    private final SettingsService settings;

    public NotificationService(Repo<Notification> outbox, SettingsService settings) {
        this.outbox = outbox;
        this.settings = settings;
    }

    /** Template variables resolved for an appointment. */
    public Map<String, String> variables(Customer customer, Appointment appointment, String serviceName, String staffName) {
        BusinessSettings config = settings.current();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("salon", Util.nz(config.salonName(), "Sillage"));
        vars.put("phone", Util.nz(config.phone(), ""));
        vars.put("customer", customer == null ? Util.nz(appointment == null ? null : appointment.guestName(), "there") : customer.fullName());
        vars.put("service", Util.nz(serviceName, "your service"));
        vars.put("staff", Util.nz(staffName, "your stylist"));
        if (appointment != null && appointment.start() != null) {
            vars.put("date", DAY.format(appointment.start()));
            vars.put("time", TIME.format(appointment.start()));
            vars.put("when", appointment.start().format(DateTimeFormatter.ofPattern("d MMM, hh:mm a")));
            vars.put("code", Util.nz(appointment.code(), ""));
        }
        return vars;
    }

    public String render(String templateKey, Map<String, String> vars) {
        String template = settings.current().template(templateKey);
        if (template == null) template = "{" + templateKey + "}";
        String out = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", Util.nz(entry.getValue(), "\u2014"));
        }
        return out;
    }

    /** Queues a message on one channel. */
    public Notification queue(String templateKey, String channel, String to, Customer customer,
                              Map<String, String> vars, String refKind, String refId, String byName) {
        if (Util.blank(to)) return null;
        BusinessSettings config = settings.current();
        if (!config.notificationsEnabled()) return null;
        if (customer != null && !customer.marketingConsent() && isMarketing(templateKey)) {
            Log.debug("notification suppressed (no marketing consent): %s", templateKey);
            return null;
        }
        String body = render(templateKey, vars);
        String subject = subjectFor(templateKey, config);
        Notification notification = new Notification(Util.id("ntf"), channel, to, subject, body, templateKey,
                refKind, refId, Notification.QUEUED, null, customer == null ? null : customer.id(),
                LocalDateTime.now(), null, Util.nz(byName, "system"), true);
        outbox.put(notification);
        return transmit(notification);
    }

    public Notification queueForAppointment(String templateKey, Appointment appointment, Customer customer,
                                            String serviceName, String staffName, String channel, String byName) {
        if (appointment == null) return null;
        Map<String, String> vars = variables(customer, appointment, serviceName, staffName);
        String to = destination(channel, customer, appointment);
        return queue(templateKey, channel, to, customer, vars, "appointment", appointment.id(), byName);
    }

    public Notification queueForInvoice(String templateKey, Invoice invoice, String channel, String byName,
                                        Map<String, String> extra) {
        if (invoice == null) return null;
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("number", Util.nz(invoice.number()));
        vars.put("amount", com.sillage.core.Money.inr(invoice.total()));
        vars.put("customer", Util.nz(invoice.customerName(), "there"));
        vars.put("points", String.valueOf(invoice.pointsEarned()));
        vars.put("method", invoice.primaryMethod());
        if (extra != null) vars.putAll(extra);
        String to = Notification.EMAIL.equals(channel) ? invoice.customerEmail() : invoice.customerPhone();
        return queue(templateKey, channel, to, null, vars, "invoice", invoice.id(), byName);
    }

    /** Simulated delivery. A real provider adapter would live here. */
    private Notification transmit(Notification notification) {
        try {
            // Deterministic "provider" behaviour: an obviously invalid destination fails.
            String to = Util.nz(notification.to());
            boolean deliverable = !to.isBlank() && !to.contains("@example.invalid") && to.length() > 3;
            Notification result = deliverable ? notification.sent() : notification.failed("destination rejected by provider");
            outbox.put(result);
            Log.debug("notification %s -> %s (%s)", result.status(), to, notification.channel());
            return result;
        } catch (RuntimeException e) {
            Notification failed = notification.failed(e.getMessage());
            outbox.put(failed);
            return failed;
        }
    }

    private String destination(String channel, Customer customer, Appointment appointment) {
        if (Notification.EMAIL.equals(channel)) {
            if (customer != null && Util.present(customer.email())) return customer.email();
            return appointment == null ? null : appointment.guestEmail();
        }
        if (customer != null && Util.present(customer.phone())) return customer.phone();
        return appointment == null ? null : appointment.guestPhone();
    }

    private boolean isMarketing(String templateKey) {
        return templateKey != null && (templateKey.startsWith("promo") || templateKey.startsWith("winback")
                || templateKey.equals("birthday"));
    }

    private String subjectFor(String templateKey, BusinessSettings config) {
        String salon = Util.nz(config.salonName(), "Sillage");
        return switch (Util.nz(templateKey)) {
            case "booking.confirmed" -> salon + " \u00b7 your chair is confirmed";
            case "booking.reminder" -> salon + " \u00b7 see you tomorrow";
            case "booking.cancelled" -> salon + " \u00b7 appointment released";
            case "invoice.paid" -> salon + " \u00b7 receipt";
            case "invoice.refund" -> salon + " \u00b7 refund issued";
            case "loyalty.tier" -> salon + " \u00b7 you have moved up";
            case "birthday" -> salon + " \u00b7 a birthday chair";
            case "winback" -> salon + " \u00b7 we kept your slot";
            case "feedback" -> salon + " \u00b7 ten seconds, please";
            default -> salon + " \u00b7 " + templateKey;
        };
    }

    /** Reminders for tomorrow's confirmed chairs — called by the scheduler and by the desk. */
    public Map<String, Object> sendDueReminders(List<Appointment> due,
                                                java.util.function.Function<Appointment, Customer> customerLookup,
                                                java.util.function.Function<Appointment, String> serviceLabel,
                                                java.util.function.Function<Appointment, String> staffName) {
        int sent = 0;
        List<String> ids = new ArrayList<>();
        for (Appointment appointment : due) {
            Notification notification = queueForAppointment("booking.reminder", appointment,
                    customerLookup.apply(appointment), serviceLabel.apply(appointment), staffName.apply(appointment),
                    Notification.SMS, "reminder-run");
            if (notification != null && Notification.SENT.equals(notification.status())) {
                sent++;
                ids.add(appointment.id());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("due", due.size());
        out.put("sent", sent);
        out.put("appointmentIds", ids);
        out.put("at", LocalDateTime.now().toString());
        return out;
    }

    public List<Notification> recent(int limit) {
        List<Notification> all = outbox.all();
        all.sort(Comparator.comparing(Notification::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Integer> byChannel = new LinkedHashMap<>();
        Map<String, Integer> byTemplate = new LinkedHashMap<>();
        BigDecimal ignored = BigDecimal.ZERO;
        for (Notification n : outbox.all()) {
            byStatus.merge(Util.nz(n.status()), 1, Integer::sum);
            byChannel.merge(Util.nz(n.channel()), 1, Integer::sum);
            byTemplate.merge(Util.nz(n.template()), 1, Integer::sum);
        }
        out.put("total", outbox.size());
        out.put("byStatus", byStatus);
        out.put("byChannel", byChannel);
        out.put("byTemplate", byTemplate);
        out.put("transport", "dry-run outbox (no provider configured)");
        return out;
    }

    public Notification resend(String id) {
        Notification notification = outbox.get(id);
        if (notification == null) throw com.sillage.core.ApiException.notFound("Notification");
        return transmit(new Notification(notification.id(), notification.channel(), notification.to(),
                notification.subject(), notification.body(), notification.template(), notification.refKind(),
                notification.refId(), Notification.QUEUED, null, notification.customerId(),
                notification.createdAt(), null, notification.createdBy(), notification.dryRun()));
    }

    public int purgeSent(int keep) {
        List<Notification> all = outbox.all();
        all.sort(Comparator.comparing(Notification::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));
        int removed = 0;
        for (int i = keep; i < all.size(); i++) {
            outbox.remove(all.get(i).id());
            removed++;
        }
        return removed;
    }

    /** Money helper kept here so the invoice templates can show a paid figure. */
    public static String paidOf(Payment payment) {
        return com.sillage.core.Money.inr(payment == null ? BigDecimal.ZERO : payment.amount());
    }
}
