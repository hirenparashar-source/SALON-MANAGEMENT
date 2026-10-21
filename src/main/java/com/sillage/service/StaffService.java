package com.sillage.service;

import com.sillage.core.ApiException;
import com.sillage.core.JsonMapper;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.Actor;
import com.sillage.model.Appointment;
import com.sillage.model.AuditEvent;
import com.sillage.model.Invoice;
import com.sillage.model.Role;
import com.sillage.model.ServiceItem;
import com.sillage.model.Staff;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The floor: stylists, their working hours, leave and performance. */
public final class StaffService {

    private final Repo<Staff> staff;
    private final Repo<Appointment> appointments;
    private final Repo<Invoice> invoices;
    private final Repo<ServiceItem> services;
    private final SearchService search;
    private final AuditService audit;

    public StaffService(Repo<Staff> staff, Repo<Appointment> appointments, Repo<Invoice> invoices,
                        Repo<ServiceItem> services, SearchService search, AuditService audit) {
        this.staff = staff;
        this.appointments = appointments;
        this.invoices = invoices;
        this.services = services;
        this.search = search;
        this.audit = audit;
    }

    public Repo<Staff> repo() { return staff; }

    public List<Staff> all() {
        List<Staff> out = staff.all();
        out.sort(Comparator.comparing((Staff s) -> s.active() ? 0 : 1).thenComparing(s -> Util.nz(s.name())));
        return out;
    }

    public List<Staff> active() {
        return staff.where(Staff::active);
    }

    public Staff get(String id) {
        Staff member = staff.get(id);
        if (member == null) throw ApiException.notFound("Team member " + Util.nz(id));
        return member;
    }

    public Staff find(String id) { return staff.get(id); }

    public String name(String id) {
        Staff member = staff.get(id);
        return member == null ? "Unassigned" : member.name();
    }

    /** Stylists who can perform a service and are free in the given window. */
    public List<Staff> eligibleFor(String serviceId) {
        ServiceItem service = services.get(serviceId);
        List<Staff> out = new ArrayList<>();
        for (Staff member : active()) {
            if (service != null && !service.performedBy(member.id())) continue;
            if (!member.certifiedFor(serviceId)) continue;
            out.add(member);
        }
        return out;
    }

    public Map<String, Object> profile(String id) {
        Staff member = get(id);
        List<Appointment> upcoming = appointments.where(a -> id.equals(a.staffId())
                && !a.status().isClosed() && a.start() != null && a.start().isAfter(LocalDateTime.now()));
        upcoming.sort(Comparator.comparing(Appointment::start));
        List<Appointment> done = appointments.where(a -> id.equals(a.staffId()) && a.status() == com.sillage.model.AppointmentStatus.COMPLETED);

        BigDecimal revenue = BigDecimal.ZERO;
        int chairs = done.size();
        double ratingSum = 0;
        int ratings = 0;
        Map<String, Integer> serviceCounts = new LinkedHashMap<>();
        for (Appointment appointment : done) {
            revenue = com.sillage.core.Money.add(revenue, appointment.price());
            for (Appointment.Item item : Util.nullSafe(appointment.items())) {
                serviceCounts.merge(Util.nz(item.name()), 1, Integer::sum);
            }
            if (appointment.rating() != null && appointment.rating() > 0) {
                ratingSum += appointment.rating();
                ratings++;
            }
        }
        int last30 = (int) done.stream().filter(a -> a.start() != null
                && a.start().isAfter(LocalDateTime.now().minusDays(30))).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("staff", member);
        out.put("upcoming", upcoming.stream().limit(20).map(a -> Util.map(
                "id", a.id(), "code", a.code(), "when", Util.format(a.start()), "status", a.status().name(),
                "services", a.serviceNames(), "customer", a.customerLabel())).toList());
        out.put("stats", Util.map(
                "completed", chairs,
                "last30Days", last30,
                "revenue", revenue,
                "averageTicket", chairs == 0 ? BigDecimal.ZERO : com.sillage.core.Money.divide(revenue, BigDecimal.valueOf(chairs)),
                "rating", ratings == 0 ? member.rating() : Math.round(ratingSum / ratings * 10) / 10.0,
                "ratings", ratings,
                "commissionPct", member.commissionPct(),
                "estimatedCommission", com.sillage.core.Money.percent(revenue, member.commissionPct())));
        out.put("topServices", topEntries(serviceCounts, 6));
        out.put("week", weekView(member));
        return out;
    }

