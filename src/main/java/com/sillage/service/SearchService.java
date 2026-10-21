package com.sillage.service;

import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.Appointment;
import com.sillage.model.Customer;
import com.sillage.model.GiftCard;
import com.sillage.model.Invoice;
import com.sillage.model.Look;
import com.sillage.model.Product;
import com.sillage.model.ServiceItem;
import com.sillage.model.Staff;
import com.sillage.search.SearchDocument;
import com.sillage.search.SearchIndex;
import com.sillage.search.SearchQuery;
import com.sillage.search.SearchResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects every collection into {@link SearchDocument}s and owns the inverted index.
 *
 * <p>One place decides what is searchable and with which weight, so the console's single search
 * box can jump across customers, services, invoices, products, staff and bookings — and so an
 * invoice number, a phone fragment or a misspelt service name all land somewhere sensible.
 */
public final class SearchService {

    public static final String KIND_CUSTOMER = "customer";
    public static final String KIND_SERVICE = "service";
    public static final String KIND_STAFF = "staff";
    public static final String KIND_INVOICE = "invoice";
    public static final String KIND_PRODUCT = "product";
    public static final String KIND_APPOINTMENT = "appointment";
    public static final String KIND_LOOK = "look";
    public static final String KIND_GIFT_CARD = "giftcard";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final SearchIndex index;
    private final Repo<Customer> customers;
    private final Repo<ServiceItem> services;
    private final Repo<Staff> staff;
    private final Repo<Invoice> invoices;
    private final Repo<Product> products;
    private final Repo<Appointment> appointments;
    private final Repo<Look> looks;
    private final Repo<GiftCard> giftCards;

    public SearchService(SearchIndex index,
                         Repo<Customer> customers,
                         Repo<ServiceItem> services,
                         Repo<Staff> staff,
                         Repo<Invoice> invoices,
                         Repo<Product> products,
                         Repo<Appointment> appointments,
                         Repo<Look> looks,
                         Repo<GiftCard> giftCards) {
        this.index = index;
        this.customers = customers;
        this.services = services;
        this.staff = staff;
        this.invoices = invoices;
        this.products = products;
        this.appointments = appointments;
        this.looks = looks;
        this.giftCards = giftCards;
    }

    public SearchIndex index() { return index; }

    // ------------------------------------------------------------------ projections

    public SearchDocument document(Customer c) {
        return SearchDocument.builder(c.id(), KIND_CUSTOMER)
                .title(c.fullName())
                .subtitle(Util.nz(c.phone()) + (Util.present(c.email()) ? " \u00b7 " + c.email() : ""))
                .href("#/customers/" + c.id())
                .field("name", c.fullName())
                .field("code", c.code())
                .field("phone", digits(c.phone()))
                .field("email", c.email())
                .field("tags", Util.join(Util.nullSafe(c.tags()), " "))
                .field("notes", c.notes())
                .field("category", Util.nz(c.city()))
                .attr("tier", c.tier())
                .attr("status", c.archived() ? "archived" : "active")
                .attr("city", c.city())
                .attr("source", c.source())
                .attr("visits", c.visits())
                .attr("lifetimeValue", c.lifetimeValue() == null ? "0" : c.lifetimeValue().toPlainString())
                .attr("points", c.points())
                .attr("staff", c.preferredStaffId())
                .attr("updated", stamp(c.updatedAt() == null ? c.createdAt() : c.updatedAt()))
                .boost(c.archived() ? 0.55 : (c.visits() >= 12 ? 1.15 : 1.0))
                .build();
    }

    public SearchDocument document(ServiceItem s) {
        return SearchDocument.builder(s.id(), KIND_SERVICE)
                .title(s.name())
                .subtitle(Money.inr(s.price()) + " \u00b7 " + Util.humanDuration(s.durationMinutes()))
                .href("#/catalogue/" + s.id())
                .field("name", s.name())
                .field("code", s.code())
                .field("description", Util.join(List.of(Util.nz(s.tagline()), Util.nz(s.description()), Util.nz(s.editorial())), " "))
                .field("tags", Util.join(Util.nullSafe(s.tags()), " "))
                .field("category", s.categoryId())
                .attr("category", s.categoryId())
                .attr("status", s.active() ? "active" : "retired")
                .attr("price", s.price() == null ? "0" : s.price().toPlainString())
                .attr("duration", s.durationMinutes())
                .attr("featured", s.featured())
                .attr("updated", Util.nz(s.updatedAt(), s.createdAt()))
                .boost(s.featured() ? 1.2 : 1.0)
                .build();
    }

