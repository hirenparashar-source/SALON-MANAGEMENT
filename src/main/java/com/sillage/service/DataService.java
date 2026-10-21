package com.sillage.service;

import com.sillage.core.ApiException;
import com.sillage.core.JsonMapper;
import com.sillage.core.Log;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.data.DataStore;
import com.sillage.data.Repo;
import com.sillage.model.Actor;
import com.sillage.model.Appointment;
import com.sillage.model.Customer;
import com.sillage.model.Expense;
import com.sillage.model.GiftCard;
import com.sillage.model.Invoice;
import com.sillage.model.Notification;
import com.sillage.model.Product;
import com.sillage.model.Role;
import com.sillage.model.ServiceItem;
import com.sillage.model.Staff;
import com.sillage.model.StockMovement;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The housekeeping service: CSV exports, snapshots, restores, referential-integrity checks and the
 * GDPR erasure orchestration. Everything here writes a line to the audit log.
 */
public final class DataService {

    private final DataStore store;
    private final Repo<Customer> customers;
    private final Repo<Appointment> appointments;
    private final Repo<Invoice> invoices;
    private final Repo<Product> products;
    private final Repo<Staff> staff;
    private final Repo<ServiceItem> services;
    private final Repo<StockMovement> movements;
    private final Repo<GiftCard> giftCards;
    private final Repo<Notification> notifications;
    private final Repo<Expense> expenses;
    private final CustomerService customerService;
    private final InventoryService inventory;
    private final AuditService audit;
    private final SearchService search;
    private final PaymentGateway gateway;
    private final NotificationService notificationService;
    private final AuthService auth;

    public DataService(DataStore store, Repo<Customer> customers, Repo<Appointment> appointments,
                       Repo<Invoice> invoices, Repo<Product> products, Repo<Staff> staff,
                       Repo<ServiceItem> services, Repo<StockMovement> movements, Repo<GiftCard> giftCards,
                       Repo<Notification> notifications, Repo<Expense> expenses, CustomerService customerService,
                       InventoryService inventory, AuditService audit, SearchService search, PaymentGateway gateway,
                       NotificationService notificationService, AuthService auth) {
        this.store = store;
        this.customers = customers;
        this.appointments = appointments;
        this.invoices = invoices;
        this.products = products;
        this.staff = staff;
        this.services = services;
        this.movements = movements;
        this.giftCards = giftCards;
        this.notifications = notifications;
        this.expenses = expenses;
        this.customerService = customerService;
        this.inventory = inventory;
        this.audit = audit;
        this.search = search;
        this.gateway = gateway;
        this.notificationService = notificationService;
        this.auth = auth;
    }

    public static final List<String> CSV_HEADER_CUSTOMERS = List.of("code", "name", "first_name", "last_name",
            "phone", "email", "birthday", "city", "tier", "points", "lifetime_value", "visits", "first_visit",
            "last_visit", "preferred_staff", "average_rating", "no_shows", "cancellations", "tags", "notes",
            "allergies", "marketing_consent", "data_consent", "archived", "created_at");

    // ------------------------------------------------------------------ exports