    public List<Map<String, Object>> weekView(Staff member) {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        for (int i = 0; i < 7; i++) {
            LocalDate date = monday.plusDays(i);
            Staff.DayHours hours = member.hoursFor(date.getDayOfWeek());
            boolean off = hours == null || hours.closed() || member.offOn(date);
            List<Appointment> day = appointments.where(a -> member.id().equals(a.staffId())
                    && a.start() != null && a.start().toLocalDate().equals(date) && a.status().occupiesChair());
            int minutes = day.stream().mapToInt(Appointment::durationMinutes).sum();
            int capacity = off ? 0 : capacityMinutes(hours);
            out.add(Util.map(
                    "date", date.toString(),
                    "day", Util.dayName(date.getDayOfWeek()),
                    "closed", off,
                    "start", off ? null : hours.start(),
                    "end", off ? null : hours.end(),
                    "bookings", day.size(),
                    "bookedMinutes", minutes,
                    "capacityMinutes", capacity,
                    "utilisation", capacity == 0 ? 0 : Util.percent(minutes, capacity)));
        }
        return out;
    }

    public static int capacityMinutes(Staff.DayHours hours) {
        if (hours == null || hours.closed() || hours.start() == null || hours.end() == null) return 0;
        LocalTime start = parse(hours.start());
        LocalTime end = parse(hours.end());
        if (start == null || end == null || !end.isAfter(start)) return 0;
        return (int) java.time.Duration.between(start, end).toMinutes();
    }