    public SearchDocument document(Staff s) {
        return SearchDocument.builder(s.id(), KIND_STAFF)
                .title(s.name())
                .subtitle(Util.nz(s.title()))
                .href("#/team/" + s.id())
                .field("name", s.name())
                .field("title", s.title())
                .field("subtitle", s.bio())
                .field("tags", Util.join(Util.nullSafe(s.specialties()), " "))
                .field("email", s.email())
                .field("phone", digits(s.phone()))
                .attr("role", s.title())
                .attr("status", s.active() ? "active" : "inactive")
                .attr("rating", s.rating())
                .attr("updated", Util.nz(s.updatedAt(), s.createdAt()))
                .boost(s.active() ? 1.0 : 0.6)
                .build();
    }

    public SearchDocument document(Invoice i) {
        StringBuilder items = new StringBuilder();
        for (Invoice.Line line : Util.nullSafe(i.lines())) {
            items.append(Util.nz(line.name())).append(' ').append(Util.nz(line.detail())).append(' ');
        }
        return SearchDocument.builder(i.id(), KIND_INVOICE)
                .title(Util.nz(i.number()))
                .subtitle(Util.nz(i.customerName()) + " \u00b7 " + Money.inr(i.total()))
                .href("#/invoices/" + i.id())
                .field("code", i.number())
                .field("name", i.customerName())
                .field("phone", digits(i.customerPhone()))
                .field("email", i.customerEmail())
                .field("items", items.toString())
                .field("notes", i.notes())
                .attr("status", i.status() == null ? "" : i.status().name())
                .attr("total", i.total() == null ? "0" : i.total().toPlainString())
                .attr("payment", i.primaryMethod())
                .attr("source", i.source())
                .attr("updated", stamp(i.createdAt()))
                .boost(i.status() != null && i.status().isOpen() ? 1.1 : 1.0)
                .build();
    }

    public SearchDocument document(Product p) {
        return SearchDocument.builder(p.id(), KIND_PRODUCT)
                .title(p.name())
                .subtitle(Util.nz(p.brand()) + " \u00b7 " + Money.inr(p.price()))
                .href("#/inventory/" + p.id())
                .field("name", p.name())
                .field("code", p.sku())
                .field("brand", p.brand())
                .field("category", p.category())
                .field("description", p.description())
                .field("tags", Util.join(Util.nullSafe(p.tags()), " "))
                .attr("brand", p.brand())
                .attr("category", p.category())
                .attr("status", p.isOut() ? "out" : (p.isLow() ? "low" : "in-stock"))
                .attr("stock", p.stock())
                .attr("price", p.price() == null ? "0" : p.price().toPlainString())
                .attr("updated", stamp(p.updatedAt()))
                .boost(p.isLow() ? 1.05 : 1.0)
                .build();
    }

    public SearchDocument document(Appointment a, String customerName, String staffName, String serviceNames) {
        return SearchDocument.builder(a.id(), KIND_APPOINTMENT)
                .title(Util.nz(a.code()) + " \u00b7 " + Util.nz(customerName, a.guestName()))
                .subtitle(Util.format(a.start()) + " \u00b7 " + Util.nz(staffName))
                .href("#/calendar?focus=" + a.id())
                .field("code", a.code())
                .field("name", Util.nz(customerName, a.guestName()))
                .field("phone", digits(Util.nz(a.guestPhone())))
                .field("email", a.guestEmail())
                .field("items", serviceNames)
                .field("notes", Util.join(List.of(Util.nz(a.notes()), Util.nz(a.internalNotes())), " "))
                .field("staff", staffName)
                .attr("status", a.status() == null ? "" : a.status().name())
                .attr("staff", a.staffId())
                .attr("source", a.source())
                .attr("date", a.start() == null ? "" : a.start().toLocalDate().toString())
                .attr("updated", stamp(a.updatedAt() == null ? a.createdAt() : a.updatedAt()))
                .boost(1.0)
                .build();
    }

    public SearchDocument document(Look l) {
        return SearchDocument.builder(l.id(), KIND_LOOK)
                .title(l.title())
                .subtitle(Util.nz(l.season()))
                .href("#/lookbook/" + l.id())
                .field("title", l.title())
                .field("name", l.title())
                .field("description", Util.join(List.of(Util.nz(l.story()), Util.nz(l.notes())), " "))
                .field("tags", Util.join(Util.nullSafe(l.palette()), " ") + " " + Util.nz(l.season()))
                .attr("category", l.season())
                .attr("status", l.active() ? "active" : "archived")
                .attr("featured", l.featured())
                .attr("updated", Util.nz(l.createdAt()))
                .boost(l.featured() ? 1.2 : 1.0)
                .build();
    }

