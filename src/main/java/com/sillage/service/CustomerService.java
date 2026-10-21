package com.sillage.service;

import com.sillage.core.ApiException;
import com.sillage.core.JsonMapper;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.Actor;
import com.sillage.model.Appointment;
import com.sillage.model.AppointmentStatus;
import com.sillage.model.AuditEvent;
import com.sillage.model.Customer;
import com.sillage.model.Invoice;
import com.sillage.model.InvoiceStatus;
import com.sillage.model.Notification;
import com.sillage.model.Role;
import com.sillage.model.Staff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The customer database: identity, consent, loyalty position, history and the data-subject rights
 * (export / erasure) that go with keeping one.
 */
public final class CustomerService {

    private final Repo<Customer> customers;
    private final Repo<Appointment> appointments;
    private final Repo<Invoice> invoices;
    private final Repo<Staff> staff;
    private final Repo<Notification> notifications;
    private final SearchService search;
    private final AuditService audit;

    public CustomerService(Repo<Customer> customers, Repo<Appointment> appointments, Repo<Invoice> invoices,
                           Repo<Staff> staff, Repo<Notification> notifications, SearchService search, AuditService audit) {
        this.customers = customers;
        this.appointments = appointments;
        this.invoices = invoices;
        this.staff = staff;
        this.notifications = notifications;
        this.search = search;
        this.audit = audit;
    }

    public Repo<Customer> repo() { return customers; }

    // ------------------------------------------------------------------ reads

    public List<Customer> all() {
        List<Customer> out = customers.where(c -> !c.archived());
        out.sort(Comparator.comparing((Customer c) -> Util.nz(c.lastName(), "").toLowerCase(Locale.ROOT))
                .thenComparing(c -> Util.nz(c.firstName(), "").toLowerCase(Locale.ROOT)));
        return out;
    }

    public Customer get(String id) {
        Customer customer = customers.get(id);
        if (customer == null) throw ApiException.notFound("Customer " + Util.nz(id));
        return customer;
    }

    public Customer find(String id) { return customers.get(id); }

    public Customer byPhone(String phone) {
        if (Util.blank(phone)) return null;
        String key = phoneKey(phone);
        if (key == null) return null;
        // The repo index stores the last ten digits, so this is an O(1) lookup for well-formed input.
        for (Customer candidate : customers.byIndex("phone", key)) if (candidate != null) return candidate;
        String tail = key.substring(Math.max(0, key.length() - 10));
        for (Customer candidate : customers.all()) {
            String other = phoneKey(candidate.phone());
            if (other != null && other.endsWith(tail)) return candidate;
        }
        return null;
    }

    /** Index key for a phone number: digits only, last ten (handles +91, 091 and spacing). */
    public static String phoneKey(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 7) return null;
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    private static String normalisePhone(String digits) {
        return phoneKey(digits);
    }

    public Customer byEmail(String email) {
        if (Util.blank(email)) return null;
        return customers.firstByIndex("email", email.trim().toLowerCase(Locale.ROOT)).orElse(null);
    }

    public List<Customer> findOrCreateMatches(String name, String phone, String email) {
        List<Customer> matches = new ArrayList<>();
        Customer byPhone = byPhone(phone);
        if (byPhone != null) matches.add(byPhone);
        Customer byEmail = byEmail(email);
        if (byEmail != null && matches.stream().noneMatch(c -> c.id().equals(byEmail.id()))) matches.add(byEmail);
        if (Util.present(name) && matches.isEmpty()) {
            String needle = Util.normalise(name);
            for (Customer candidate : customers.all()) {
                if (Util.normalise(candidate.fullName()).equals(needle)) { matches.add(candidate); break; }
            }
        }
        return matches;
    }