    private static LocalTime parse(String value) {
        try {
            return LocalTime.parse(value.length() == 5 ? value + ":00" : value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ writes

    public Staff create(Map<String, Object> body, Actor actor) {
        actor.require(Role.MANAGER);
        Map<String, Object> merged = new LinkedHashMap<>(body == null ? Map.of() : body);
        merged.putIfAbsent("id", Util.id("stf"));
        merged.put("createdAt", LocalDateTime.now().toString());
        merged.putIfAbsent("active", true);
        merged.putIfAbsent("bufferMinutes", 10);
        Staff saved = normalise(JsonMapper.from(merged, Staff.class, null), null);
        if (Util.present(saved.email()) && staff.firstWhere(s -> saved.email().equalsIgnoreCase(s.email())).isPresent()) {
            throw ApiException.conflict("Another team member already uses that e-mail address");
        }
        staff.put(saved);
        search.index(saved);
        audit.record("staff.create", "staff", saved.id(), "Added " + saved.name() + " to the floor", actor);
        return saved;
    }

    public Staff update(String id, Map<String, Object> patch, Actor actor) {
        actor.require(Role.MANAGER);
        Staff existing = get(id);
        Map<String, Object> merged = JsonMapper.merge(JsonMapper.toMap(existing), patch);
        merged.put("id", existing.id());
        merged.put("createdAt", existing.createdAt());
        merged.put("updatedAt", LocalDateTime.now().toString());
        Staff saved = normalise(JsonMapper.from(merged, Staff.class, null), existing);
        staff.put(saved);
        search.index(saved);
        audit.record("staff.update", "staff", id, "Updated " + saved.name(), actor);
        return saved;
    }

    public void delete(String id, Actor actor) {
        actor.require(Role.OWNER);
        Staff member = get(id);
        long future = appointments.countWhere(a -> id.equals(a.staffId()) && !a.status().isClosed()
                && a.start() != null && a.start().isAfter(LocalDateTime.now()));
        if (future > 0) throw ApiException.conflict("Reassign " + future + " upcoming appointment(s) first");
        staff.remove(id);
        search.remove("staff", id);
        audit.record("staff.delete", "staff", id, "Removed " + member.name(), actor, AuditEvent.WARN, null);
    }

    public Staff deactivate(String id, Actor actor) {
        actor.require(Role.MANAGER);
        Staff member = get(id);
        Staff saved = new Staff(member.id(), member.name(), member.title(), member.bio(), member.quote(),
                member.specialties(), member.serviceIds(), member.schedule(), member.timeOff(), member.phone(),
                member.email(), member.colorKey(), member.commissionPct(), member.rating(), member.yearsExperience(),
                false, member.acceptsWalkIns(), member.bufferMinutes(), member.joinedAt(), member.createdAt(),
                LocalDateTime.now().toString());
        staff.put(saved);
        search.index(saved);
        return saved;
    }

    public Staff addTimeOff(String id, LocalDate date, String reason, Actor actor) {
        actor.require(Role.MANAGER);
        Staff member = get(id);
        if (date == null) throw ApiException.validation(List.of("Pick a date"));
        if (date.isBefore(LocalDate.now().minusDays(1))) throw ApiException.validation(List.of("That date is in the past"));
        List<Staff.TimeOff> timeOff = new ArrayList<>(Util.nullSafe(member.timeOff()));
        for (Staff.TimeOff existing : timeOff) {
            if (date.equals(existing.date())) throw ApiException.conflict("That day is already blocked");
        }
        timeOff.add(new Staff.TimeOff(Util.id("off"), date, com.sillage.security.Validator.sanitise(Util.nz(reason, "Leave")), true));
        timeOff.sort(Comparator.comparing(Staff.TimeOff::date));
        Staff saved = member.withTimeOff(timeOff);
        staff.put(saved);
        search.index(saved);
        audit.record("staff.timeoff", "staff", id, "Blocked " + date + " for " + saved.name(), actor);
        return saved;
    }

    public Staff removeTimeOff(String id, String timeOffId, Actor actor) {
        actor.require(Role.MANAGER);
        Staff member = get(id);
        List<Staff.TimeOff> timeOff = new ArrayList<>(Util.nullSafe(member.timeOff()));
        timeOff.removeIf(t -> timeOffId.equals(t.id()));
        Staff saved = member.withTimeOff(timeOff);
        staff.put(saved);
        search.index(saved);
        return saved;
    }

    private Staff normalise(Staff member, Staff existing) {
        List<String> problems = new ArrayList<>();
        if (Util.blank(member.name())) problems.add("A team member needs a name");
        if (member.name() != null && member.name().length() > 80) problems.add("Name is too long");
        if (member.commissionPct() < 0 || member.commissionPct() > 100) problems.add("Commission must be between 0 and 100%");
        if (member.bufferMinutes() < 0 || member.bufferMinutes() > 60) problems.add("Buffer must be between 0 and 60 minutes");
        if (Util.present(member.email()) && !member.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")) {
            problems.add("That e-mail address does not look right");
        }
        if (member.schedule() != null) {
            for (Staff.DayHours hours : member.schedule()) {
                if (hours.closed() || hours.start() == null || hours.end() == null) continue;
                LocalTime start = parse(hours.start());
                LocalTime end = parse(hours.end());
                if (start == null || end == null) { problems.add("Hours must use HH:mm"); break; }
                if (!end.isAfter(start)) { problems.add("Closing time must follow opening time"); break; }
            }
        }
        if (!problems.isEmpty()) throw ApiException.validation(problems);

        List<Staff.DayHours> schedule = new ArrayList<>();
        for (Staff.DayHours hours : Util.nullSafe(member.schedule())) {
            if (hours == null || Util.blank(hours.day())) continue;
            String day = hours.day().trim().toUpperCase(java.util.Locale.ROOT);
            if (day.length() > 3) {
                try {
                    day = DayOfWeek.valueOf(day).name();
                } catch (IllegalArgumentException e) {
                    continue;
                }
            }
            schedule.add(new Staff.DayHours(day, hours.closed() ? null : hours.start(), hours.closed() ? null : hours.end(),
                    hours.closed(), com.sillage.security.Validator.sanitise(hours.note())));
        }
        List<Staff.TimeOff> timeOff = new ArrayList<>();
        for (Staff.TimeOff off : Util.nullSafe(member.timeOff())) {
            if (off == null || off.date() == null) continue;
            timeOff.add(new Staff.TimeOff(Util.nz(off.id(), Util.id("off")), off.date(),
                    com.sillage.security.Validator.sanitise(off.reason()), off.allDay()));
        }
        return new Staff(Util.nz(member.id(), Util.id("stf")),
                com.sillage.security.Validator.sanitise(member.name().trim()),
                com.sillage.security.Validator.sanitise(Util.nz(member.title(), "Stylist")),
                com.sillage.security.Validator.sanitise(member.bio()),
                com.sillage.security.Validator.sanitise(member.quote()),
                CatalogueService.cleanTags(member.specialties()),
                CatalogueService.cleanIds(member.serviceIds()),
                schedule, timeOff,
                member.phone(), member.email() == null ? null : member.email().toLowerCase(java.util.Locale.ROOT),
                Util.nz(member.colorKey(), "brass"),
                member.commissionPct(),
                Math.min(5, Math.max(0, member.rating())),
                Math.max(0, member.yearsExperience()),
                member.active(), member.acceptsWalkIns(),
                Util.clamp(member.bufferMinutes(), 0, 60),
                member.joinedAt(),
                Util.nz(member.createdAt(), LocalDateTime.now().toString()),
                Util.nz(member.updatedAt(), LocalDateTime.now().toString()));
    }

    public Staff recordRating(String id, int rating) {
        Staff member = staff.get(id);
        if (member == null) return null;
        long count = appointments.countWhere(a -> id.equals(a.staffId()) && a.rating() != null);
        double updated = (member.rating() * Math.max(1, count - 1) + rating) / Math.max(1, count);
        Staff saved = member.withRating(Math.round(Math.min(5, Math.max(0, updated)) * 10) / 10.0);
        staff.put(saved);
        search.index(saved);
        return saved;
    }

    public Map<String, Object> stats() {
        List<Staff> all = staff.all();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("active", all.stream().filter(Staff::active).count());
        List<Map<String, Object>> leaderboard = new ArrayList<>();
        for (Staff member : all) {
            List<Appointment> done = appointments.where(a -> member.id().equals(a.staffId())
                    && a.status() == com.sillage.model.AppointmentStatus.COMPLETED
                    && a.start() != null && a.start().isAfter(LocalDateTime.now().minusDays(90)));
            BigDecimal revenue = done.stream().map(Appointment::price).reduce(BigDecimal.ZERO, com.sillage.core.Money::add);
            leaderboard.add(Util.map("id", member.id(), "name", member.name(), "title", member.title(),
                    "chairs", done.size(), "revenue", revenue, "rating", member.rating(),
                    "colour", member.colorKey()));
        }
        leaderboard.sort(Comparator.comparing(m -> (BigDecimal) m.get("revenue"), Comparator.reverseOrder()));
        out.put("leaderboard", leaderboard);
        long billed = invoices.countWhere(i -> i.staffId() != null);
        out.put("invoicesAttributed", invoices);
        return out;
    }

    private static List<Map<String, Object>> topEntries(Map<String, Integer> counts, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .forEach(e -> out.add(Util.map("name", e.getKey(), "count", e.getValue())));
        return out;
    }
}