    public SearchDocument document(GiftCard g) {
        return SearchDocument.builder(g.id(), KIND_GIFT_CARD)
                .title(Util.nz(g.code()))
                .subtitle(Util.nz(g.recipientName(), g.purchaserName()) + " \u00b7 " + Money.inr(g.balance()))
                .href("#/giftcards/" + g.id())
                .field("code", g.code())
                .field("name", Util.join(List.of(Util.nz(g.purchaserName()), Util.nz(g.recipientName())), " "))
                .field("phone", digits(g.purchaserPhone()))
                .field("notes", g.message())
                .attr("status", Util.nz(g.status()))
                .attr("total", g.amount() == null ? "0" : g.amount().toPlainString())
                .attr("updated", stamp(g.createdAt()))
                .boost(1.0)
                .build();
    }

    // ------------------------------------------------------------------ index maintenance

    public void index(Customer c) { if (c != null) index.add(document(c)); }

    public void index(ServiceItem s) { if (s != null) index.add(document(s)); }

    public void index(Staff s) { if (s != null) index.add(document(s)); }

    public void index(Invoice i) { if (i != null) index.add(document(i)); }

    public void index(Product p) { if (p != null) index.add(document(p)); }

    public void index(Look l) { if (l != null) index.add(document(l)); }

    public void index(GiftCard g) { if (g != null) index.add(document(g)); }

    public void index(Appointment a) {
        if (a == null) return;
        Customer customer = a.customerId() == null ? null : customers.get(a.customerId());
        Staff member = staff.get(a.staffId());
        StringBuilder names = new StringBuilder();
        for (Appointment.Item item : Util.nullSafe(a.items())) {
            ServiceItem service = services.get(item.serviceId());
            names.append(Util.nz(item.name(), service == null ? "" : service.name())).append(' ');
        }
        index.add(document(a, customer == null ? a.guestName() : customer.fullName(),
                member == null ? null : member.name(), names.toString().trim()));
    }

    public void remove(String kind, String id) { index.remove(kind, id); }

    /** Rebuilds everything; returns the number of documents indexed and the elapsed millis. */
    public Map<String, Object> rebuild() {
        long started = System.nanoTime();
        List<SearchDocument> documents = new ArrayList<>();
        for (Customer c : customers.all()) documents.add(document(c));
        for (ServiceItem s : services.all()) documents.add(document(s));
        for (Staff s : staff.all()) documents.add(document(s));
        for (Invoice i : invoices.all()) documents.add(document(i));
        for (Product p : products.all()) documents.add(document(p));
        for (Look l : looks.all()) documents.add(document(l));
        for (GiftCard g : giftCards.all()) documents.add(document(g));
        for (Appointment a : appointments.all()) {
            Customer customer = a.customerId() == null ? null : customers.get(a.customerId());
            Staff member = staff.get(a.staffId());
            StringBuilder names = new StringBuilder();
            for (Appointment.Item item : Util.nullSafe(a.items())) {
                ServiceItem service = services.get(item.serviceId());
                names.append(Util.nz(item.name(), service == null ? "" : service.name())).append(' ');
            }
            documents.add(document(a, customer == null ? a.guestName() : customer.fullName(),
                    member == null ? null : member.name(), names.toString().trim()));
        }
        index.rebuild(documents);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documents", documents.size());
        out.put("tookMillis", (System.nanoTime() - started) / 1_000_000);
        out.put("at", LocalDateTime.now().toString());
        return out;
    }

    // ------------------------------------------------------------------ queries

    public SearchResult search(SearchQuery query) {
        return index.search(query);
    }

    public List<Map<String, Object>> autocomplete(String prefix, int limit) {
        return index.autocomplete(prefix, limit);
    }

    public Map<String, Object> stats() {
        SearchIndex.IndexStats stats = index.stats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documents", stats.documents());
        out.put("vocabulary", stats.tokens());
        out.put("postings", stats.postings());
        out.put("trigrams", stats.trigrams());
        out.put("avgDocLength", stats.avgDocLength());
        out.put("estimatedKilobytes", Math.round(stats.estimatedBytes() / 1024.0));
        out.put("buildMillis", stats.buildMillis());
        out.put("byKind", stats.byKind());
        out.put("fieldWeights", stats.fieldWeights());
        out.put("ranking", "BM25 (k1=1.35, b=0.72) with per-field weights");
        out.put("capabilities", List.of("exact", "prefix", "typo-tolerant (edit distance \u2264 2)",
                "substring on phone/code/email", "attribute filters", "ranges", "facets", "sorting", "paging"));
        return out;
    }

    private static String digits(String value) {
        return value == null ? null : value.replaceAll("[^0-9A-Za-z]", "");
    }

    private static String stamp(LocalDateTime value) {
        return value == null ? "" : STAMP.format(value);
    }
}