    /** Full profile: history, money, loyalty and the next-visit prediction inputs. */
    public Map<String, Object> profile(String id, Actor actor) {
        Customer customer = get(id);
        actor.requireSelfOrStaff(customer.id());

        List<Appointment> history = appointments.where(a -> id.equals(a.customerId()));
        history.sort(Comparator.comparing(Appointment::start, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Invoice> bills = invoices.where(i -> id.equals(i.customerId()));
        bills.sort(Comparator.comparing(Invoice::createdAt, Comparator.nullsLast(Comparator.reverseOrder())));

        List<Appointment> upcoming = history.stream()
                .filter(a -> !a.status().isClosed() && a.start() != null && a.start().isAfter(LocalDateTime.now()))
                .toList();
        List<Appointment> past = history.stream().filter(a -> a.status() == AppointmentStatus.COMPLETED).toList();

        Map<String, Integer> serviceCounts = new LinkedHashMap<>();
        Map<String, Integer> staffCounts = new LinkedHashMap<>();
        List<Long> gaps = new ArrayList<>();
        LocalDateTime previous = null;
        List<Appointment> chronological = new ArrayList<>(past);
        chronological.sort(Comparator.comparing(Appointment::start, Comparator.nullsLast(Comparator.naturalOrder())));
        for (Appointment appointment : chronological) {
            for (Appointment.Item item : Util.nullSafe(appointment.items())) {
                serviceCounts.merge(Util.nz(item.name(), item.serviceId()), 1, Integer::sum);
            }
            Staff member = staff.get(appointment.staffId());
            if (member != null) staffCounts.merge(member.name(), 1, Integer::sum);
            if (previous != null && appointment.start() != null) {
                gaps.add(ChronoUnit.DAYS.between(previous, appointment.start()));
            }
            if (appointment.start() != null) previous = appointment.start();
        }

        BigDecimal spent = bills.stream().filter(i -> i.status() != InvoiceStatus.VOID)
                .map(Invoice::total).reduce(BigDecimal.ZERO, Money::add);
        BigDecimal outstanding = bills.stream().filter(i -> i.status() != null && i.status().isOpen())
                .map(Invoice::balance).reduce(BigDecimal.ZERO, Money::add);
        long medianGap = median(gaps);
        int noShows = (int) history.stream().filter(a -> a.status() == AppointmentStatus.NO_SHOW).count();
        int cancellations = (int) history.stream().filter(a -> a.status() == AppointmentStatus.CANCELLED).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customer", customer);
        out.put("display", JsonMapper.toMap(customer));
        out.put("upcoming", upcoming.stream().limit(5).map(this::appointmentSummary).toList());
        out.put("history", history.stream().limit(40).map(this::appointmentSummary).toList());
        out.put("invoices", bills.stream().limit(30).map(i -> Util.map(
                "id", i.id(), "number", i.number(), "total", i.total(), "status", i.status().name(),
                "createdAt", i.createdAt() == null ? null : i.createdAt().toString(),
                "method", i.primaryMethod())).toList());
        out.put("stats", Util.map(
                "visits", history.size(),
                "completed", past.size(),
                "noShows", noShows,
                "cancellations", cancellations,
                "noShowRate", past.isEmpty() ? 0 : Util.percent(noShows, past.size()),
                "lifetimeValue", Money.scale(spent),
                "outstanding", Money.scale(outstanding),
                "averageTicket", past.isEmpty() ? BigDecimal.ZERO : Money.divide(spent, BigDecimal.valueOf(Math.max(1, bills.size()))),
                "medianGapDays", medianGap,
                "daysSinceLastVisit", customer.daysSinceLastVisit(),
                "firstVisit", customer.firstVisitAt(),
                "lastVisit", customer.lastVisitAt()));
        out.put("favourites", topEntries(serviceCounts, 6));
        out.put("stylists", topEntries(staffCounts, 5));
        out.put("loyalty", loyaltyView(customer));
        out.put("consent", Util.map("marketing", customer.marketingConsent(), "data", customer.dataConsent()));
        out.put("notes", customer.notes());
        out.put("allergies", customer.allergies());
        out.put("preferredStaff", customer.preferredStaffId() == null ? null : staffName(customer.preferredStaffId()));
        out.put("birthdaySoon", customer.isBirthdayWithin(30));
        out.put("audit", audit.forEntity("customer", id).stream().limit(15).toList());
        return out;
    }

    private Map<String, Object> loyaltyView(Customer customer) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tier", Util.nz(customer.tier(), "Atelier"));
        out.put("points", customer.points());
        out.put("value", Money.multiply(BigDecimal.valueOf(customer.points()), new BigDecimal("0.5")));
        out.put("lifetimeValue", customer.lifetimeValue());
        return out;
    }

    public Map<String, Object> appointmentSummary(Appointment a) {
        Staff member = staff.get(a.staffId());
        return Util.map(
                "id", a.id(),
                "code", a.code(),
                "start", a.start() == null ? null : a.start().toString(),
                "end", a.end() == null ? null : a.end().toString(),
                "when", Util.format(a.start()),
                "status", a.status() == null ? null : a.status().name(),
                "statusLabel", a.status() == null ? null : a.status().label(),
                "staff", member == null ? null : member.name(),
                "staffId", a.staffId(),
                "services", a.serviceNames(),
                "price", a.price(),
                "invoiceId", a.invoiceId(),
                "rating", a.rating());
    }

    public Map<String, Object> tags() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Customer customer : customers.all()) {
            for (String tag : Util.nullSafe(customer.tags())) counts.merge(tag, 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> out.add(Util.map("tag", e.getKey(), "count", e.getValue())));
        return Util.map("tags", out, "total", counts.size());
    }

