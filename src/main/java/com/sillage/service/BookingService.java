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
import com.sillage.model.BusinessSettings;
import com.sillage.model.Customer;
import com.sillage.model.Notification;
import com.sillage.model.Role;
import com.sillage.model.ServiceItem;
import com.sillage.model.Staff;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The booking engine: chair-time arithmetic, availability generation, conflict detection and the
 * appointment state machine.
 *
 * <p>Availability is derived, never stored. A slot exists when the salon is open, the stylist
 * works that weekday and is not on leave, the requested window fits inside their hours, it starts
 * far enough in the future, and it does not overlap an existing booking once the chair buffer is
 * applied. That keeps double-booking impossible by construction instead of by convention.
 */
public final class BookingService {

    /** One candidate chair time. */
    public record Slot(LocalDateTime start, LocalDateTime end, String staffId, String staffName,
                       boolean available, String reason, int durationMinutes) {}

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM");
    private static final Map<AppointmentStatus, Set<AppointmentStatus>> TRANSITIONS = new LinkedHashMap<>();

    static {
        TRANSITIONS.put(AppointmentStatus.REQUESTED, EnumSet.of(AppointmentStatus.CONFIRMED, AppointmentStatus.CANCELLED,
                AppointmentStatus.WAITLISTED, AppointmentStatus.ARRIVED, AppointmentStatus.NO_SHOW));
        TRANSITIONS.put(AppointmentStatus.CONFIRMED, EnumSet.of(AppointmentStatus.ARRIVED, AppointmentStatus.IN_CHAIR,
                AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW, AppointmentStatus.REQUESTED));
        TRANSITIONS.put(AppointmentStatus.ARRIVED, EnumSet.of(AppointmentStatus.IN_CHAIR, AppointmentStatus.COMPLETED,
                AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW));
        TRANSITIONS.put(AppointmentStatus.IN_CHAIR, EnumSet.of(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED));
        TRANSITIONS.put(AppointmentStatus.COMPLETED, EnumSet.noneOf(AppointmentStatus.class));
        TRANSITIONS.put(AppointmentStatus.CANCELLED, EnumSet.of(AppointmentStatus.REQUESTED, AppointmentStatus.CONFIRMED));
        TRANSITIONS.put(AppointmentStatus.NO_SHOW, EnumSet.of(AppointmentStatus.CONFIRMED, AppointmentStatus.ARRIVED));
        TRANSITIONS.put(AppointmentStatus.WAITLISTED, EnumSet.of(AppointmentStatus.CONFIRMED, AppointmentStatus.REQUESTED,
                AppointmentStatus.CANCELLED));
    }

    private final Repo<Appointment> appointments;
    private final Repo<ServiceItem> services;
    private final Repo<Staff> staffRepo;
    private final Repo<Customer> customers;
    private final SettingsService settings;
    private final CustomerService customerService;
    private final SearchService search;
    private final AuditService audit;
    private final NotificationService notifications;

    public BookingService(Repo<Appointment> appointments, Repo<ServiceItem> services, Repo<Staff> staffRepo,
                          Repo<Customer> customers, SettingsService settings, CustomerService customerService,
                          SearchService search, AuditService audit, NotificationService notifications) {
        this.appointments = appointments;
        this.services = services;
        this.staffRepo = staffRepo;
        this.customers = customers;
        this.settings = settings;
        this.customerService = customerService;
        this.search = search;
        this.audit = audit;
        this.notifications = notifications;
    }

    public Repo<Appointment> repo() { return appointments; }