    /** The catalogue of exports, with live row counts. */
    public List<Map<String, Object>> exports() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(exportMeta("customers", "Client database", customers.size(), CSV_HEADER_CUSTOMERS.size(),
                "Every field except the internal id, with consent flags"));
        out.add(exportMeta("appointments", "Diary", appointments.size(), 16, "All bookings with status and price"));
        out.add(exportMeta("invoices", "Invoices", invoices.size(), 19, "GST-ready billing lines summarised per invoice"));
        out.add(exportMeta("products", "Inventory", products.size(), 14, "Stock, cost, retail and reorder points"));
        out.add(exportMeta("services", "Menu", services.size(), 11, "The published catalogue"));
        out.add(exportMeta("staff", "Stylists", staff.size(), 10, "Rota summary and commissions"));
        out.add(exportMeta("movements", "Stock ledger", movements.size(), 9, "Every stock change, immutable"));
        out.add(exportMeta("giftcards", "Gift cards", giftCards.size(), 8, "Issued value, balance and redemptions"));
        out.add(exportMeta("expenses", "Expenses", expenses.size(), 7, "Costs by category"));
        out.add(exportMeta("notifications", "Outbox", notifications.size(), 8, "Queued and dry-run messages"));
        out.add(exportMeta("audit", "Audit trail", audit.count(), 9, "Who did what, when, from where"));
        return out;
    }

    private static Map<String, Object> exportMeta(String key, String label, int rows, int columns, String note) {
        return Util.map("key", key, "label", label, "rows", rows, "columns", columns, "note", note,
                "url", "/api/export/" + key + ".csv");
    }

    public Map<String, Object> export(String kind, Actor actor) {
        actor.requireStaff();
        String csv;
        switch (Util.nz(kind)) {
            case "customers" -> csv = customerService.toCsv(customers.all());
            case "appointments" -> csv = appointmentsCsv();
            case "invoices" -> csv = invoiceCsv();
            case "products" -> csv = inventory.toCsv(products.all());
            case "services" -> csv = servicesCsv();
            case "staff" -> csv = staffCsv();
            case "movements" -> csv = movementsCsv();
            case "giftcards" -> csv = giftCardsCsv();
            case "expenses" -> csv = expensesCsv();
            case "notifications" -> csv = notificationsCsv();
            case "audit" -> csv = audit.csv(actor.has(Role.MANAGER) ? 5000 : 500);
            default -> throw ApiException.notFound("Export " + Util.nz(kind));
        }
        audit.record("data.export", "export", kind, "Exported " + kind + " to CSV ("
                + (csv.length() / 1024) + " KB)", actor);
        return Util.map("kind", kind, "csv", csv, "bytes", csv.length(),
                "filename", "sillage-" + kind + "-" + LocalDate.now() + ".csv",
                "contentType", "text/csv; charset=utf-8", "rows", csv.split("\n").length - 1);
    }

    private String appointmentsCsv() {
        List<List<String>> rows = new ArrayList<>();
        for (Appointment a : appointments.all()) {
            Customer customer = a.customerId() == null ? null : customers.get(a.customerId());
            rows.add(List.of(Util.nz(a.code()), a.start() == null ? "" : a.start().toString(),
                    a.end() == null ? "" : a.end().toString(), Util.nz(a.status() == null ? "" : a.status().name()),
                    customer == null ? "" : customer.fullName(), Util.nz(a.guestName()), Util.nz(a.guestPhone()),
                    Util.nz(a.staffId()), String.valueOf(a.durationMinutes()),
                    plain(a.price()), plain(a.deposit()),
                    Util.join(a.serviceNames(), " | "), Util.nz(a.source()),
                    a.rating() == null ? "" : String.valueOf(a.rating()),
                    Util.nz(a.invoiceId()), Util.nz(a.notes())));
        }
        return Util.toCsv(List.of("code", "start", "end", "status", "customer", "guest_name", "guest_phone",
                "staff_id", "minutes", "price", "deposit", "services", "source", "rating", "invoice_id", "notes"), rows);
    }

    private String invoiceCsv() {
        List<Invoice> all = invoices.all();
        all.sort((a, b) -> Util.nz(b.number()).compareTo(Util.nz(a.number())));
        return invoiceCsvOf(all);
    }

    private String invoiceCsvOf(List<Invoice> all) {
        List<List<String>> rows = new ArrayList<>();
        for (Invoice i : all) {
            rows.add(List.of(Util.nz(i.number()), i.createdAt() == null ? "" : i.createdAt().toString(),
                    Util.nz(i.customerName()), Util.nz(i.customerPhone()), Util.nz(i.customerEmail()),
                    plain(i.subtotal()), plain(i.discount()), plain(i.taxTotal()), plain(i.cgst()), plain(i.sgst()),
                    plain(i.gratuity()), plain(i.total()), plain(i.paid()), plain(i.balance()),
                    Util.nz(i.status() == null ? "" : i.status().name()), Util.nz(i.primaryMethod()),
                    String.valueOf(i.pointsEarned()), Util.nz(i.source()), Util.nz(i.createdBy())));
        }
        return Util.toCsv(List.of("number", "created_at", "customer", "phone", "email", "subtotal", "discount",
                "tax_total", "cgst", "sgst", "gratuity", "total", "paid", "balance", "status", "method",
                "points_earned", "source", "billed_by"), rows);
    }

    public String invoiceCsvBetween(LocalDate from, LocalDate to) {
        return invoiceCsvOf(invoices.where(i -> i.createdAt() != null && !i.createdAt().toLocalDate().isBefore(from)
                && !i.createdAt().toLocalDate().isAfter(to)));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    private String servicesCsv() {
        List<List<String>> rows = new ArrayList<>();
        for (ServiceItem s : services.all()) {
            rows.add(List.of(Util.nz(s.code()), Util.nz(s.name()), Util.nz(s.categoryId()),
                    plain(s.price()), String.valueOf(s.durationMinutes()), String.valueOf(s.active()),
                    String.valueOf(s.onlineBookable()), String.valueOf(s.timesBooked()),
                    String.valueOf(s.rating()),
                    Util.join(Util.nullSafe(s.tags()), " | "), Util.nz(s.tagline())));
        }
        return Util.toCsv(List.of("code", "name", "category", "price", "minutes", "active", "online_bookable",
                "times_booked", "rating", "tags", "tagline"), rows);
    }

    private String staffCsv() {
        List<List<String>> rows = new ArrayList<>();
        for (Staff m : staff.all()) {
            List<String> names = new ArrayList<>();
            for (String serviceId : Util.nullSafe(m.serviceIds())) {
                ServiceItem service = services.get(serviceId);
                names.add(service == null ? serviceId : service.name());
            }
            rows.add(List.of(Util.nz(m.id()), Util.nz(m.name()), Util.nz(m.title()), Util.nz(m.phone()),
                    Util.nz(m.email()), String.valueOf(m.active()), String.valueOf(m.commissionPct()),
                    String.valueOf(m.rating()), String.valueOf(Util.nullSafe(m.specialties()).size()),
                    Util.join(names, " | ")));
        }
        return Util.toCsv(List.of("code", "name", "title", "phone", "email", "active", "commission_pct",
                "rating", "specialities", "services"), rows);
    }

    private String movementsCsv() {
        List<List<String>> rows = new ArrayList<>();
        for (StockMovement m : movements.all()) {
            rows.add(List.of(Util.nz(m.id()), m.at() == null ? "" : m.at().toString(), Util.nz(m.productId()),
                    Util.nz(m.reason()), String.valueOf(m.delta()), String.valueOf(m.balanceAfter() - m.delta()),
                    String.valueOf(m.balanceAfter()), Util.nz(m.refId()), Util.nz(m.byName())));
        }
        return Util.toCsv(List.of("id", "at", "product_id", "reason", "delta", "balance_before", "balance_after",
                "reference", "by"), rows);
    }

    private String giftCardsCsv() {
        List<List<String>> rows = new ArrayList<>();
        for (GiftCard g : giftCards.all()) {
            rows.add(List.of(Util.nz(g.code()), Util.nz(g.purchaserName()), Util.nz(g.recipientName()),
                    plain(g.amount()), plain(g.balance()), Util.nz(g.status()),
                    g.expiresOn() == null ? "" : g.expiresOn().toString(), String.valueOf(g.redemptions())));
        }
        return Util.toCsv(List.of("code", "purchaser", "recipient", "amount", "balance", "status", "expires_on",
                "redemptions"), rows);
    }

    private String expensesCsv() {
        List<List<String>> rows = new ArrayList<>();
        for (Expense e : expenses.all()) {
            rows.add(List.of(e.date() == null ? "" : e.date().toString(), Util.nz(e.category()),
                    Util.nz(e.note()), plain(e.amount()), Util.nz(e.paymentMethod()),
                    Util.nz(e.vendor()), Util.nz(e.createdBy())));
        }
        return Util.toCsv(List.of("date", "category", "note", "amount", "method", "vendor", "created_by"), rows);
    }

    private String notificationsCsv() {
        List<List<String>> rows = new ArrayList<>();
        for (Notification n : notifications.all()) {
            rows.add(List.of(Util.nz(n.id()), n.createdAt() == null ? "" : n.createdAt().toString(),
                    Util.nz(n.channel()), Util.nz(n.template()), Util.maskEmail(n.to()),
                    Util.nz(n.status()), Util.nz(n.subject()), Util.nz(n.refId())));
        }
        return Util.toCsv(List.of("id", "queued_at", "channel", "template", "destination", "status", "subject",
                "entity_id"), rows);
    }

    // ------------------------------------------------------------------ snapshots

    public Map<String, Object> backup(String label, Actor actor) {
        actor.require(Role.MANAGER);
        store.flushAll();
        try {
            Path snapshot = store.snapshot(Util.blank(label) ? "manual" : label);
            Map<String, Object> out = Util.map("snapshot", snapshot.getFileName().toString(),
                    "path", snapshot.toString(), "label", Util.nz(label, "manual"),
                    "at", java.time.LocalDateTime.now().toString(),
                    "collections", store.stats(), "bytes", sizeOf(snapshot));
            audit.security("data.backup", "Snapshot written to " + snapshot.getFileName(), actor.ip(),
                    Map.of("snapshot", snapshot.getFileName().toString()));
            Log.info("snapshot written: %s", snapshot);
            return out;
        } catch (IOException e) {
            Log.error("snapshot failed", e);
            throw new ApiException(500, "BACKUP_FAILED", "The snapshot could not be written: " + e.getMessage(), Map.of());
        }
    }

    private static long sizeOf(Path path) {
        try {
            return java.nio.file.Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    public List<Map<String, Object>> backups() {
        return store.snapshots();
    }

    public Map<String, Object> restore(String name, Actor actor) {
        actor.require(Role.OWNER);
        Path snapshot = store.backupDirectory().resolve(name);
        if (!java.nio.file.Files.exists(snapshot)) throw ApiException.notFound("Snapshot " + Util.nz(name));
        try {
            Map<String, Integer> counts = store.restore(name);
            search.rebuild();
            audit.security("data.restore", "Restored from " + name + " (" + counts.size() + " collections)",
                    actor.ip(), new LinkedHashMap<>(counts));
            Log.warn("restored snapshot %s \u2014 %s", name, counts);
            return Util.map("restored", name, "collections", counts,
                    "message", "Restored " + name + ". The search index has been rebuilt.");
        } catch (IOException e) {
            Log.error("restore failed", e);
            throw new ApiException(500, "RESTORE_FAILED", "The snapshot could not be restored: " + e.getMessage(), Map.of());
        }
    }

    public Map<String, Object> storage() {
        Map<String, Object> out = new LinkedHashMap<>(store.stats());
        out.put("directory", store.directory().toString());
        out.put("backupDirectory", store.backupDirectory().toString());
        out.put("diskBytes", store.diskUsageBytes());
        out.put("diskHuman", humanBytes(store.diskUsageBytes()));
        out.put("openedAt", store.openedAt() == null ? null : store.openedAt().toString());
        out.put("snapshots", store.snapshots().size());
        out.put("searchIndex", search.stats());
        return out;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return Math.round(value * 10) / 10.0 + " " + units[Math.max(0, unit)];
    }

    /** Orphan and inconsistency checks \u2014 the kind of thing a paper diary hides. */
    public Map<String, Object> integrity() {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Appointment appointment : appointments.all()) {
            if (Util.present(appointment.customerId()) && customers.get(appointment.customerId()) == null) {
                issues.add(issue("appointment", appointment.id(), appointment.code(), "points at a missing client"));
            }
            if (Util.present(appointment.staffId()) && staff.get(appointment.staffId()) == null) {
                issues.add(issue("appointment", appointment.id(), appointment.code(), "points at a missing stylist"));
            }
            for (Appointment.Item item : Util.nullSafe(appointment.items())) {
                if (Util.present(item.serviceId()) && services.get(item.serviceId()) == null) {
                    issues.add(issue("appointment", appointment.id(), appointment.code(),
                            "lists a service that is no longer on the menu (" + item.name() + ")"));
                }
            }
        }
        for (Invoice invoice : invoices.all()) {
            if (Util.present(invoice.customerId()) && customers.get(invoice.customerId()) == null) {
                issues.add(issue("invoice", invoice.id(), invoice.number(), "points at a missing client"));
            }
            // subtotal counts only real charges; discount, loyalty and gratuity lines are display-only
            BigDecimal taxable = invoice.lines().stream()
                    .filter(l -> Invoice.Line.SERVICE.equals(l.kind()) || Invoice.Line.PRODUCT.equals(l.kind())
                            || Invoice.Line.ADDON.equals(l.kind()))
                    .map(l -> Money.nz(l.amount()))
                    .reduce(BigDecimal.ZERO, Money::add);
            BigDecimal expected = Money.add(Money.subtract(taxable, Money.nz(invoice.discount())),
                    Money.subtract(Money.add(Money.nz(invoice.taxTotal()), Money.nz(invoice.gratuity())),
                            Money.nz(invoice.loyaltyRedemption())));
            expected = Money.add(expected, Money.nz(invoice.roundOff()));
            if (Money.compare(expected, Money.nz(invoice.total())) != 0) {
                issues.add(issue("invoice", invoice.id(), invoice.number(),
                        "lines and tax total " + Money.inr(expected) + " but the invoice says "
                                + Money.inr(invoice.total())));
            }
            for (String appointmentId : Util.nullSafe(invoice.appointmentIds())) {
                if (appointments.get(appointmentId) == null) {
                    issues.add(issue("invoice", invoice.id(), invoice.number(), "references a missing booking"));
                }
            }
        }
        for (Product product : products.all()) {
            if (product.stock() < 0) issues.add(issue("product", product.id(), product.sku(), "has negative stock"));
            for (String serviceId : Util.nullSafe(product.linkedServiceIds())) {
                if (services.get(serviceId) == null) {
                    issues.add(issue("product", product.id(), product.sku(), "is linked to a removed service"));
                }
            }
        }
        for (Staff member : staff.all()) {
            for (String serviceId : Util.nullSafe(member.serviceIds())) {
                if (services.get(serviceId) == null) {
                    issues.add(issue("staff", member.id(), member.name(), "is certified for a removed service"));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", Util.map("appointments", appointments.size(), "invoices", invoices.size(),
                "products", products.size(), "staff", staff.size(), "customers", customers.size()));
        out.put("issues", issues);
        out.put("clean", issues.isEmpty());
        out.put("checkedAt", java.time.LocalDateTime.now().toString());
        return out;
    }

    private static Map<String, Object> issue(String kind, String id, String label, String message) {
        return Util.map("kind", kind, "id", id, "label", Util.nz(label), "message", message);
    }

    // ------------------------------------------------------------------ erasure & purge

    public Map<String, Object> erasure(String customerId, Actor actor) {
        Customer customer = customers.get(customerId);
        if (customer == null) throw ApiException.notFound("Customer " + Util.nz(customerId));
        actor.requireSelfOrStaff(customerId);
        Map<String, Object> subject = customerService.exportSubject(customerId, actor);
        Customer erased = customerService.erase(customerId, actor);
        subject.put("erasedAt", java.time.LocalDateTime.now().toString());
        subject.put("erasureReceipt", Util.sha256Hex(customerId + "|" + System.nanoTime()).substring(0, 16));
        audit.security("data.erasure", "Right-to-be-forgotten executed for " + Util.nz(customer.code()), actor.ip(),
                Map.of("customerId", customerId, "erased", erased.archived()));
        return subject;
    }

    /** Prunes short-lived records; safe to run at any time. */
    public Map<String, Object> purge(Actor actor) {
        actor.require(Role.MANAGER);
        int intents = gateway.pruneIntents();
        int sent = notificationService.purgeSent(1000);
        int sessions = auth.pruneSessions();
        int trimmed = audit.trim();
        store.flushAll();
        Map<String, Object> out = Util.map("paymentIntents", intents, "sentNotifications", sent,
                "expiredSessions", sessions, "auditEvents", trimmed);
        audit.record("data.purge", "system", "housekeeping", "Purged " + out, actor);
        return out;
    }

    public Map<String, Object> resetAndSeed(Actor actor, boolean seed) {
        actor.require(Role.OWNER);
        store.wipe();
        audit.security("data.reset", "All collections wiped" + (seed ? " and reseeded" : ""), actor.ip(), Map.of());
        return Util.map("reset", true, "seeded", seed,
                "message", "Collections cleared. Restart with --no-seed to keep them empty.");
    }

    public DataStore store() { return store; }

    public Path dataDirectory() { return store.directory(); }
}