    public Map<String, Object> stats() {
        List<Customer> all = customers.all();
        Map<String, Integer> tiers = new LinkedHashMap<>();
        Map<String, Integer> sources = new LinkedHashMap<>();
        int active = 0;
        int marketing = 0;
        BigDecimal lifetime = BigDecimal.ZERO;
        for (Customer customer : all) {
            if (!customer.archived()) active++;
            if (customer.marketingConsent()) marketing++;
            tiers.merge(Util.nz(customer.tier(), "Atelier"), 1, Integer::sum);
            sources.merge(Util.nz(customer.source(), "walk-in"), 1, Integer::sum);
            lifetime = Money.add(lifetime, customer.lifetimeValue());
        }
        return Util.map(
                "total", all.size(),
                "active", active,
                "archived", all.size() - active,
                "marketingConsent", marketing,
                "lifetimeValue", lifetime,
                "byTier", tiers,
                "bySource", sources,
                "newThisMonth", all.stream().filter(c -> c.createdAt() != null
                        && c.createdAt().isAfter(LocalDate.now().withDayOfMonth(1).atStartOfDay())).count());
    }

    // ------------------------------------------------------------------ writes

    public Customer create(Map<String, Object> body, Actor actor) {
        actor.requireStaff();
        return save(null, body, actor, true);
    }

    public Customer update(String id, Map<String, Object> patch, Actor actor) {
        Customer existing = get(id);
        boolean self = actor.customerId() != null && actor.customerId().equals(id);
        if (!actor.isStaff() && !self) throw ApiException.forbidden("You can only edit your own record");
        if (self && !actor.isStaff()) {
            // A client may change contact details and preferences, not money or loyalty fields.
            patch = new LinkedHashMap<>(patch);
            for (String blocked : List.of("tier", "points", "lifetimeValue", "visits", "archived", "code", "userId")) patch.remove(blocked);
        }
        return save(existing, patch, actor, false);
    }

    private Customer save(Customer existing, Map<String, Object> body, Actor actor, boolean isNew) {
        Map<String, Object> merged = existing == null ? new LinkedHashMap<>() : JsonMapper.toMap(existing);
        merged.putAll(body == null ? Map.of() : body);
        if (existing == null) {
            merged.putIfAbsent("id", Util.id("cus"));
            merged.put("createdAt", LocalDateTime.now().toString());
            merged.put("visits", 0);
            merged.put("points", 0);
            merged.put("lifetimeValue", BigDecimal.ZERO);
            merged.putIfAbsent("tier", "Atelier");
            merged.putIfAbsent("source", "desk");
        } else {
            merged.put("id", existing.id());
            merged.put("createdAt", existing.createdAt() == null ? LocalDateTime.now().toString() : existing.createdAt().toString());
            merged.put("updatedAt", LocalDateTime.now().toString());
            merged.put("visits", existing.visits());
            merged.put("points", existing.points());
            merged.put("lifetimeValue", existing.lifetimeValue());
        }
        Customer candidate;
        try {
            candidate = JsonMapper.from(merged, Customer.class, null);
        } catch (RuntimeException e) {
            throw new ApiException(422, "VALIDATION", "That record could not be read: " + e.getMessage());
        }
        Customer customer = validate(candidate, existing);

        if (isNew) {
            String code = Util.blank(customer.code()) ? nextCode() : customer.code();
            customer = withCode(customer, code);
        }
        customers.put(customer);
        search.index(customer);
        audit.record(isNew ? "customer.create" : "customer.update", "customer", customer.id(),
                (isNew ? "Created customer " : "Updated customer ") + customer.fullName(), actor,
                AuditEvent.INFO, Util.map("phone", Util.mask(customer.phone(), 3), "email", customer.email()));
        return customer;
    }