    public static boolean canTransition(AppointmentStatus from, AppointmentStatus to) {
        if (from == null || to == null) return false;
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static List<AppointmentStatus> nextStates(AppointmentStatus from) {
        return List.copyOf(TRANSITIONS.getOrDefault(from, Set.of()));
    }

    // ------------------------------------------------------------------ availability

    /**
     * Every candidate slot for a service on a date, across one stylist or all eligible ones.
     * Unavailable slots are returned too, with the reason, so the desk can see *why*.
     */
    public List<Slot> slots(String serviceId, String variantId, String staffId, LocalDate date, boolean includeUnavailable) {
        BusinessSettings config = settings.current();
        if (config.isClosedOn(date)) return List.of();
        ServiceItem service = services.get(serviceId);
        int duration = service == null ? config.slotMinutes() : service.durationWith(variantId);
        int buffer = config.bufferMinutes();

        List<Staff> candidates = new ArrayList<>();
        if (Util.present(staffId)) {
            Staff member = staffRepo.get(staffId);
            if (member != null) candidates.add(member);
        } else {
            for (Staff member : staffRepo.where(Staff::active)) {
                if (service == null || service.performedBy(member.id())) candidates.add(member);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime earliest = now.plusMinutes(config.minAdvanceMinutes());
        LocalDateTime horizon = now.plusDays(config.maxAdvanceDays());
        List<Slot> out = new ArrayList<>();

        for (Staff member : candidates) {
            if (!member.active()) continue;
            Staff.DayHours hours = effectiveHours(member, date, config);
            if (hours == null || hours.closed()) continue;
            LocalTime open = parseTime(hours.start());
            LocalTime close = parseTime(hours.end());
            if (open == null || close == null) continue;
            int memberBuffer = member.bufferMinutes() > 0 ? member.bufferMinutes() : buffer;
            boolean leave = member.offOn(date);
            List<Appointment> booked = appointments.where(a -> member.id().equals(a.staffId())
                    && a.start() != null && a.start().toLocalDate().equals(date) && a.status().occupiesChair());

            int step = Math.max(5, config.slotMinutes());
            LocalDateTime cursor = date.atTime(open);
            LocalDateTime last = date.atTime(close).minusMinutes(duration);
            int guard = 0;
            while (!cursor.isAfter(last) && guard++ < 400) {
                LocalDateTime end = cursor.plusMinutes(duration);
                String reason = null;
                if (leave) reason = "On leave";
                else if (cursor.isBefore(earliest)) reason = "Too soon to book";
                else if (cursor.isAfter(horizon)) reason = "Outside the booking window";
                else {
                    Appointment clash = conflict(member.id(), cursor, end.plusMinutes(memberBuffer), null);
                    if (clash != null) reason = "Chair taken";
                }
                if (reason == null || includeUnavailable) {
                    out.add(new Slot(cursor, end, member.id(), member.name(), reason == null, reason, duration));
                }
                cursor = cursor.plusMinutes(step);
            }
        }
        out.sort(Comparator.comparing(Slot::start).thenComparing(Slot::staffName));
        return out;
    }

    /** Hours for a stylist on a date, clipped to the salon's own opening hours. */
    public Staff.DayHours effectiveHours(Staff member, LocalDate date, BusinessSettings config) {
        Staff.DayHours personal = member.hoursFor(date.getDayOfWeek());
        Staff.DayHours house = config.hoursFor(date);
        if (personal == null || personal.closed()) return null;
        if (house == null || house.closed()) return personal;
        LocalTime start = later(parseTime(personal.start()), parseTime(house.start()));
        LocalTime end = earlier(parseTime(personal.end()), parseTime(house.end()));
        if (start == null || end == null || !end.isAfter(start)) return null;
        return new Staff.DayHours(date.getDayOfWeek().name(), format(start), format(end), false, personal.note());
    }

    /** Multi-day availability grid used by the public booking flow. */
    public Map<String, Object> availability(String serviceId, String variantId, String staffId, LocalDate from, int days) {
        BusinessSettings config = settings.current();
        ServiceItem service = services.get(serviceId);
        if (service == null) throw ApiException.notFound("Service " + Util.nz(serviceId));
        LocalDate start = from == null ? LocalDate.now() : from;
        int span = Util.clamp(days, 1, 21);
        List<Map<String, Object>> dayList = new ArrayList<>();
        int totalFree = 0;
        for (int i = 0; i < span; i++) {
            LocalDate date = start.plusDays(i);
            List<Slot> slots = slots(serviceId, variantId, staffId, date, false);
            totalFree += slots.size();

            // A day with no slots has a reason, and the reason matters to the person reading it:
            // "we are closed on Mondays" is not the same sentence as "Monday is fully booked".
            String holiday = config.holidayNameOn(date);
            boolean houseClosed = config.isClosedOn(date);
            Staff.DayHours houseHours = config.hoursFor(date);
            boolean dayOff = !houseClosed && (houseHours == null || houseHours.closed());
            String reason = null;
            if (houseClosed) reason = Util.present(holiday) ? holiday : "Closed";
            else if (dayOff) reason = "Closed";
            else if (slots.isEmpty()) reason = "Fully booked";

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", date.toString());
            day.put("label", DAY.format(date));
            day.put("weekday", Util.dayName(date.getDayOfWeek()));
            day.put("closed", houseClosed || dayOff || slots.isEmpty());
            day.put("holiday", holiday);
            day.put("dayOff", dayOff);
            day.put("reason", reason);
            day.put("slotCount", slots.size());
            day.put("first", slots.isEmpty() ? null : Util.formatTime(slots.get(0).start()));
            day.put("last", slots.isEmpty() ? null : Util.formatTime(slots.get(slots.size() - 1).start()));
            List<Map<String, Object>> byStaff = new ArrayList<>();
            Map<String, List<Slot>> grouped = new LinkedHashMap<>();
            for (Slot slot : slots) grouped.computeIfAbsent(slot.staffId(), k -> new ArrayList<>()).add(slot);
            grouped.forEach((id, list) -> byStaff.add(Util.map(
                    "staffId", id, "staffName", list.get(0).staffName(),
                    "count", list.size(),
                    "times", list.stream().limit(24).map(s -> Util.formatTime(s.start())).toList(),
                    "slots", list.stream().limit(48).map(s -> Util.map("start", s.start().toString(),
                            "end", s.end().toString(), "time", Util.formatTime(s.start()))).toList())));
            day.put("byStaff", byStaff);
            dayList.add(day);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", Util.map("id", service.id(), "name", service.name(),
                "duration", Util.humanDuration(service.durationWith(variantId)),
                "price", service.priceWith(variantId), "variant", service.variantLabel(variantId)));
        out.put("staff", Util.present(staffId) && staffRepo.get(staffId) != null
                ? Util.map("id", staffId, "name", staffRepo.get(staffId).name()) : null);
        out.put("days", dayList);
        out.put("totalSlots", totalFree);
        out.put("generatedAt", LocalDateTime.now().toString());
        return out;
    }

    /** Overlap check for one stylist, ignoring a specific appointment (for reschedules). */
    public Appointment conflict(String staffId, LocalDateTime start, LocalDateTime end, String ignoreId) {
        for (Appointment existing : appointments.where(a -> staffId.equals(a.staffId()) && a.status().occupiesChair())) {
            if (ignoreId != null && ignoreId.equals(existing.id())) continue;
            if (existing.start() == null || existing.end() == null) continue;
            boolean overlaps = start.isBefore(existing.end()) && existing.start().isBefore(end);
            if (overlaps) return existing;
        }
        return null;
    }

    /** True when the same guest already holds a chair in this window. */
    public Appointment duplicateGuest(String phone, LocalDateTime start) {
        if (Util.blank(phone) || start == null) return null;
        String key = CustomerService.phoneKey(phone);
        if (key == null) return null;
        for (Appointment a : appointments.where(x -> x.status().occupiesChair() && x.start() != null
                && Math.abs(Duration.between(x.start(), start).toMinutes()) <= 30)) {
            String other = a.guestPhone() == null ? null : CustomerService.phoneKey(a.guestPhone());
            if (key.equals(other)) return a;
            if (a.customerId() != null) {
                Customer customer = customers.get(a.customerId());
                if (customer != null && key.equals(CustomerService.phoneKey(customer.phone()))) return a;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ booking

    public Appointment book(Map<String, Object> body, Actor actor) {
        BusinessSettings config = settings.current();
        boolean online = actor == null || actor.isGuest();
        if (online && !config.features().getOrDefault("onlineBooking", true)) {
            throw ApiException.unavailable("Online booking is paused. Please call the studio.");
        }

        List<Map<String, Object>> requested = requestedItems(body);
        if (requested.isEmpty()) throw ApiException.validation(List.of("Choose at least one service"));

        String staffId = JsonMapper.asText(body.get("staffId"));
        LocalDateTime start = JsonMapper.asDateTime(body.get("start"));
        if (start == null) throw ApiException.validation(List.of("Choose a date and time"));
        start = start.withSecond(0).withNano(0);

        Customer customer = resolveCustomer(body, actor, online);
        List<Appointment.Item> items = new ArrayList<>();
        int totalMinutes = 0;
        BigDecimal price = BigDecimal.ZERO;
        List<String> problems = new ArrayList<>();

        for (Map<String, Object> entry : requested) {
            String serviceId = JsonMapper.asText(entry.get("serviceId"));
            ServiceItem service = services.get(serviceId);
            if (service == null) { problems.add("Unknown service " + Util.nz(serviceId)); continue; }
            if (!service.active()) { problems.add(service.name() + " is no longer offered"); continue; }
            if (online && !service.onlineBookable()) { problems.add(service.name() + " must be booked at the desk"); continue; }
            String variantId = JsonMapper.asText(entry.get("variantId"));
            if (Util.present(variantId) && service.variantLabel(variantId) == null) variantId = null;
            int minutes = service.durationWith(variantId);
            BigDecimal amount = service.priceWith(variantId);
            String label = service.name() + (variantId == null ? "" : " \u00b7 " + service.variantLabel(variantId));
            items.add(new Appointment.Item(service.id(), variantId, label, minutes, amount));
            totalMinutes += minutes;
            price = Money.add(price, amount);
        }
        if (!problems.isEmpty()) throw ApiException.validation(problems);

        Staff member = resolveStaff(staffId, items, start, config);
        if (member == null) throw ApiException.validation(List.of("Choose a stylist for this booking"));

        LocalDateTime end = start.plusMinutes(totalMinutes);
        int buffer = member.bufferMinutes() > 0 ? member.bufferMinutes() : config.bufferMinutes();

        // --- policy gates -------------------------------------------------
        if (config.isClosedOn(start.toLocalDate())) {
            throw ApiException.conflict("The studio is closed on " + Util.formatDate(start.toLocalDate())
                    + " (" + Util.nz(config.holidayNameOn(start.toLocalDate()), "holiday") + ")");
        }
        Staff.DayHours hours = effectiveHours(member, start.toLocalDate(), config);
        if (hours == null) {
            throw ApiException.conflict(member.firstName() + " does not work on " + Util.dayName(start.toLocalDate().getDayOfWeek()));
        }
        LocalTime open = parseTime(hours.start());
        LocalTime close = parseTime(hours.end());
        if (start.toLocalTime().isBefore(open) || end.toLocalTime().isAfter(close)) {
            throw ApiException.conflict("That time falls outside " + member.firstName() + "'s hours ("
                    + hours.start() + "\u2013" + hours.end() + ")");
        }
        if (online && start.isBefore(LocalDateTime.now().plusMinutes(config.minAdvanceMinutes()))) {
            throw ApiException.conflict("Online bookings need at least " + config.minAdvanceMinutes() + " minutes' notice. Call the studio for a same-hour chair.");
        }
        if (start.isAfter(LocalDateTime.now().plusDays(config.maxAdvanceDays()))) {
            throw ApiException.conflict("The book is open " + config.maxAdvanceDays() + " days ahead");
        }
        Appointment clash = conflict(member.id(), start, end.plusMinutes(buffer), null);
        if (clash != null) {
            throw new ApiException(409, "CHAIR_TAKEN", "That chair is already held at " + Util.formatTime(clash.start()),
                    Map.of("staff", member.name(), "existingStart", clash.start().toString(),
                            "suggestion", nextFreeSlot(member.id(), items, start, buffer)));
        }
        Appointment dupe = duplicateGuest(customer == null ? JsonMapper.asText(body.get("guestPhone")) : customer.phone(), start);
        if (dupe != null && online) {
            throw new ApiException(409, "DUPLICATE_BOOKING", "We already have a chair for that number at this time",
                    Map.of("code", dupe.code(), "start", dupe.start().toString()));
        }

        BigDecimal deposit = BigDecimal.ZERO;
        if (config.requireDeposit() && online) {
            deposit = Money.percent(price, config.depositPct() == null ? 0 : config.depositPct().doubleValue());
        }

        Appointment appointment = new Appointment(
                Util.id("apt"),
                nextCode(start.toLocalDate()),
                customer == null ? null : customer.id(),
                customer == null ? com.sillage.security.Validator.sanitise(JsonMapper.asText(body.get("guestName"))) : null,
                customer == null ? JsonMapper.asText(body.get("guestPhone")) : null,
                customer == null ? JsonMapper.asText(body.get("guestEmail")) : null,
                member.id(),
                items, start, end, totalMinutes,
                online ? AppointmentStatus.REQUESTED : AppointmentStatus.CONFIRMED,
                price, deposit,
                com.sillage.security.Validator.sanitise(JsonMapper.asText(body.get("notes"))),
                actor != null && actor.isStaff() ? com.sillage.security.Validator.sanitise(JsonMapper.asText(body.get("internalNotes"))) : null,
                online ? "online" : "desk",
                0, null, LocalDateTime.now(), LocalDateTime.now(),
                online ? null : LocalDateTime.now(), null, null, null, null, null, null,
                actor == null ? "system" : actor.label());

        appointments.put(appointment);
        search.index(appointment);
        audit.record("booking.create", "appointment", appointment.id(),
                "Booked " + Util.join(items.stream().map(Appointment.Item::name).toList(), ", ")
                        + " with " + member.name() + " at " + Util.format(start), actor, AuditEvent.INFO,
                Util.map("staff", member.id(), "start", start.toString(), "value", price.toPlainString(),
                        "channel", appointment.source()));

        notifyBooking(appointment, customer, member, "booking.confirmed");
        return appointment;
    }

    private List<Map<String, Object>> requestedItems(Map<String, Object> body) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object raw = body.get("items");
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                Map<String, Object> map = JsonMapper.asMap(entry);
                if (!map.isEmpty()) out.add(map);
            }
        }
        if (out.isEmpty()) {
            String serviceId = JsonMapper.asText(body.get("serviceId"));
            if (Util.present(serviceId)) {
                Map<String, Object> single = new LinkedHashMap<>();
                single.put("serviceId", serviceId);
                single.put("variantId", body.get("variantId"));
                out.add(single);
            }
        }
        List<String> serviceIds = JsonMapper.asStringList(body.get("serviceIds"));
        if (out.isEmpty()) {
            for (String id : serviceIds) {
                Map<String, Object> single = new LinkedHashMap<>();
                single.put("serviceId", id);
                out.add(single);
            }
        }
        return out;
    }

    private Customer resolveCustomer(Map<String, Object> body, Actor actor, boolean online) {
        String customerId = JsonMapper.asText(body.get("customerId"));
        if (Util.present(customerId)) {
            Customer customer = customers.get(customerId);
            if (customer == null) throw ApiException.notFound("Customer " + customerId);
            if (online && actor != null && actor.isClient() && !customerId.equals(actor.customerId())) {
                throw ApiException.forbidden("You can only book for your own record");
            }
            return customer;
        }
        if (actor != null && Util.present(actor.customerId())) {
            Customer customer = customers.get(actor.customerId());
            if (customer != null) return customer;
        }
        if (!online || !settings.current().allowGuestCheckout()) {
            String name = JsonMapper.asText(body.get("guestName"));
            String phone = JsonMapper.asText(body.get("guestPhone"));
            if (Util.blank(name) || Util.blank(phone)) {
                throw ApiException.validation(List.of("A desk booking needs a customer name and phone number"));
            }
            return customerService.findOrCreate(name, phone, JsonMapper.asText(body.get("guestEmail")), "desk", actor);
        }
        // Public booking: keep an existing record if we recognise the phone, otherwise stay a guest.
        String phone = JsonMapper.asText(body.get("guestPhone"));
        Customer known = customerService.byPhone(phone);
        return known;
    }

    private Staff resolveStaff(String staffId, List<Appointment.Item> items, LocalDateTime start, BusinessSettings config) {
        if (Util.present(staffId) && !"any".equalsIgnoreCase(staffId)) {
            Staff member = staffRepo.get(staffId);
            if (member == null) throw ApiException.notFound("Stylist " + staffId);
            if (!member.active()) throw ApiException.conflict(member.name() + " is not taking chairs right now");
            for (Appointment.Item item : items) {
                if (!member.certifiedFor(item.serviceId())) {
                    throw ApiException.conflict(member.firstName() + " is not listed for " + item.name());
                }
            }
            return member;
        }
        // "Any available": pick the eligible stylist with the lightest day.
        List<Staff> eligible = staffRepo.where(s -> s.active() && items.stream().allMatch(i -> s.certifiedFor(i.serviceId())));
        if (eligible.isEmpty()) return null;
        eligible.sort(Comparator.comparingLong(s -> appointments.countWhere(a -> s.id().equals(a.staffId())
                && a.start() != null && a.start().toLocalDate().equals(start.toLocalDate()) && a.status().occupiesChair())));
        return eligible.get(0);
    }

    private String nextFreeSlot(String staffId, List<Appointment.Item> items, LocalDateTime wanted, int buffer) {
        int minutes = items.stream().mapToInt(Appointment.Item::durationMinutes).sum();
        LocalDateTime cursor = wanted.plusMinutes(15);
        for (int i = 0; i < 40; i++) {
            if (conflict(staffId, cursor, cursor.plusMinutes(minutes + buffer), null) == null) {
                return Util.formatTime(cursor);
            }
            cursor = cursor.plusMinutes(15);
        }
        return "another day";
    }

    public String nextCode(LocalDate date) {
        long today = appointments.countWhere(a -> a.createdAt() != null && a.createdAt().toLocalDate().equals(LocalDate.now()));
        return "A%s-%03d".formatted(date.format(DateTimeFormatter.ofPattern("ddMM")), today + 1);
    }

    // ------------------------------------------------------------------ lifecycle

    public Appointment setStatus(String id, AppointmentStatus target, String note, Actor actor) {
        Appointment appointment = get(id);
        if (!canTransition(appointment.status(), target)) {
            throw ApiException.conflict("Cannot move a booking from " + appointment.status().label() + " to " + target.label());
        }
        if (actor != null && actor.isClient() && !appointment.id().equals(actor.customerId())
                && !Util.nz(appointment.customerId()).equals(actor.customerId())) {
            throw ApiException.forbidden("That booking is not yours");
        }
        if (target == AppointmentStatus.CANCELLED && actor != null && !actor.isStaff()) {
            BusinessSettings config = settings.current();
            long hoursAhead = Duration.between(LocalDateTime.now(), appointment.start()).toHours();
            if (hoursAhead < config.cancellationHours() && config.requireDeposit()) {
                note = Util.nz(note) + " (inside the " + config.cancellationHours() + "h window \u2014 deposit retained)";
            }
        }
        Appointment updated = appointment.withStatus(target, LocalDateTime.now());
        if (target == AppointmentStatus.CANCELLED || target == AppointmentStatus.NO_SHOW) {
            updated = new Appointment(updated.id(), updated.code(), updated.customerId(), updated.guestName(),
                    updated.guestPhone(), updated.guestEmail(), updated.staffId(), updated.items(), updated.start(),
                    updated.end(), updated.durationMinutes(), target, updated.price(), updated.deposit(),
                    updated.notes(), updated.internalNotes(), updated.source(), updated.queueNumber(),
                    updated.invoiceId(), updated.createdAt(), LocalDateTime.now(), updated.confirmedAt(),
                    updated.completedAt(), LocalDateTime.now(),
                    com.sillage.security.Validator.sanitise(Util.nz(note)), updated.reminderSentAt(),
                    updated.rating(), updated.ratingComment(), updated.createdBy());
        } else if (Util.present(note)) {
            updated = new Appointment(updated.id(), updated.code(), updated.customerId(), updated.guestName(),
                    updated.guestPhone(), updated.guestEmail(), updated.staffId(), updated.items(), updated.start(),
                    updated.end(), updated.durationMinutes(), target, updated.price(), updated.deposit(),
                    Util.nz(updated.notes()) + (updated.notes() == null || updated.notes().isBlank() ? "" : " \u00b7 ") + note,
                    updated.internalNotes(), updated.source(), updated.queueNumber(), updated.invoiceId(),
                    updated.createdAt(), LocalDateTime.now(), updated.confirmedAt(), updated.completedAt(),
                    updated.cancelledAt(), updated.cancellationReason(), updated.reminderSentAt(),
                    updated.rating(), updated.ratingComment(), updated.createdBy());
        }
        appointments.put(updated);
        search.index(updated);

        if (target == AppointmentStatus.COMPLETED) {
            for (Appointment.Item item : Util.nullSafe(updated.items())) {
                ServiceItem service = services.get(item.serviceId());
                if (service != null) services.put(service.withBookingCount(service.timesBooked() + 1));
            }
        }
        audit.record("booking.status." + target.name().toLowerCase(Locale.ROOT), "appointment", id,
                appointment.status().label() + " \u2192 " + target.label() + " (" + updated.code() + ")", actor,
                target == AppointmentStatus.NO_SHOW ? AuditEvent.WARN : AuditEvent.INFO,
                Util.present(note) ? Map.of("note", note) : null);

        if (target == AppointmentStatus.CONFIRMED) {
            notifyBooking(updated, customerOf(updated), staffRepo.get(updated.staffId()), "booking.confirmed");
        } else if (target == AppointmentStatus.CANCELLED) {
            notifyBooking(updated, customerOf(updated), staffRepo.get(updated.staffId()), "booking.cancelled");
        }
        return updated;
    }

    public Appointment reschedule(String id, LocalDateTime newStart, String newStaffId, Actor actor) {
        Appointment appointment = get(id);
        if (appointment.status().isClosed() && appointment.status() != AppointmentStatus.CANCELLED) {
            throw ApiException.conflict("A " + appointment.status().label().toLowerCase(Locale.ROOT) + " booking cannot be moved");
        }
        BusinessSettings config = settings.current();
        LocalDateTime start = newStart == null ? appointment.start() : newStart.withSecond(0).withNano(0);
        Staff member = Util.present(newStaffId) ? staffRepo.get(newStaffId) : staffRepo.get(appointment.staffId());
        if (member == null) throw ApiException.notFound("Stylist");
        if (config.isClosedOn(start.toLocalDate())) throw ApiException.conflict("The studio is closed that day");
        Staff.DayHours hours = effectiveHours(member, start.toLocalDate(), config);
        if (hours == null) throw ApiException.conflict(member.firstName() + " does not work that day");
        LocalDateTime end = start.plusMinutes(appointment.durationMinutes());
        if (start.toLocalTime().isBefore(parseTime(hours.start())) || end.toLocalTime().isAfter(parseTime(hours.end()))) {
            throw ApiException.conflict("That time is outside " + member.firstName() + "'s hours");
        }
        int buffer = member.bufferMinutes() > 0 ? member.bufferMinutes() : config.bufferMinutes();
        Appointment clash = conflict(member.id(), start, end.plusMinutes(buffer), id);
        if (clash != null) {
            throw new ApiException(409, "CHAIR_TAKEN", "That chair is taken at " + Util.formatTime(clash.start()),
                    Map.of("suggestion", nextFreeSlot(member.id(), Util.nullSafe(appointment.items()), start, buffer)));
        }
        Appointment moved = appointment.withWindow(start, end, appointment.durationMinutes());
        if (Util.present(newStaffId) && !newStaffId.equals(appointment.staffId())) moved = moved.withStaff(newStaffId);
        if (moved.status() == AppointmentStatus.CANCELLED) moved = moved.withStatus(AppointmentStatus.CONFIRMED, LocalDateTime.now());
        appointments.put(moved);
        search.index(moved);
        audit.record("booking.reschedule", "appointment", id,
                "Moved " + moved.code() + " to " + Util.format(start) + " with " + member.name(), actor);
        notifyBooking(moved, customerOf(moved), member, "booking.confirmed");
        return moved;
    }

    public Appointment cancel(String id, String reason, Actor actor) {
        return setStatus(id, AppointmentStatus.CANCELLED, reason, actor);
    }

    public Appointment rate(String id, int rating, String comment, Actor actor) {
        Appointment appointment = get(id);
        if (appointment.status() != AppointmentStatus.COMPLETED) throw ApiException.conflict("Only completed visits can be rated");
        if (actor != null && actor.isClient() && !Util.nz(appointment.customerId()).equals(actor.customerId())) {
            throw ApiException.forbidden("That visit is not yours");
        }
        int value = Util.clamp(rating, 1, 5);
        Appointment rated = appointment.withRating(value, com.sillage.security.Validator.sanitise(Util.truncate(comment, 400)));
        appointments.put(rated);
        if (rated.staffId() != null) {
            Staff member = staffRepo.get(rated.staffId());
            if (member != null) {
                double avg = member.rating() == 0 ? value : (member.rating() * 0.8) + (value * 0.2);
                staffRepo.put(member.withRating(Math.round(avg * 10) / 10.0));
                search.index(staffRepo.get(rated.staffId()));
            }
        }
        audit.record("booking.rating", "appointment", id, "Rated " + value + "/5", actor);
        return rated;
    }

    public Appointment attachInvoice(String id, String invoiceId) {
        Appointment appointment = get(id);
        Appointment updated = appointment.withInvoice(invoiceId, appointment.status() == AppointmentStatus.REQUESTED
                ? AppointmentStatus.CONFIRMED : appointment.status());
        appointments.put(updated);
        search.index(updated);
        return updated;
    }

    public Appointment markReminder(String id) {
        Appointment appointment = appointments.get(id);
        if (appointment == null) return null;
        Appointment updated = appointment.withReminder(LocalDateTime.now());
        appointments.put(updated);
        return updated;
    }

    // ------------------------------------------------------------------ reads

    public Appointment get(String id) {
        Appointment appointment = appointments.get(id);
        if (appointment == null) throw ApiException.notFound("Booking " + Util.nz(id));
        return appointment;
    }

    public Appointment find(String id) { return appointments.get(id); }

    public Appointment byCode(String code) {
        if (Util.blank(code)) return null;
        return appointments.firstWhere(a -> code.equalsIgnoreCase(a.code())).orElse(null);
    }

    public Customer customerOf(Appointment appointment) {
        return appointment == null || appointment.customerId() == null ? null : customers.get(appointment.customerId());
    }

    public List<Appointment> range(LocalDate from, LocalDate to, String staffId, String status) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        List<Appointment> out = appointments.where(a -> a.start() != null
                && !a.start().isBefore(start) && a.start().isBefore(end)
                && (!Util.present(staffId) || staffId.equals(a.staffId()))
                && (!Util.present(status) || status.equalsIgnoreCase(String.valueOf(a.status()))));
        out.sort(Comparator.comparing(Appointment::start));
        return out;
    }

    public List<Appointment> forCustomer(String customerId) {
        List<Appointment> out = appointments.where(a -> customerId.equals(a.customerId()));
        out.sort(Comparator.comparing(Appointment::start, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** The desk's day board: chairs as columns, time as rows, bookings as blocks. */
    public Map<String, Object> dayBoard(LocalDate date) {
        BusinessSettings config = settings.current();
        List<Staff> members = staffRepo.where(Staff::active);
        members.sort(Comparator.comparing(Staff::name));
        List<Map<String, Object>> columns = new ArrayList<>();
        LocalTime earliest = LocalTime.of(23, 59);
        LocalTime latest = LocalTime.of(0, 0);
        for (Staff member : members) {
            Staff.DayHours hours = effectiveHours(member, date, config);
            boolean off = hours == null || member.offOn(date);
            List<Appointment> day = appointments.where(a -> member.id().equals(a.staffId())
                    && a.start() != null && a.start().toLocalDate().equals(date));
            day.sort(Comparator.comparing(Appointment::start));
            if (!off) {
                earliest = earlier(earliest, parseTime(hours.start()));
                latest = later(latest, parseTime(hours.end()));
            }
            columns.add(Util.map(
                    "staffId", member.id(),
                    "name", member.name(),
                    "title", member.title(),
                    "colour", member.colorKey(),
                    "closed", off,
                    "start", off ? null : hours.start(),
                    "end", off ? null : hours.end(),
                    "capacityMinutes", off ? 0 : StaffService.capacityMinutes(hours),
                    "bookedMinutes", day.stream().filter(a -> a.status().occupiesChair()).mapToInt(Appointment::durationMinutes).sum(),
                    "appointments", day.stream().map(this::summary).toList()));
        }
        if (earliest.equals(LocalTime.of(23, 59))) earliest = LocalTime.of(9, 0);
        if (latest.equals(LocalTime.MIDNIGHT)) latest = LocalTime.of(20, 0);

        List<Appointment> all = range(date, date, null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        out.put("label", DAY.format(date));
        out.put("holiday", config.holidayNameOn(date));
        out.put("dayStart", format(earliest));
        out.put("dayEnd", format(latest));
        out.put("slotMinutes", config.slotMinutes());
        out.put("columns", columns);
        out.put("totals", Util.map(
                "appointments", all.size(),
                "confirmed", all.stream().filter(a -> a.status() == AppointmentStatus.CONFIRMED).count(),
                "requested", all.stream().filter(a -> a.status() == AppointmentStatus.REQUESTED).count(),
                "completed", all.stream().filter(a -> a.status() == AppointmentStatus.COMPLETED).count(),
                "cancelled", all.stream().filter(a -> a.status() == AppointmentStatus.CANCELLED).count(),
                "noShows", all.stream().filter(a -> a.status() == AppointmentStatus.NO_SHOW).count(),
                "revenue", all.stream().filter(a -> a.status() == AppointmentStatus.COMPLETED)
                        .map(Appointment::price).reduce(BigDecimal.ZERO, Money::add),
                "chairMinutes", columns.stream().mapToInt(c -> (int) c.get("bookedMinutes")).sum(),
                "capacityMinutes", columns.stream().mapToInt(c -> (int) c.get("capacityMinutes")).sum()));
        out.put("now", LocalDateTime.now().toString());
        return out;
    }

    public Map<String, Object> summary(Appointment a) {
        Staff member = staffRepo.get(a.staffId());
        Customer customer = customerOf(a);
        String who = customer != null ? customer.fullName() : Util.nz(a.guestName(), "Walk-in");
        return Util.map(
                "id", a.id(),
                "code", a.code(),
                "customer", who,
                "customerId", a.customerId(),
                "phone", customer != null ? customer.phone() : a.guestPhone(),
                "staff", member == null ? null : member.name(),
                "staffId", a.staffId(),
                "colour", member == null ? "stone" : member.colorKey(),
                "start", a.start() == null ? null : a.start().toString(),
                "end", a.end() == null ? null : a.end().toString(),
                "time", Util.formatTime(a.start()),
                "duration", a.durationMinutes(),
                "status", a.status().name(),
                "statusLabel", a.status().label(),
                "services", a.serviceNames(),
                "price", a.price(),
                "notes", a.notes(),
                "source", a.source(),
                "invoiceId", a.invoiceId(),
                "queueNumber", a.queueNumber(),
                "rating", a.rating());
    }

    public Map<String, Object> detail(String id, Actor actor) {
        Appointment appointment = get(id);
        Customer customer = customerOf(appointment);
        if (actor != null && actor.isClient() && !Util.nz(appointment.customerId()).equals(actor.customerId())) {
            throw ApiException.forbidden("That booking is not yours");
        }
        Staff member = staffRepo.get(appointment.staffId());
        BusinessSettings config = settings.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appointment", JsonMapper.toMap(appointment));
        out.put("summary", summary(appointment));
        out.put("customer", customer == null ? null : Util.map("id", customer.id(), "name", customer.fullName(),
                "phone", customer.phone(), "email", customer.email(), "tier", customer.tier(),
                "allergies", customer.allergies(), "notes", customer.notes(), "visits", customer.visits()));
        out.put("staff", member == null ? null : Util.map("id", member.id(), "name", member.name(), "title", member.title()));
        out.put("items", Util.nullSafe(appointment.items()).stream().map(i -> {
            ServiceItem service = services.get(i.serviceId());
            return Util.map("serviceId", i.serviceId(), "name", i.name(), "variantId", i.variantId(),
                    "minutes", i.durationMinutes(), "price", i.price(),
                    "aftercare", service == null ? null : service.aftercareNotes(),
                    "prep", service == null ? null : service.prepNotes());
        }).toList());
        out.put("transitions", nextStates(appointment.status()).stream().map(AppointmentStatus::name).toList());
        out.put("cancellationPolicyHours", config.cancellationHours());
        out.put("audit", audit.forEntity("appointment", id).stream().limit(20).toList());
        out.put("history", customer == null ? List.of() : forCustomer(customer.id()).stream().limit(10)
                .map(this::summary).toList());
        return out;
    }

    /** Bookings whose reminder has not gone out and which start tomorrow. */
    public List<Appointment> dueForReminders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        return appointments.where(a -> a.status() == AppointmentStatus.CONFIRMED
                && a.start() != null && a.start().toLocalDate().equals(tomorrow)
                && a.reminderSentAt() == null);
    }

    public List<Appointment> overdueNoShows() {
        int grace = settings.current().graceMinutes();
        return appointments.where(a -> (a.status() == AppointmentStatus.CONFIRMED || a.status() == AppointmentStatus.REQUESTED)
                && a.start() != null && a.start().plusMinutes(grace + 60L).isBefore(LocalDateTime.now()));
    }

    public Map<String, Object> stats() {
        List<Appointment> all = appointments.all();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long thisMonth = all.stream().filter(a -> a.createdAt() != null && a.createdAt().isAfter(monthStart)).count();
        long completed = all.stream().filter(a -> a.status() == AppointmentStatus.COMPLETED).count();
        long noShows = all.stream().filter(a -> a.status() == AppointmentStatus.NO_SHOW).count();
        long cancelled = all.stream().filter(a -> a.status() == AppointmentStatus.CANCELLED).count();
        long today = all.stream().filter(a -> a.start() != null && a.start().toLocalDate().equals(LocalDate.now())
                && a.status().occupiesChair()).count();
        return Util.map("total", all.size(), "today", today, "thisMonth", thisMonth, "completed", completed,
                "cancelled", cancelled, "noShows", noShows,
                "noShowRate", completed + noShows == 0 ? 0 : Util.percent(noShows, completed + noShows),
                "upcoming", all.stream().filter(a -> !a.status().isClosed() && a.start() != null
                        && a.start().isAfter(LocalDateTime.now())).count());
    }

    private void notifyBooking(Appointment appointment, Customer customer, Staff member, String template) {
        try {
            String serviceName = Util.join(appointment.serviceNames(), ", ");
            notifications.queueForAppointment(template, appointment, customer, serviceName,
                    member == null ? null : member.name(), Notification.SMS, "booking");
        } catch (RuntimeException e) {
            Util.nz(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static LocalTime parseTime(String value) {
        if (value == null) return null;
        try {
            return LocalTime.parse(value.length() == 5 ? value + ":00" : value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String format(LocalTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static LocalTime earlier(LocalTime a, LocalTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static LocalTime later(LocalTime a, LocalTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    /** Accessible booking list for a client's own portal. */
    public List<Map<String, Object>> myBookings(String customerId, int limit) {
        List<Appointment> mine = appointments.where(a -> customerId.equals(a.customerId()));
        mine.sort(Comparator.comparing(Appointment::start, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Appointment appointment : mine) {
            if (out.size() >= limit) break;
            out.add(summary(appointment));
        }
        return out;
    }
}