    private Customer validate(Customer candidate, Customer existing) {
        List<String> problems = new ArrayList<>();
        if (Util.blank(candidate.firstName()) && Util.blank(candidate.email()) && Util.blank(candidate.phone())) {
            problems.add("A record needs at least a name, a phone number or an e-mail address");
        }
        if (Util.present(candidate.firstName()) && candidate.firstName().length() > 60) problems.add("First name is too long");
        if (Util.present(candidate.lastName()) && candidate.lastName().length() > 60) problems.add("Last name is too long");
        if (Util.present(candidate.email()) && !candidate.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")) {
            problems.add("That e-mail address does not look right");
        }
        String phone = candidate.phone();
        if (Util.present(phone)) {
            String digits = phone.replaceAll("[^0-9]", "");
            if (digits.length() < 7 || digits.length() > 15) problems.add("Phone numbers have 7\u201315 digits");
        }
        if (candidate.notes() != null && candidate.notes().length() > 2000) problems.add("Notes are limited to 2000 characters");
        if (Util.present(candidate.email())) {
            Customer clash = customers.firstByIndex("email", candidate.email().toLowerCase(Locale.ROOT)).orElse(null);
            if (clash != null && (existing == null || !clash.id().equals(existing.id()))) {
                problems.add("Another record already uses " + candidate.email());
            }
        }
        if (Util.present(phone)) {
            Customer clash = byPhone(phone);
            if (clash != null && (existing == null || !clash.id().equals(existing.id()))) {
                problems.add("Another record already uses " + phone);
            }
        }
        if (!problems.isEmpty()) throw ApiException.validation(problems);

        return new Customer(
                Util.nz(candidate.id(), Util.id("cus")),
                Util.blank(candidate.code()) ? null : candidate.code().trim().toUpperCase(Locale.ROOT),
                clean(candidate.firstName()), clean(candidate.lastName()),
                Util.present(phone) ? formatPhone(phone) : null,
                Util.present(candidate.email()) ? candidate.email().trim().toLowerCase(Locale.ROOT) : null,
                clean(candidate.gender()),
                candidate.birthday(),
                clean(candidate.address()), clean(candidate.city()), clean(candidate.pincode()),
                CatalogueService.cleanTags(candidate.tags()),
                clean(candidate.notes()), clean(candidate.allergies()),
                candidate.marketingConsent(), candidate.dataConsent(),
                Util.nz(candidate.tier(), "Atelier"),
                Math.max(0, candidate.points()),
                Money.scale(candidate.lifetimeValue()),
                Math.max(0, candidate.visits()),
                candidate.firstVisitAt(), candidate.lastVisitAt(),
                Util.nz(candidate.source(), "desk"),
                candidate.preferredStaffId(), candidate.preferredTime(), candidate.userId(),
                candidate.styleProfile(),
                Math.min(5, Math.max(0, candidate.averageRating())),
                Math.max(0, candidate.noShows()), Math.max(0, candidate.cancellations()),
                candidate.createdAt() == null ? LocalDateTime.now() : candidate.createdAt(),
                LocalDateTime.now(),
                candidate.archived());
    }

    private static String clean(String value) {
        return com.sillage.security.Validator.sanitise(value);
    }

    public static String formatPhone(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+")) return digits;
        String numbers = digits.replaceAll("[^0-9]", "");
        if (numbers.length() == 10) return "+91 " + numbers.substring(0, 5) + " " + numbers.substring(5);
        if (numbers.length() > 10 && numbers.startsWith("91")) {
            String tail = numbers.substring(numbers.length() - 10);
            return "+91 " + tail.substring(0, 5) + " " + tail.substring(5);
        }
        return numbers;
    }

    private String nextCode() {
        int max = 0;
        for (Customer customer : customers.all()) {
            String code = customer.code();
            if (code == null) continue;
            String digits = code.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) max = Math.max(max, Util.parseInt(digits, 0));
        }
        return "C-%05d".formatted(max + 1);
    }

    private static Customer withCode(Customer customer, String code) {
        return new Customer(customer.id(), code, customer.firstName(), customer.lastName(), customer.phone(),
                customer.email(), customer.gender(), customer.birthday(), customer.address(), customer.city(),
                customer.pincode(), customer.tags(), customer.notes(), customer.allergies(), customer.marketingConsent(),
                customer.dataConsent(), customer.tier(), customer.points(), customer.lifetimeValue(), customer.visits(),
                customer.firstVisitAt(), customer.lastVisitAt(), customer.source(), customer.preferredStaffId(),
                customer.preferredTime(), customer.userId(), customer.styleProfile(), customer.averageRating(),
                customer.noShows(), customer.cancellations(), customer.createdAt(), customer.updatedAt(), customer.archived());
    }

    /** Soft delete: keeps fiscal history intact while removing the person from the active book. */
    public Customer archive(String id, Actor actor) {
        actor.require(Role.MANAGER);
        Customer customer = get(id);
        Customer archived = new Customer(customer.id(), customer.code(), customer.firstName(), customer.lastName(),
                customer.phone(), customer.email(), customer.gender(), customer.birthday(), customer.address(),
                customer.city(), customer.pincode(), customer.tags(), customer.notes(), customer.allergies(),
                customer.marketingConsent(), customer.dataConsent(), customer.tier(), customer.points(),
                customer.lifetimeValue(), customer.visits(), customer.firstVisitAt(), customer.lastVisitAt(),
                customer.source(), customer.preferredStaffId(), customer.preferredTime(), customer.userId(),
                customer.styleProfile(), customer.averageRating(), customer.noShows(), customer.cancellations(),
                customer.createdAt(), LocalDateTime.now(), true);
        customers.put(archived);
        search.index(archived);
        audit.record("customer.archive", "customer", id, "Archived " + customer.fullName(), actor);
        return archived;
    }

    public Customer restore(String id, Actor actor) {
        actor.require(Role.MANAGER);
        Customer customer = get(id);
        Customer restored = withArchived(customer, false);
        customers.put(restored);
        search.index(restored);
        return restored;
    }

    private static Customer withArchived(Customer c, boolean archived) {
        return new Customer(c.id(), c.code(), c.firstName(), c.lastName(), c.phone(), c.email(), c.gender(),
                c.birthday(), c.address(), c.city(), c.pincode(), c.tags(), c.notes(), c.allergies(),
                c.marketingConsent(), c.dataConsent(), c.tier(), c.points(), c.lifetimeValue(), c.visits(),
                c.firstVisitAt(), c.lastVisitAt(), c.source(), c.preferredStaffId(), c.preferredTime(), c.userId(),
                c.styleProfile(), c.averageRating(), c.noShows(), c.cancellations(), c.createdAt(), LocalDateTime.now(), archived);
    }

    /** Hard delete. Refused when fiscal documents reference the customer. */
    public void delete(String id, Actor actor) {
        actor.require(Role.OWNER);
        Customer customer = get(id);
        long bills = invoices.countWhere(i -> id.equals(i.customerId()));
        if (bills > 0) {
            throw ApiException.conflict("This customer has " + bills + " invoice(s). Archive instead, or use the erasure tool to anonymise.");
        }
        for (Appointment appointment : appointments.where(a -> id.equals(a.customerId()))) {
            appointments.remove(appointment.id());
            search.remove("appointment", appointment.id());
        }
        customers.remove(id);
        search.remove("customer", id);
        audit.record("customer.delete", "customer", id, "Deleted customer record", actor, AuditEvent.WARN, null);
    }

    /** Data-subject export: everything held about one person, as JSON. */
    public Map<String, Object> exportSubject(String id, Actor actor) {
        Customer customer = get(id);
        actor.requireSelfOrStaff(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", LocalDateTime.now().toString());
        out.put("exportedBy", actor.label());
        out.put("customer", JsonMapper.toMap(customer));
        out.put("appointments", appointments.where(a -> id.equals(a.customerId())).stream()
                .map(JsonMapper::toMap).toList());
        out.put("invoices", invoices.where(i -> id.equals(i.customerId())).stream()
                .map(JsonMapper::toMap).toList());
        out.put("messages", notifications.where(n -> id.equals(n.customerId())).stream()
                .map(JsonMapper::toMap).toList());
        return out;
    }

    /** Right to erasure: replaces identity with placeholders, keeps the fiscal trail legal. */
    public Customer erase(String id, Actor actor) {
        actor.require(Role.OWNER);
        Customer customer = get(id);
        Customer erased = new Customer(customer.id(), customer.code(), "Erased", "on request", null, null, null,
                null, null, null, null, List.of(), null, null, false, customer.dataConsent(), customer.tier(),
                customer.points(), customer.lifetimeValue(), customer.visits(), customer.firstVisitAt(),
                customer.lastVisitAt(), "erased", null, null, null, null, customer.averageRating(),
                customer.noShows(), customer.cancellations(), customer.createdAt(), LocalDateTime.now(), true);
        customers.put(erased);
        search.index(erased);
        audit.record("customer.erase", "customer", id, "Erased personal data on request", actor, AuditEvent.CRITICAL, null);
        return erased;
    }

    /** Finds or creates a customer for a booking — the guest-to-record path. */
    public Customer findOrCreate(String name, String phone, String email, String source, Actor actor) {
        Customer existing = byPhone(phone);
        if (existing == null) existing = byEmail(email);
        if (existing != null) return existing;
        String[] parts = splitName(name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", parts[0]);
        body.put("lastName", parts[1]);
        body.put("phone", phone);
        body.put("email", email);
        body.put("source", Util.nz(source, "online"));
        body.put("dataConsent", true);
        body.put("marketingConsent", false);
        try {
            return save(null, body, actor == null ? Actor.anonymous("system", "system") : actor, true);
        } catch (ApiException e) {
            // A partial identity (name only) is still worth keeping as a walk-in record.
            body.remove("phone");
            body.remove("email");
            body.put("firstName", Util.nz(parts[0], "Walk-in"));
            return save(null, body, actor == null ? Actor.anonymous("system", "system") : actor, true);
        }
    }

    public static String[] splitName(String name) {
        String value = Util.nz(name).trim();
        if (value.isEmpty()) return new String[]{"", ""};
        int space = value.indexOf(' ');
        if (space < 0) return new String[]{value, ""};
        return new String[]{value.substring(0, space), value.substring(space + 1).trim()};
    }

    public String staffName(String id) {
        Staff member = staff.get(id);
        return member == null ? "Any available" : member.name();
    }

    public List<Map<String, Object>> birthdaysWithin(int days) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Customer customer : customers.where(c -> !c.archived() && c.isBirthdayWithin(days))) {
            out.add(Util.map("id", customer.id(), "name", customer.fullName(), "phone", customer.phone(),
                    "email", customer.email(), "tier", customer.tier(), "birthday", customer.birthday().toString()));
        }
        return out;
    }

    /** Duplicate radar: same phone or e-mail under two records. */
    public List<Map<String, Object>> duplicates() {
        Map<String, List<Customer>> byPhone = new LinkedHashMap<>();
        Map<String, List<Customer>> byEmail = new LinkedHashMap<>();
        for (Customer customer : customers.all()) {
            if (Util.present(customer.phone())) {
                byPhone.computeIfAbsent(normalisePhone(customer.phone().replaceAll("[^0-9]", "")), k -> new ArrayList<>()).add(customer);
            }
            if (Util.present(customer.email())) {
                byEmail.computeIfAbsent(customer.email().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(customer);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        addDuplicateGroups(out, byPhone, "phone");
        addDuplicateGroups(out, byEmail, "email");
        return out;
    }

    private void addDuplicateGroups(List<Map<String, Object>> out, Map<String, List<Customer>> groups, String field) {
        for (Map.Entry<String, List<Customer>> entry : groups.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            Set<String> ids = new LinkedHashSet<>();
            for (Customer c : entry.getValue()) ids.add(c.id());
            boolean already = out.stream().anyMatch(m -> new LinkedHashSet<>(JsonMapper.asStringList(m.get("ids"))).equals(ids));
            if (already) continue;
            out.add(Util.map("field", field, "value", entry.getKey(),
                    "records", entry.getValue().stream().map(c -> Util.map("id", c.id(), "name", c.fullName(),
                            "visits", c.visits(), "lifetimeValue", c.lifetimeValue())).toList(),
                    "ids", new ArrayList<>(ids)));
        }
    }

    public String toCsv(List<Customer> list) {
        List<List<String>> rows = new ArrayList<>();
        for (Customer c : list) {
            rows.add(List.of(Util.nz(c.code()), Util.nz(c.firstName()), Util.nz(c.lastName()), Util.nz(c.phone()),
                    Util.nz(c.email()), Util.nz(c.gender()), c.birthday() == null ? "" : c.birthday().toString(),
                    Util.nz(c.address()), Util.nz(c.city()), Util.nz(c.pincode()),
                    Util.join(Util.nullSafe(c.tags()), "|"), Util.nz(c.tier()), String.valueOf(c.points()),
                    c.lifetimeValue() == null ? "0" : c.lifetimeValue().toPlainString(), String.valueOf(c.visits()),
                    c.firstVisitAt() == null ? "" : c.firstVisitAt().toString(),
                    c.lastVisitAt() == null ? "" : c.lastVisitAt().toString(),
                    Util.nz(c.source()), String.valueOf(c.marketingConsent()), Util.nz(c.notes())));
        }
        return Util.toCsv(List.of("code", "first_name", "last_name", "phone", "email", "gender", "birthday",
                "address", "city", "pincode", "tags", "tier", "points", "lifetime_value", "visits", "first_visit",
                "last_visit", "source", "marketing_consent", "notes"), rows);
    }

    public int importCsv(String csv, Actor actor) {
        actor.require(Role.MANAGER);
        List<List<String>> rows = Csv.read(csv);
        if (rows.size() < 2) return 0;
        List<String> header = rows.get(0).stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        int imported = 0;
        int skipped = 0;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.stream().allMatch(String::isBlank)) continue;
            Map<String, Object> body = new LinkedHashMap<>();
            for (int c = 0; c < header.size() && c < row.size(); c++) {
                String value = row.get(c) == null ? "" : row.get(c).trim();
                if (value.isEmpty()) continue;
                body.put(camel(header.get(c)), value);
            }
            for (String blocked : List.of("id", "code", "points", "lifetimeValue", "visits", "userId",
                    "createdAt", "updatedAt", "archived")) body.remove(blocked);
            Object tags = body.get("tags");
            if (tags instanceof String text && text.contains("|")) body.put("tags", List.of(text.split("\\|")));
            if (Util.blank(JsonMapper.asText(body.get("firstName")))
                    && Util.blank(JsonMapper.asText(body.get("email")))
                    && Util.blank(JsonMapper.asText(body.get("phone")))) { skipped++; continue; }
            body.put("source", Util.nz(JsonMapper.asText(body.get("source")), "import"));
            body.put("lifetimeValue", BigDecimal.ZERO);
            body.put("visits", 0);
            body.put("points", 0);
            try {
                save(null, body, actor, true);
                imported++;
            } catch (ApiException e) {
                skipped++;
            }
        }
        if (skipped > 0) com.sillage.core.Log.info("customer import: %d imported, %d skipped", imported, skipped);
        return imported;
    }

    private static String camel(String snake) {
        if (snake == null || !snake.contains("_")) return snake;
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') { upperNext = true; continue; }
            sb.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return sb.toString();
    }

    private static long median(List<Long> values) {
        if (values == null || values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return sorted.get(sorted.size() / 2);
    }

    private static List<Map<String, Object>> topEntries(Map<String, Integer> counts, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .forEach(e -> out.add(Util.map("name", e.getKey(), "count", e.getValue())));
        return out;
    }

    /** Minimal CSV reader: RFC4180 quoting, used by the import path. */
    static final class Csv {
        static List<List<String>> read(String text) {
            List<List<String>> rows = new ArrayList<>();
            if (text == null || text.isBlank()) return rows;
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                        else quoted = false;
                    } else cell.append(c);
                    continue;
                }
                switch (c) {
                    case '"' -> quoted = true;
                    case ',' -> { row.add(cell.toString()); cell.setLength(0); }
                    case '\n' -> { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>(); }
                    case '\r' -> { }
                    default -> cell.append(c);
                }
            }
            if (cell.length() > 0 || !row.isEmpty()) {
                row.add(cell.toString());
                rows.add(row);
            }
            return rows;
        }
    }
}
