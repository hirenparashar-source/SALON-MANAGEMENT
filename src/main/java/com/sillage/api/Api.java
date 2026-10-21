package com.sillage.api;

import com.sillage.Salon;
import com.sillage.core.ApiException;
import com.sillage.core.Json;
import com.sillage.core.JsonMapper;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.model.Actor;
import com.sillage.model.Appointment;
import com.sillage.model.AppointmentStatus;
import com.sillage.model.BusinessSettings;
import com.sillage.model.Customer;
import com.sillage.model.Invoice;
import com.sillage.model.InvoiceStatus;
import com.sillage.model.PaymentMethod;
import com.sillage.model.Product;
import com.sillage.model.Role;
import com.sillage.model.Suggestion;
import com.sillage.search.SearchQuery;
import com.sillage.search.SearchResult;
import com.sillage.web.Request;
import com.sillage.web.Response;
import com.sillage.web.Router;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The HTTP API: one flat routing table, every handler a few lines that delegate to a service.
 *
 * <p>Public endpoints are readable without an account. Anything that touches money, personal data
 * or configuration asks for a role through {@link Actor#require}. Search narrows itself to public
 * kinds for guests, so a visitor can never enumerate the client book.
 */
public final class Api {

    private static final Set<String> PUBLIC_KINDS = Set.of("service", "staff", "look", "product");

    private Api() {}

    public static Router routes(Salon salon) {
        Router router = new Router();
        meta(router, salon);
        auth(router, salon);
        catalogue(router, salon);
        booking(router, salon);
        billing(router, salon);
        receipts(router, salon);
        clients(router, salon);
        inventory(router, salon);
        suggestions(router, salon);
        analytics(router, salon);
        search(router, salon);
        loyalty(router, salon);
        settings(router, salon);
        notifications(router, salon);
        audit(router, salon);
        data(router, salon);
        return router;
    }

    private static Actor actor(Request request, Salon salon) {
        return Support.actor(request, salon.auth);
    }

    // ================================================================== meta

    private static void meta(Router router, Salon salon) {
        router.get("/api/health", request -> Response.json(salon.health()));

        router.get("/api/bootstrap", request -> {
            BusinessSettings config = salon.settingsService.current();
            Actor actor = actor(request, salon);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("salon", salon.settingsService.publicView());
            out.put("menu", salon.catalogue.menu(!actor.isStaff()));
            out.put("staff", salon.staffService.active().stream().map(s -> Util.map(
                    "id", s.id(), "name", s.name(), "title", s.title(), "quote", s.quote(),
                    "colorKey", s.colorKey(), "rating", s.rating(), "years", s.yearsExperience(),
                    "specialties", s.specialties(), "acceptsWalkIns", s.acceptsWalkIns())).toList());
            out.put("looks", salon.catalogue.publicLooks(12));
            out.put("testimonials", salon.catalogue.quotes(true, 8).stream().map(JsonMapper::toMap).toList());
            out.put("loyalty", salon.settingsService.loyaltyView(config));
            out.put("payments", salon.settingsService.paymentMethods(config));
            out.put("open", salon.settingsService.openNowStatus(config));
            out.put("session", salon.auth.me(request.session(), request.account()));
            out.put("features", config.features());
            out.put("gateway", Util.map("name", salon.gateway.name(), "testMode", salon.gateway.testMode(),
                    "methods", PaymentGatewayMethods(salon)));
            out.put("engine", com.sillage.service.SuggestionEngine.ENGINE);
            out.put("version", com.sillage.SillageApp.VERSION);
            out.put("bootedAt", LocalDateTime.now().toString());
            return Response.json(out);
        });

        router.get("/api/meta/routes", request -> {
            Support.staff(request, salon.auth);
            return Support.list("routes", salon.router.inventory());
        });

        router.get("/api/meta/engine", request -> Response.json(salon.suggestions.explain()));
    }

    private static List<String> PaymentGatewayMethods(Salon salon) {
        return com.sillage.service.PaymentGateway.supportedMethods();
    }

    // ================================================================== auth

    private static void auth(Router router, Salon salon) {
        router.post("/api/auth/register", request -> {
            Map<String, Object> body = request.json();
            Map<String, Object> out = salon.auth.register(body, request.ip(), request.userAgent());
            return Response.json(out, 201).cookie(String.valueOf(out.get("cookie"))).cookie(String.valueOf(out.get("csrf")));
        });

        router.post("/api/auth/login", request -> {
            Map<String, Object> out = salon.auth.login(request.json(), request.ip(), request.userAgent());
            return Response.json(out).cookie(String.valueOf(out.get("cookie"))).cookie(String.valueOf(out.get("csrf")));
        });

        router.post("/api/auth/guest", request -> {
            Map<String, Object> out = salon.auth.guest(request.ip(), request.userAgent(),
                    Support.bodyText(request.json(), "customerId"));
            return Response.json(out).cookie(String.valueOf(out.get("cookie"))).cookie(String.valueOf(out.get("csrf")));
        });

        router.post("/api/auth/logout", request -> {
            salon.auth.logout(request.session(), request.ip());
            return Response.json(Map.of("loggedOut", true))
                    .cookie(salon.auth.clearCookieHeader(salon.config.secureCookies()));
        });

        router.get("/api/auth/me", request -> Response.json(salon.auth.me(request.session(), request.account())));

        router.post("/api/auth/password", request -> {
            Actor actor = Support.staffOrClient(request, salon.auth);
            Map<String, Object> body = request.json();
            return Response.json(salon.auth.changePassword(actor.id(), Support.bodyText(body, "current"),
                    Support.bodyText(body, "next"), actor));
        });

        router.post("/api/auth/password/reset", request -> Response.json(
                salon.auth.requestPasswordReset(Support.bodyText(request.json(), "identifier"), request.ip())));

        router.post("/api/auth/password/confirm", request -> {
            Map<String, Object> body = request.json();
            return Response.json(salon.auth.confirmPasswordReset(Support.bodyText(body, "token"),
                    Support.bodyText(body, "password"), request.ip()));
        });

        router.get("/api/auth/policy", request -> Response.json(salon.auth.passwordPolicy()));

        router.get("/api/auth/sessions", request -> {
            Actor actor = actor(request, salon);
            if (!actor.authenticated()) throw ApiException.unauthorized("Sign in to list your sessions");
            return Support.list("sessions", salon.auth.sessionsOf(actor.id(), request.session()));
        });

        router.delete("/api/auth/sessions/:id", request -> {
            Actor actor = actor(request, salon);
            if (!actor.authenticated()) throw ApiException.unauthorized("Sign in first");
            salon.auth.revokeSession(request.pathParam("id"), actor);
            return Support.message("That session was signed out");
        });

        router.post("/api/auth/sessions/revoke-all", request -> {
            Actor actor = actor(request, salon);
            if (!actor.authenticated()) throw ApiException.unauthorized("Sign in first");
            return Support.message("Signed out everywhere",
                    Map.of("revoked", salon.auth.revokeEverywhere(actor.id(), actor)));
        });

        router.post("/api/auth/link-customer", request -> {
            Actor actor = actor(request, salon);
            if (!actor.authenticated()) throw ApiException.unauthorized("Sign in first");
            Map<String, Object> body = request.json();
            return Response.json(salon.auth.linkCustomer(actor.id(), Support.bodyText(body, "phone"),
                    Support.bodyText(body, "code"), actor));
        });

        router.get("/api/auth/stats", request -> {
            Support.manager(request, salon.auth);
            return Response.json(salon.auth.stats());
        });

        router.get("/api/accounts", request -> Support.list("accounts", salon.auth.list(Support.manager(request, salon.auth))));

        router.post("/api/accounts/staff", request -> {
            Actor actor = Support.manager(request, salon.auth);
            return Response.json(salon.auth.createStaffAccount(request.json(), actor), 201);
        });

        router.patch("/api/accounts/:id/role", request -> {
            Actor actor = Support.owner(request, salon.auth);
            Role role = Role.parse(Support.bodyText(request.json(), "role"));
            return Response.json(salon.auth.view(salon.auth.setRole(request.pathParam("id"), role, actor)));
        });

        router.patch("/api/accounts/:id/active", request -> {
            Actor actor = Support.owner(request, salon.auth);
            boolean active = JsonMapper.asBoolean(request.json().get("active"));
            return Response.json(salon.auth.view(salon.auth.setActive(request.pathParam("id"), active, actor)));
        });
    }

    // ================================================================== catalogue

    private static void catalogue(Router router, Salon salon) {
        router.get("/api/catalogue", request -> Response.json(salon.catalogue.menu(!actor(request, salon).isStaff())));

        router.get("/api/services", request -> {
            boolean publicOnly = !actor(request, salon).isStaff();
            List<Map<String, Object>> out = new ArrayList<>();
            for (var service : salon.catalogue.allServices()) {
                if (publicOnly && (!service.active() || !service.onlineBookable())) continue;
                out.add(salon.catalogue.publicService(service));
            }
            return Support.list("services", out);
        });

        router.get("/api/services/:id", request -> Response.json(salon.catalogue.detail(request.pathParam("id"))));

        router.post("/api/services", request -> Response.json(
                JsonMapper.toMap(salon.catalogue.createService(request.json(), Support.staff(request, salon.auth))), 201));

        router.patch("/api/services/:id", request -> Response.json(JsonMapper.toMap(
                salon.catalogue.updateService(request.pathParam("id"), request.json(), Support.staff(request, salon.auth)))));

        router.delete("/api/services/:id", request -> {
            salon.catalogue.deleteService(request.pathParam("id"), Support.manager(request, salon.auth));
            return Support.message("Service removed from the menu");
        });

        router.patch("/api/services/:id/featured", request -> {
            boolean featured = JsonMapper.asBoolean(request.json().get("featured"));
            return Response.json(JsonMapper.toMap(salon.catalogue.setFeatured(request.pathParam("id"), featured,
                    Support.staff(request, salon.auth))));
        });

        router.get("/api/categories", request -> Support.list("categories",
                salon.catalogue.allCategories().stream().map(JsonMapper::toMap).toList()));

        router.post("/api/categories", request -> Response.json(JsonMapper.toMap(
                salon.catalogue.createCategory(request.json(), Support.staff(request, salon.auth))), 201));

        router.patch("/api/categories/:id", request -> Response.json(JsonMapper.toMap(
                salon.catalogue.updateCategory(request.pathParam("id"), request.json(), Support.staff(request, salon.auth)))));

        router.delete("/api/categories/:id", request -> {
            salon.catalogue.deleteCategory(request.pathParam("id"), Support.manager(request, salon.auth));
            return Support.message("Category removed");
        });

        router.get("/api/looks", request -> {
            boolean staffView = actor(request, salon).isStaff();
            return Support.list("looks", staffView
                    ? salon.looks.all().stream().map(JsonMapper::toMap).toList()
                    : salon.catalogue.publicLooks(Support.limit(request, "limit", 24, 100)));
        });

        router.post("/api/looks", request -> Response.json(JsonMapper.toMap(
                salon.catalogue.createLook(request.json(), Support.staff(request, salon.auth))), 201));

        router.patch("/api/looks/:id", request -> Response.json(JsonMapper.toMap(
                salon.catalogue.updateLook(request.pathParam("id"), request.json(), Support.staff(request, salon.auth)))));

        router.delete("/api/looks/:id", request -> {
            salon.catalogue.deleteLook(request.pathParam("id"), Support.staff(request, salon.auth));
            return Support.message("Look removed");
        });

        router.get("/api/testimonials", request -> Support.list("testimonials",
                salon.catalogue.quotes(!actor(request, salon).isStaff(), Support.limit(request, "limit", 20, 100))
                        .stream().map(JsonMapper::toMap).toList()));

        router.post("/api/testimonials", request -> {
            Actor actor = actor(request, salon);
            return Response.json(JsonMapper.toMap(salon.catalogue.createTestimonial(request.json(), actor)), 201);
        });

        router.patch("/api/testimonials/:id/moderate", request -> {
            Actor actor = Support.staff(request, salon.auth);
            Map<String, Object> body = request.json();
            return Response.json(JsonMapper.toMap(salon.catalogue.moderateTestimonial(request.pathParam("id"),
                    JsonMapper.asBoolean(body.get("approved")), JsonMapper.asBoolean(body.get("featured")), actor)));
        });

        router.delete("/api/testimonials/:id", request -> {
            salon.catalogue.deleteTestimonial(request.pathParam("id"), Support.staff(request, salon.auth));
            return Support.message("Testimonial removed");
        });

        router.get("/api/staff", request -> {
            boolean publicOnly = !actor(request, salon).isStaff();
            List<Map<String, Object>> out = new ArrayList<>();
            for (var member : publicOnly ? salon.staffService.active() : salon.staffService.all()) {
                out.add(Util.map("id", member.id(), "name", member.name(), "title", member.title(),
                        "bio", member.bio(), "quote", member.quote(), "specialties", member.specialties(),
                        "serviceIds", member.serviceIds(), "colorKey", member.colorKey(), "rating", member.rating(),
                        "years", member.yearsExperience(), "acceptsWalkIns", member.acceptsWalkIns(),
                        "active", member.active(), "schedule", member.schedule(),
                        "phone", publicOnly ? null : member.phone(),
                        "email", publicOnly ? null : member.email()));
            }
            return Support.list("staff", out);
        });

        router.get("/api/staff/:id", request -> Response.json(salon.staffService.profile(request.pathParam("id"))));

        router.post("/api/staff", request -> Response.json(JsonMapper.toMap(
                salon.staffService.create(request.json(), Support.manager(request, salon.auth))), 201));

        router.patch("/api/staff/:id", request -> Response.json(JsonMapper.toMap(
                salon.staffService.update(request.pathParam("id"), request.json(), Support.manager(request, salon.auth)))));

        router.delete("/api/staff/:id", request -> {
            salon.staffService.delete(request.pathParam("id"), Support.owner(request, salon.auth));
            return Support.message("Stylist removed");
        });

        router.post("/api/staff/:id/deactivate", request -> Response.json(JsonMapper.toMap(
                salon.staffService.deactivate(request.pathParam("id"), Support.manager(request, salon.auth)))));

        router.post("/api/staff/:id/time-off", request -> {
            Actor actor = Support.manager(request, salon.auth);
            Map<String, Object> body = request.json();
            LocalDate date = JsonMapper.asDate(body.get("date"));
            if (date == null) throw ApiException.badRequest("A date is required");
            return Response.json(JsonMapper.toMap(salon.staffService.addTimeOff(request.pathParam("id"), date,
                    Support.bodyText(body, "reason"), actor)));
        });

        router.delete("/api/staff/:id/time-off/:offId", request -> Response.json(JsonMapper.toMap(
                salon.staffService.removeTimeOff(request.pathParam("id"), request.pathParam("offId"),
                        Support.manager(request, salon.auth)))));

        router.get("/api/staff/:id/week", request -> Support.list("days",
                salon.staffService.weekView(salon.staffService.get(request.pathParam("id")))));

        router.get("/api/catalogue/stats", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.catalogue.stats());
        });
    }

    // ================================================================== booking

    private static void booking(Router router, Salon salon) {
        router.get("/api/availability", request -> Response.json(flattenAvailability(salon.booking.availability(
                request.queryString("serviceId"), request.queryString("variantId"), request.queryString("staffId"),
                Support.date(request, "date", LocalDate.now()), Support.limit(request, "days", 7, 30)))));

        // --- public booking management: proved by the booking code and the phone it was made with
        router.get("/api/appointments/lookup", request -> {
            String code = Util.nz(request.queryString("code")).trim();
            Appointment appointment = salon.booking.byCode(code);
            if (appointment == null) throw ApiException.notFound("No booking under " + (Util.blank(code) ? "that code" : code));
            proveOwnership(salon, appointment, request.queryString("phone"), null);
            return Response.json(Util.map("appointment", salon.booking.summary(appointment),
                    "message", "Found it. Cancelling is free up to six hours before your chair time."));
        });

        router.post("/api/appointments/:id/deposit", request -> {
            Map<String, Object> body = request.json();
            Appointment appointment = salon.booking.find(request.pathParam("id"));
            if (appointment == null) throw ApiException.notFound("Booking " + Util.nz(request.pathParam("id")));
            proveOwnership(salon, appointment, Support.bodyText(body, "phone"), Support.bodyText(body, "code"));
            Invoice invoice = Util.present(appointment.invoiceId())
                    ? salon.invoicing.find(appointment.invoiceId())
                    : salon.invoicing.createFromAppointment(appointment.id(), Actor.system());
            if (invoice == null) throw ApiException.notFound("The bill for that booking");
            Map<String, Object> result = salon.invoicing.pay(invoice.id(), body, Actor.system());
            result.put("appointment", salon.booking.summary(salon.booking.get(appointment.id())));
            return Response.json(result);
        });

        router.post("/api/appointments/:id/release", request -> {
            Map<String, Object> body = request.json();
            Appointment appointment = salon.booking.find(request.pathParam("id"));
            if (appointment == null) throw ApiException.notFound("Booking " + Util.nz(request.pathParam("id")));
            proveOwnership(salon, appointment, Support.bodyText(body, "phone"), Support.bodyText(body, "code"));
            Appointment cancelled = salon.booking.cancel(appointment.id(),
                    Util.nz(Support.bodyText(body, "reason"), "Cancelled online by the guest"), Actor.system());
            return Response.json(Util.map("cancelled", true, "appointment", salon.booking.summary(cancelled),
                    "message", "Cancelled. The chair has been released back to the book."));
        });

        router.get("/api/slots", request -> Support.list("slots", salon.booking.slots(
                request.queryString("serviceId"), request.queryString("variantId"), request.queryString("staffId"),
                Support.date(request, "date", LocalDate.now()), request.queryBool("includeUnavailable", false))
                .stream().map(s -> Util.map("start", s.start().toString(), "end", s.end().toString(),
                        "staffId", s.staffId(), "staffName", s.staffName(), "available", s.available(),
                        "reason", s.reason(), "minutes", s.durationMinutes(),
                        "label", Util.formatTime(s.start()))).toList()));

        router.post("/api/appointments", request -> {
            Actor actor = actor(request, salon);
            Appointment appointment = salon.booking.book(request.json(), actor);
            return Response.json(salon.booking.detail(appointment.id(), actor), 201);
        });

        router.get("/api/appointments", request -> {
            Actor actor = Support.staff(request, salon.auth);
            LocalDate from = Support.date(request, "from", LocalDate.now().minusDays(7));
            LocalDate to = Support.date(request, "to", LocalDate.now().plusDays(30));
            List<Appointment> found = salon.booking.range(from, to, request.queryString("staffId"),
                    request.queryString("status"));
            List<Map<String, Object>> out = found.stream().map(salon.booking::summary).toList();
            return Support.envelope("appointments", out, Util.map("count", out.size(),
                    "from", from.toString(), "to", to.toString(), "actor", actor.label()));
        });

        router.get("/api/appointments/mine", request -> {
            Actor actor = actor(request, salon);
            if (Util.blank(actor.customerId())) {
                return Support.list("appointments", List.of());
            }
            return Support.list("appointments", salon.booking.myBookings(actor.customerId(),
                    Support.limit(request, "limit", 20, 100)));
        });

        router.get("/api/appointments/:id", request -> Response.json(
                salon.booking.detail(request.pathParam("id"), actor(request, salon))));

        router.patch("/api/appointments/:id/status", request -> {
            Actor actor = Support.staff(request, salon.auth);
            Map<String, Object> body = request.json();
            AppointmentStatus status = AppointmentStatus.parse(Support.bodyText(body, "status"));
            return Response.json(salon.booking.detail(salon.booking.setStatus(request.pathParam("id"), status,
                    Support.bodyText(body, "note"), actor).id(), actor));
        });

        router.post("/api/appointments/:id/reschedule", request -> {
            Actor actor = actor(request, salon);
            Map<String, Object> body = request.json();
            LocalDateTime start = JsonMapper.asDateTime(body.get("start"));
            if (start == null) throw ApiException.badRequest("A new start time is required");
            return Response.json(salon.booking.detail(salon.booking.reschedule(request.pathParam("id"), start,
                    Support.bodyText(body, "staffId"), actor).id(), actor));
        });

        router.post("/api/appointments/:id/cancel", request -> {
            Actor actor = actor(request, salon);
            return Response.json(salon.booking.detail(salon.booking.cancel(request.pathParam("id"),
                    Support.bodyText(request.json(), "reason"), actor).id(), actor));
        });

        router.post("/api/appointments/:id/rate", request -> {
            Actor actor = actor(request, salon);
            Map<String, Object> body = request.json();
            int rating = Util.clamp(JsonMapper.asInt(body.get("rating"), 5), 1, 5);
            return Response.json(salon.booking.detail(salon.booking.rate(request.pathParam("id"), rating,
                    Support.bodyText(body, "comment"), actor).id(), actor));
        });

        router.get("/api/diary", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.booking.dayBoard(Support.date(request, "date", LocalDate.now())));
        });

        router.get("/api/booking/stats", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.booking.stats());
        });

        router.post("/api/booking/reminders", request -> {
            Support.staff(request, salon.auth);
            List<Appointment> due = salon.booking.dueForReminders();
            return Response.json(salon.notifications.sendDueReminders(due,
                    salon.booking::customerOf,
                    a -> Util.join(a.serviceNames(), ", "),
                    a -> salon.staffService.name(a.staffId())));
        });

        router.get("/api/booking/transitions/:status", request -> Support.list("transitions",
                BookingServiceTransitions(AppointmentStatus.parse(request.pathParam("status")))));
    }

    /**
     * The public booking flow wants one flat list of slots per day; the service groups them by chair
     * so the desk can see who is free. Flatten it here rather than change the service.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> flattenAvailability(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>(raw);
        List<Map<String, Object>> flat = new ArrayList<>();
        for (Object rawDay : (List<Object>) raw.getOrDefault("days", List.of())) {
            Map<String, Object> day = (Map<String, Object>) rawDay;
            List<Map<String, Object>> slots = new ArrayList<>();
            for (Object rawChair : (List<Object>) day.getOrDefault("byStaff", List.of())) {
                Map<String, Object> chair = (Map<String, Object>) rawChair;
                for (Object rawSlot : (List<Object>) chair.getOrDefault("slots", List.of())) {
                    Map<String, Object> slot = new LinkedHashMap<>((Map<String, Object>) rawSlot);
                    slot.put("staffId", chair.get("staffId"));
                    slot.put("staffName", chair.get("staffName"));
                    slot.put("available", true);
                    slot.put("label", slot.get("time"));
                    slots.add(slot);
                }
            }
            slots.sort((a, b) -> String.valueOf(a.get("start")).compareTo(String.valueOf(b.get("start"))));
            boolean closed = Boolean.TRUE.equals(day.get("closed"));
            String holiday = day.get("holiday") == null ? null : String.valueOf(day.get("holiday"));
            Map<String, Object> flatDay = new LinkedHashMap<>();
            flatDay.put("date", day.get("date"));
            flatDay.put("dayLabel", day.get("label"));
            flatDay.put("weekday", day.get("weekday"));
            flatDay.put("open", !closed);
            flatDay.put("available", slots.size());
            flatDay.put("reason", day.get("reason") != null && !String.valueOf(day.get("reason")).isBlank()
                    ? String.valueOf(day.get("reason"))
                    : (holiday != null && !holiday.isBlank() ? holiday
                    : (slots.isEmpty() ? "Fully booked" : null)));
            flatDay.put("first", day.get("first"));
            flatDay.put("last", day.get("last"));
            flatDay.put("slots", slots);
            flat.add(flatDay);
        }
        out.put("days", flat);
        out.put("note", "Slots are derived from the live diary each time you ask \u2014 nothing is cached.");
        return out;
    }

    /** A guest proves ownership of a booking with its code and the phone number it was made with. */
    private static void proveOwnership(Salon salon, Appointment appointment, String phone, String code) {
        if (Util.present(code) && !Util.constantTimeEquals(Util.nz(appointment.code()), code.trim())) {
            throw ApiException.forbidden("That reference does not match this booking");
        }
        String onRecord = appointment.guestPhone();
        if (Util.blank(onRecord) && Util.present(appointment.customerId())) {
            Customer customer = salon.customers.get(appointment.customerId());
            onRecord = customer == null ? null : customer.phone();
        }
        if (Util.blank(phone) || Util.blank(onRecord)) throw ApiException.forbidden("The mobile number is required");
        String a = com.sillage.service.CustomerService.phoneKey(phone);
        String b = com.sillage.service.CustomerService.phoneKey(onRecord);
        if (!Util.constantTimeEquals(a, b)) throw ApiException.forbidden("That mobile number does not match this booking");
    }

    private static List<String> BookingServiceTransitions(AppointmentStatus status) {
        return com.sillage.service.BookingService.nextStates(status).stream().map(Enum::name).toList();
    }

    // ================================================================== billing

    private static void billing(Router router, Salon salon) {
        router.post("/api/invoices/quote", request -> Response.json(
                salon.invoicing.quote(request.json(), actor(request, salon))));

        router.post("/api/invoices", request -> {
            Actor actor = actor(request, salon);
            Invoice invoice = salon.invoicing.create(request.json(), actor);
            return Response.json(salon.invoicing.view(invoice), 201);
        });

        router.post("/api/invoices/from-appointment/:appointmentId", request -> {
            Actor actor = Support.staff(request, salon.auth);
            return Response.json(salon.invoicing.view(
                    salon.invoicing.createFromAppointment(request.pathParam("appointmentId"), actor)), 201);
        });

        router.get("/api/invoices", request -> {
            Support.staff(request, salon.auth);
            InvoiceStatus status = Util.blank(request.queryString("status")) ? null
                    : InvoiceStatus.parse(request.queryString("status"));
            List<Invoice> found = salon.invoicing.list(status,
                    request.queryString("from") == null ? null : LocalDate.parse(request.queryString("from")),
                    request.queryString("to") == null ? null : LocalDate.parse(request.queryString("to")),
                    request.queryString("customerId"), Support.limit(request, "limit", 60, 500));
            return Support.envelope("invoices", found.stream().map(salon.invoicing::view).toList(),
                    Util.map("count", found.size(), "stats", salon.invoicing.stats()));
        });

        router.get("/api/invoices/:id", request -> {
            Actor actor = actor(request, salon);
            Invoice invoice = salon.invoicing.get(request.pathParam("id"));
            if (!actor.isStaff() && !invoiceBelongsTo(invoice, actor)) throw ApiException.forbidden("Not your invoice");
            return Response.json(salon.invoicing.view(invoice));
        });

        router.patch("/api/invoices/:id/notes", request -> Response.json(salon.invoicing.view(
                salon.invoicing.updateNotes(request.pathParam("id"), Support.bodyText(request.json(), "notes"),
                        Support.staff(request, salon.auth)))));

        router.post("/api/invoices/:id/void", request -> Response.json(salon.invoicing.view(
                salon.invoicing.voidInvoice(request.pathParam("id"), Support.bodyText(request.json(), "reason"),
                        Support.owner(request, salon.auth)))));

        router.post("/api/payments", request -> {
            Actor actor = actor(request, salon);
            Map<String, Object> body = request.json();
            String invoiceId = Support.bodyText(body, "invoiceId");
            if (Util.blank(invoiceId)) invoiceId = request.pathParam("invoiceId");
            if (Util.blank(invoiceId)) throw ApiException.badRequest("An invoiceId is required");
            Invoice invoice = salon.invoicing.get(invoiceId);
            if (!actor.isStaff() && !invoiceBelongsTo(invoice, actor)) throw ApiException.forbidden("Not your invoice");
            return Response.json(salon.invoicing.pay(invoiceId, body, actor));
        });

        router.post("/api/payments/intent", request -> {
            Actor actor = actor(request, salon);
            Map<String, Object> body = request.json();
            BigDecimal amount = Support.bodyAmount(body, "amount", BigDecimal.ZERO);
            if (amount.signum() <= 0) throw ApiException.badRequest("An amount greater than zero is required");
            PaymentMethod method = PaymentMethod.parse(Support.bodyText(body, "method"));
            return Response.json(JsonMapper.toMap(salon.gateway.createIntent(amount, method,
                    Support.bodyText(body, "invoiceId"), Support.bodyText(body, "invoiceNumber"),
                    Support.bodyText(body, "appointmentId"), Support.bodyText(body, "customerId"),
                    Support.bodyText(body, "idempotencyKey"), request.ip())));
        });

        router.get("/api/payments/intent/:id", request -> Response.json(
                JsonMapper.toMap(salon.gateway.intent(request.pathParam("id")))));

        router.post("/api/payments/intent/:id/cancel", request -> {
            salon.gateway.cancelIntent(request.pathParam("id"));
            return Support.message("Intent cancelled");
        });

        router.get("/api/payments/methods", request -> {
            BusinessSettings config = salon.settingsService.current();
            return Support.list("methods", salon.settingsService.paymentMethods(config));
        });

        router.post("/api/payments/:invoiceId/refund", request -> {
            Actor actor = Support.manager(request, salon.auth);
            Map<String, Object> body = request.json();
            BigDecimal amount = Support.bodyAmount(body, "amount", null);
            return Response.json(salon.invoicing.refund(request.pathParam("invoiceId"),
                    Support.bodyText(body, "paymentId"), amount, Support.bodyText(body, "reason"), actor));
        });

        router.get("/api/gateway/console", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.gateway.console());
        });

        router.get("/api/gateway/test-cards", request -> Support.list("tenders", salon.gateway.testCards()));

        router.get("/api/gateway/events", request -> {
            Support.staff(request, salon.auth);
            return Support.list("events", salon.gateway.events(Support.limit(request, "limit", 40, 120)));
        });

        router.get("/api/gateway/intents", request -> {
            Support.staff(request, salon.auth);
            return Support.list("intents", salon.gateway.recentIntents(Support.limit(request, "limit", 30, 200)));
        });

        router.get("/api/day-close", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.invoicing.dailySummary(Support.date(request, "date", LocalDate.now())));
        });

        router.get("/api/gift-cards", request -> {
            Support.staff(request, salon.auth);
            return Support.list("giftCards", salon.invoicing.giftCards(Support.limit(request, "limit", 50, 500))
                    .stream().map(JsonMapper::toMap).toList());
        });

        router.post("/api/gift-cards", request -> Response.json(JsonMapper.toMap(
                salon.invoicing.issueGiftCard(request.json(), Support.staff(request, salon.auth))), 201));

        router.get("/api/gift-cards/:code", request -> Response.json(salon.invoicing.giftCardStatus(request.pathParam("code"))));
    }

    private static boolean invoiceBelongsTo(Invoice invoice, Actor actor) {
        if (invoice == null) return false;
        if (Util.present(actor.customerId()) && actor.customerId().equals(invoice.customerId())) return true;
        return actor.isStaff();
    }

    // ================================================================== receipts

    private static void receipts(Router router, Salon salon) {
        router.get("/api/receipts/verify", request -> {
            String number = request.queryString("number");
            String code = request.queryString("code");
            Invoice invoice = Util.blank(number) ? null : salon.invoicing.byNumber(number);
            boolean ok = invoice != null && salon.invoicing.verifyReceipt(number, code);
            return Response.json(Util.map("verified", ok, "number", Util.nz(number),
                    "total", invoice == null ? null : invoice.total(),
                    "status", invoice == null ? null : invoice.status().label(),
                    "customer", invoice == null ? null : invoice.customerName(),
                    "message", ok ? "This receipt matches the salon's records." : "Not found or seal mismatch"));
        });

        router.get("/api/receipts/:id", request -> Response.json(salon.invoicing.view(loadReceipt(salon, request))));

        router.get("/api/receipts/:id/text", request -> {
            Invoice invoice = loadReceipt(salon, request);
            salon.invoicing.markPrinted(invoice.id());
            if (request.queryBool("download", false)) {
                return Support.download(salon.invoicing.receiptText(invoice),
                        invoice.number().replace('/', '-') + ".txt", "text/plain; charset=utf-8");
            }
            return Support.plainText(salon.invoicing.receiptText(invoice));
        });

        router.get("/api/receipts/:id/print", request -> {
            Invoice invoice = loadReceipt(salon, request);
            salon.invoicing.markPrinted(invoice.id());
            return Support.html(ReceiptPage.render(invoice, salon));
        });

        router.get("/api/receipts/:id/csv", request -> {
            Support.staff(request, salon.auth);
            Invoice invoice = loadReceipt(salon, request);
            return Support.csv(salon.invoicing.toCsv(List.of(invoice)),
                    invoice.number().replace('/', '-') + ".csv");
        });

        router.get("/api/receipts/:id/verify", request -> {
            Invoice invoice = loadReceipt(salon, request);
            boolean ok = salon.invoicing.verifyReceipt(invoice.number(), request.queryString("code"));
            return Response.json(Util.map("verified", ok, "number", invoice.number(),
                    "total", invoice.total(), "status", invoice.status().label(),
                    "issuedAt", invoice.createdAt() == null ? null : invoice.createdAt().toString(),
                    "customer", invoice.customerName(),
                    "message", ok ? "This receipt matches the salon's records."
                            : "That seal does not match \u2014 check the code on the receipt."));
        });

        router.get("/api/receipts/:id/lines.csv", request -> {
            Support.staff(request, salon.auth);
            Invoice invoice = loadReceipt(salon, request);
            List<List<String>> rows = new ArrayList<>();
            for (Invoice.Line line : Util.nullSafe(invoice.lines())) {
                rows.add(List.of(Util.nz(line.kind()), Util.nz(line.name()), Util.nz(line.detail()),
                        String.valueOf(line.qty()), plain(line.unitPrice()), plain(line.taxPct()),
                        plain(line.amount()), Util.nz(line.staffId())));
            }
            return Support.csv(Util.toCsv(List.of("kind", "name", "detail", "qty", "unit_price", "tax_pct",
                    "amount", "staff_id"), rows), invoice.number().replace('/', '-') + "-lines.csv");
        });
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    /** Accepts either the internal id or the printed invoice number. */
    private static Invoice loadReceipt(Salon salon, Request request) {
        String key = request.pathParam("id");
        Invoice invoice = salon.invoicing.find(key);
        if (invoice == null) invoice = salon.invoicing.byNumber(key == null ? null : key.replace('-', '/'));
        if (invoice == null) throw ApiException.notFound("Receipt " + Util.nz(key));
        Actor actor = actor(request, salon);
        if (!actor.isStaff() && !invoiceBelongsTo(invoice, actor)
                && !salon.invoicing.verifyReceipt(invoice.number(), request.queryString("code"))) {
            throw ApiException.forbidden("Add ?code=\u2026 from the printed receipt to open this");
        }
        return invoice;
    }

    // ================================================================== clients

    private static void clients(Router router, Salon salon) {
        router.get("/api/customers", request -> {
            Actor actor = Support.staff(request, salon.auth);
            String query = request.queryString("q");
            List<Customer> found;
            if (Util.present(query)) {
                SearchResult result = salon.search.search(SearchQuery.builder().text(query).kind("customer")
                        .limit(Support.limit(request, "limit", 50, 500)).build());
                List<Customer> matched = new ArrayList<>();
                result.hits().forEach(hit -> {
                    Customer customer = salon.customers.get(hit.id());
                    if (customer != null) matched.add(customer);
                });
                found = matched;
            } else {
                found = salon.customerService.all();
                found.sort((a, b) -> Util.nz(b.fullName()).compareToIgnoreCase(Util.nz(a.fullName())));
                found = Support.slice(found, request.queryInt("offset", 0), Support.limit(request, "limit", 60, 500));
            }
            List<Map<String, Object>> out = found.stream().map(c -> Util.map(
                    "id", c.id(), "code", c.code(), "name", c.fullName(), "firstName", c.firstName(),
                    "phone", c.phone(), "email", c.email(), "tier", c.tier(), "points", c.points(),
                    "visits", c.visits(), "lifetimeValue", c.lifetimeValue(),
                    "lastVisit", c.lastVisitAt() == null ? null : c.lastVisitAt().toString(),
                    "daysSince", c.daysSinceLastVisit(), "rating", c.averageRating(),
                    "tags", c.tags(), "archived", c.archived(), "birthday", c.birthday() == null ? null : c.birthday().toString(),
                    "preferredStaffId", c.preferredStaffId())).toList();
            return Support.envelope("customers", out, Util.map("count", out.size(), "actor", actor.label(),
                    "stats", salon.customerService.stats()));
        });

        router.post("/api/customers", request -> Response.json(JsonMapper.toMap(
                salon.customerService.create(request.json(), Support.staff(request, salon.auth))), 201));

        router.get("/api/customers/duplicates", request -> {
            Support.staff(request, salon.auth);
            return Support.list("duplicates", salon.customerService.duplicates());
        });

        router.get("/api/customers/birthdays", request -> {
            Support.staff(request, salon.auth);
            return Support.list("birthdays", salon.customerService.birthdaysWithin(Support.limit(request, "days", 30, 120)));
        });

        router.get("/api/customers/tags", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.customerService.tags());
        });

        router.post("/api/customers/import", request -> {
            Actor actor = Support.manager(request, salon.auth);
            Map<String, Object> body = request.json();
            String csv = Support.bodyText(body, "csv");
            if (Util.blank(csv)) throw ApiException.badRequest("Paste the CSV into the 'csv' field");
            return Support.message("Import complete", Map.of("imported", salon.customerService.importCsv(csv, actor)));
        });

        router.get("/api/customers/:id", request -> {
            Actor actor = Support.staff(request, salon.auth);
            return Response.json(salon.customerService.profile(request.pathParam("id"), actor));
        });

        router.patch("/api/customers/:id", request -> Response.json(JsonMapper.toMap(
                salon.customerService.update(request.pathParam("id"), request.json(), Support.staff(request, salon.auth)))));

        router.delete("/api/customers/:id", request -> {
            salon.customerService.archive(request.pathParam("id"), Support.staff(request, salon.auth));
            return Support.message("Client archived \u2014 the record stays for the books");
        });

        router.post("/api/customers/:id/restore", request -> Response.json(JsonMapper.toMap(
                salon.customerService.restore(request.pathParam("id"), Support.staff(request, salon.auth)))));

        router.get("/api/customers/:id/export", request -> {
            Actor actor = Support.staff(request, salon.auth);
            return Response.json(salon.customerService.exportSubject(request.pathParam("id"), actor));
        });

        router.post("/api/customers/:id/erase", request -> Response.json(
                salon.data.erasure(request.pathParam("id"), actor(request, salon))));

        router.get("/api/customers/:id/suggestions", request -> {
            Support.staff(request, salon.auth);
            List<Suggestion> found = salon.suggestions.forCustomer(request.pathParam("id"),
                    Support.limit(request, "limit", 6, 20), request.queryBool("includeDismissed", false));
            return Support.list("suggestions", found.stream().map(JsonMapper::toMap).toList());
        });

        router.get("/api/customers/:id/invoices", request -> {
            Support.staff(request, salon.auth);
            return Support.list("invoices", salon.invoicing.forCustomer(request.pathParam("id"))
                    .stream().map(salon.invoicing::view).toList());
        });

        router.get("/api/customers/:id/appointments", request -> {
            Support.staff(request, salon.auth);
            return Support.list("appointments", salon.booking.forCustomer(request.pathParam("id"))
                    .stream().map(salon.booking::summary).toList());
        });

        router.get("/api/profile", request -> {
            Actor actor = actor(request, salon);
            if (!actor.authenticated()) throw ApiException.unauthorized("Sign in to see your profile");
            Map<String, Object> out = salon.auth.me(request.session(), request.account());
            if (Util.present(actor.customerId())) {
                out.put("record", salon.customerService.profile(actor.customerId(), actor));
                out.put("appointments", salon.booking.myBookings(actor.customerId(), 20));
                out.put("invoices", salon.invoicing.forCustomer(actor.customerId()).stream()
                        .map(salon.invoicing::view).toList());
                out.put("loyalty", salon.loyalty.summary(salon.customers.get(actor.customerId())));
            }
            return Response.json(out);
        });

        router.patch("/api/profile", request -> {
            Actor actor = actor(request, salon);
            if (!actor.authenticated()) throw ApiException.unauthorized("Sign in first");
            return Response.json(salon.auth.view(salon.auth.updateProfile(actor.id(), request.json(), actor)));
        });

        router.put("/api/profile/preferences", request -> {
            Actor actor = actor(request, salon);
            if (!actor.authenticated()) throw ApiException.unauthorized("Sign in to save preferences");
            return Response.json(Util.map("preferences",
                    JsonMapper.toMap(salon.auth.updatePreferences(actor.id(), request.json(), actor)).get("preferences"),
                    "saved", true));
        });

        router.get("/api/profile/preferences", request -> {
            Actor actor = actor(request, salon);
            Map<String, Object> current = request.account() != null && request.account().preferences() != null
                    ? request.account().preferences() : salon.auth.defaultPreferences();
            BusinessSettings config = salon.settingsService.current();
            return Response.json(Util.map("preferences", current, "defaults", salon.auth.defaultPreferences(),
                    "accessibility", config.accessibility(), "theme", config.theme(),
                    "note", "Preferences are stored in your browser and, once you sign in, on the server too."));
        });
    }

    // ================================================================== inventory

    private static void inventory(Router router, Salon salon) {
        router.get("/api/products", request -> {
            Actor actor = actor(request, salon);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Product product : salon.inventory.all()) {
                if (!actor.isStaff() && (!product.active() || product.retailOnly() && product.stock() <= 0)) continue;
                out.add(productView(product, actor.isStaff()));
            }
            return Support.envelope("products", out, actor.isStaff()
                    ? Util.map("count", out.size(), "stats", salon.inventory.stats())
                    : Util.map("count", out.size()));
        });

        router.get("/api/products/low", request -> {
            Support.staff(request, salon.auth);
            return Support.list("products", salon.inventory.lowStock().stream()
                    .map(p -> productView(p, true)).toList());
        });

        router.get("/api/inventory/stats", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.inventory.stats());
        });

        router.get("/api/products/:id", request -> {
            Actor actor = actor(request, salon);
            return Response.json(productView(salon.inventory.get(request.pathParam("id")), actor.isStaff()));
        });

        router.post("/api/products", request -> Response.json(productView(
                salon.inventory.create(request.json(), Support.staff(request, salon.auth)), true), 201));

        router.patch("/api/products/:id", request -> Response.json(productView(
                salon.inventory.update(request.pathParam("id"), request.json(), Support.staff(request, salon.auth)), true)));

        router.delete("/api/products/:id", request -> {
            salon.inventory.delete(request.pathParam("id"), Support.manager(request, salon.auth));
            return Support.message("Product removed");
        });

        router.post("/api/products/:id/stock", request -> {
            Actor actor = Support.staff(request, salon.auth);
            Map<String, Object> body = request.json();
            int delta = JsonMapper.asInt(body.get("delta"), 0);
            if (delta == 0) throw ApiException.badRequest("A non-zero delta is required");
            return Response.json(productView(salon.inventory.adjust(request.pathParam("id"), delta,
                    Support.bodyText(body, "reason"), Support.bodyText(body, "refId"),
                    Support.bodyText(body, "note"), actor), true));
        });

        router.post("/api/products/:id/receive", request -> {
            Actor actor = Support.staff(request, salon.auth);
            Map<String, Object> body = request.json();
            int qty = JsonMapper.asInt(body.get("qty"), 0);
            if (qty <= 0) throw ApiException.badRequest("How many are arriving?");
            return Response.json(productView(salon.inventory.receive(request.pathParam("id"), qty,
                    Support.bodyAmount(body, "costPrice", null), Support.bodyText(body, "supplier"), actor), true));
        });

        router.get("/api/products/:id/movements", request -> {
            Support.staff(request, salon.auth);
            return Support.list("movements", salon.inventory.movements(request.pathParam("id"),
                            Support.limit(request, "limit", 40, 400)).stream().map(JsonMapper::toMap).toList());
        });
    }

    private static Map<String, Object> productView(Product product, boolean staffView) {
        Map<String, Object> out = JsonMapper.toMap(product);
        out.put("stockLabel", product.stock() + " " + Util.nz(product.unit(), "unit"));
        out.put("status", product.isOut() ? "out" : product.isLow() ? "low" : "ok");
        out.put("marginPct", product.marginPct());
        if (!staffView) {
            out.remove("costPrice");
            out.remove("supplier");
            out.remove("supplierPhone");
            out.remove("stock");
        }
        return out;
    }

    // ================================================================== suggestions

    private static void suggestions(Router router, Salon salon) {
        router.get("/api/suggestions", request -> {
            Actor actor = Support.staff(request, salon.auth);
            Map<String, Object> feed = salon.suggestions.feed(actor, Support.limit(request, "limit", 12, 40),
                    request.queryBool("includeDismissed", false));
            List<Map<String, Object>> desk = mapSuggestions(feed.get("desk"));
            List<Map<String, Object>> clients = mapSuggestions(feed.get("clients"));
            return Support.envelope("suggestions", desk, Util.map("count", desk.size(),
                    "clients", clients, "engine", feed.get("engine"), "tookMillis", feed.get("tookMillis"),
                    "generatedAt", feed.get("generatedAt")));
        });

        router.get("/api/suggestions/customer/:customerId", request -> {
            Support.staff(request, salon.auth);
            return Support.list("suggestions", salon.suggestions.forCustomer(request.pathParam("customerId"),
                            Support.limit(request, "limit", 6, 20), request.queryBool("includeDismissed", false))
                    .stream().map(JsonMapper::toMap).toList());
        });

        router.get("/api/suggestions/today", request -> {
            Support.staff(request, salon.auth);
            return Support.list("suggestions", salon.suggestions.today(Support.limit(request, "limit", 12, 40))
                    .stream().map(JsonMapper::toMap).toList());
        });

        router.get("/api/suggestions/bundles", request -> {
            Support.staff(request, salon.auth);
            return Support.list("suggestions", salon.suggestions.bundles(Support.limit(request, "limit", 8, 30))
                    .stream().map(JsonMapper::toMap).toList());
        });

        router.get("/api/suggestions/operations", request -> {
            Support.staff(request, salon.auth);
            return Support.list("suggestions", salon.suggestions.operations(Support.limit(request, "limit", 12, 40))
                    .stream().map(JsonMapper::toMap).toList());
        });

        router.post("/api/suggestions/upsell", request -> {
            Map<String, Object> body = request.json();
            List<String> cart = Support.bodyStrings(body, "serviceIds");
            return Support.list("suggestions", salon.suggestions.upsellFor(cart, Support.bodyText(body, "customerId"),
                    Support.limit(request, "limit", 6, 20)).stream().map(JsonMapper::toMap).toList());
        });

        router.get("/api/suggestions/engine", request -> Response.json(salon.suggestions.explain()));

        router.get("/api/suggestions/stats", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.suggestions.stats());
        });

        router.get("/api/suggestions/matrix", request -> {
            Support.staff(request, salon.auth);
            Map<String, Map<String, double[]>> pairs = salon.suggestions.pairStatistics();
            List<Map<String, Object>> out = new ArrayList<>();
            pairs.forEach((from, row) -> row.forEach((to, stats) -> out.add(Util.map(
                    "from", from, "fromName", name(salon, from),
                    "to", to, "toName", name(salon, to), "pairs", (int) stats[0],
                    "support", Math.round(stats[1] * 10000) / 10000.0,
                    "confidence", Math.round(stats[2] * 1000) / 1000.0,
                    "lift", Math.round(stats[3] * 100) / 100.0))));
            out.sort((a, b) -> Double.compare((Double) b.get("lift"), (Double) a.get("lift")));
            return Support.list("pairs", Support.slice(out, 0, Support.limit(request, "limit", 60, 500)));
        });

        router.get("/api/suggestions/demand", request -> {
            Support.staff(request, salon.auth);
            return Support.list("cells", salon.suggestions.demandGrid(Support.limit(request, "weeks", 8, 26)));
        });

        router.get("/api/suggestions/states", request -> {
            Support.staff(request, salon.auth);
            return Support.list("states", salon.suggestions.states(request.queryString("status"))
                    .stream().map(JsonMapper::toMap).toList());
        });

        router.post("/api/suggestions/:key/state", request -> {
            Actor actor = Support.staff(request, salon.auth);
            Map<String, Object> body = request.json();
            return Response.json(JsonMapper.toMap(salon.suggestions.setState(request.pathParam("key"),
                    Support.bodyText(body, "status"), Support.bodyText(body, "note"),
                    Support.bodyText(body, "entityId"), Support.bodyText(body, "kind"), actor)));
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapSuggestions(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Suggestion suggestion) out.add(JsonMapper.toMap(suggestion));
                else if (item instanceof Map<?, ?> map) out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static String name(Salon salon, String serviceId) {
        var service = salon.catalogue.findService(serviceId);
        return service == null ? serviceId : service.name();
    }

    // ================================================================== analytics

    private static void analytics(Router router, Salon salon) {
        router.get("/api/analytics/overview", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.analytics.overview(request.queryString("range", "30d")));
        });
        router.get("/api/analytics/revenue", request -> {
            Support.staff(request, salon.auth);
            LocalDate[] window = salon.analytics.rangeOf(request.queryString("range", "30d"));
            return Support.list("series", salon.analytics.series(window[0], window[1],
                    request.queryString("granularity", "day")));
        });
        router.get("/api/analytics/services", request -> {
            Support.staff(request, salon.auth);
            LocalDate[] window = salon.analytics.rangeOf(request.queryString("range", "90d"));
            return Support.list("services", salon.analytics.servicePerformance(window[0], window[1],
                    Support.limit(request, "limit", 12, 60)));
        });
        router.get("/api/analytics/staff", request -> {
            Support.staff(request, salon.auth);
            LocalDate[] window = salon.analytics.rangeOf(request.queryString("range", "30d"));
            return Support.list("staff", salon.analytics.staffPerformance(window[0], window[1]));
        });
        router.get("/api/analytics/retention", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.analytics.retention());
        });
        router.get("/api/analytics/occupancy", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.analytics.occupancyHeat(request.queryString("range", "30d")));
        });
        router.get("/api/analytics/forecast", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.analytics.forecast(Support.limit(request, "days", 7, 30)));
        });
        router.get("/api/analytics/products", request -> {
            Support.staff(request, salon.auth);
            return Support.list("products", salon.analytics.productPerformance(Support.limit(request, "limit", 20, 100)));
        });
        router.get("/api/analytics/channels", request -> {
            Support.staff(request, salon.auth);
            LocalDate[] window = salon.analytics.rangeOf(request.queryString("range", "30d"));
            return Response.json(salon.analytics.channelMix(window[0], window[1]));
        });
        router.get("/api/analytics/tax", request -> {
            Support.manager(request, salon.auth);
            LocalDate[] window = salon.analytics.rangeOf(request.queryString("range", "mtd"));
            return Response.json(salon.analytics.taxReport(window[0], window[1]));
        });
        router.get("/api/analytics/tax.csv", request -> {
            Support.manager(request, salon.auth);
            LocalDate[] window = salon.analytics.rangeOf(request.queryString("range", "mtd"));
            Map<String, Object> report = salon.analytics.taxReport(window[0], window[1]);
            List<List<String>> rows = new ArrayList<>();
            report.forEach((key, value) -> rows.add(List.of(key, String.valueOf(value))));
            return Support.csv(Util.toCsv(List.of("field", "value"), rows), "sillage-tax-" + window[0] + ".csv");
        });
    }

    // ================================================================== search

    private static void search(Router router, Salon salon) {
        router.get("/api/search", request -> {
            Actor actor = actor(request, salon);
            String query = Util.nz(request.queryString("q"), request.queryString("query"));
            SearchQuery.Builder builder = SearchQuery.builder()
                    .text(query)
                    .limit(Support.limit(request, "limit", 20, 100))
                    .offset(Math.max(0, request.queryInt("offset", 0)))
                    .sort(request.queryString("sort", "relevance"))
                    .ascending(request.queryBool("ascending", false));
            Set<String> wanted = new java.util.LinkedHashSet<>(request.queryAll("kind"));
            if (Util.present(request.queryString("kind"))) wanted.add(request.queryString("kind"));
            if (actor.isStaff()) {
                builder.kinds(wanted);
            } else {
                builder.kinds(wanted.isEmpty() ? PUBLIC_KINDS : intersection(wanted, PUBLIC_KINDS));
            }
            Support.filters(request).forEach(builder::filter);
            String minPrice = request.queryString("minPrice");
            String maxPrice = request.queryString("maxPrice");
            if (Util.present(minPrice) || Util.present(maxPrice)) builder.range("price", minPrice, maxPrice);
            SearchResult result = salon.search.search(builder.build());
            Map<String, Object> out = JsonMapper.toMap(result);
            out.put("kindsAllowed", actor.isStaff() ? "all" : PUBLIC_KINDS);
            return Response.json(out);
        });

        router.get("/api/search/suggest", request -> Support.list("suggestions",
                salon.search.autocomplete(Util.nz(request.queryString("q")), Support.limit(request, "limit", 8, 20))));

        router.get("/api/search/stats", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.search.stats());
        });

        router.post("/api/search/rebuild", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.search.rebuild());
        });
    }

    private static Set<String> intersection(Set<String> wanted, Set<String> allowed) {
        Set<String> out = new java.util.LinkedHashSet<>(wanted);
        out.retainAll(allowed);
        return out;
    }

    // ================================================================== loyalty

    private static void loyalty(Router router, Salon salon) {
        router.get("/api/loyalty", request -> Response.json(
                salon.settingsService.loyaltyView(salon.settingsService.current())));

        router.get("/api/loyalty/leaderboard", request -> {
            Support.staff(request, salon.auth);
            return Support.list("leaderboard", salon.loyalty.leaderboard(Support.limit(request, "limit", 10, 50)));
        });

        router.get("/api/loyalty/:customerId", request -> {
            Actor actor = actor(request, salon);
            String customerId = request.pathParam("customerId");
            actor.requireSelfOrStaff(customerId);
            Customer customer = salon.customers.get(customerId);
            if (customer == null) throw ApiException.notFound("Customer " + customerId);
            return Response.json(salon.loyalty.summary(customer));
        });
    }

    // ================================================================== settings

    private static void settings(Router router, Salon salon) {
        router.get("/api/settings", request -> {
            Actor actor = actor(request, salon);
            return Response.json(actor.has(Role.OWNER) ? salon.settingsService.adminView()
                    : salon.settingsService.publicView());
        });

        router.patch("/api/settings", request -> {
            Actor actor = Support.owner(request, salon.auth);
            salon.settingsService.patch(request.json(), actor);
            return Response.json(salon.settingsService.adminView());
        });

        router.put("/api/settings", request -> {
            Actor actor = Support.owner(request, salon.auth);
            salon.settingsService.patch(request.json(), actor);
            return Response.json(salon.settingsService.adminView());
        });

        router.get("/api/settings/hours", request -> Response.json(
                salon.settingsService.hoursView(salon.settingsService.current())));

        router.get("/api/settings/loyalty", request -> Response.json(
                salon.settingsService.loyaltyView(salon.settingsService.current())));

        router.get("/api/settings/payments", request -> Support.list("methods",
                salon.settingsService.paymentMethods(salon.settingsService.current())));

        router.get("/api/settings/features", request -> Response.json(
                salon.settingsService.current().features()));
    }

    // ================================================================== notifications

    private static void notifications(Router router, Salon salon) {
        router.get("/api/notifications", request -> {
            Actor actor = Support.staff(request, salon.auth);
            return Support.list("notifications", salon.notifications.recent(Support.limit(request, "limit", 40, 300))
                    .stream().map(n -> Util.map("id", n.id(), "channel", n.channel(), "to", Util.maskEmail(n.to()),
                            "subject", n.subject(), "body", n.body(), "template", n.template(), "status", n.status(),
                            "refKind", n.refKind(), "refId", n.refId(), "dryRun", n.dryRun(),
                            "createdAt", n.createdAt() == null ? null : n.createdAt().toString(),
                            "error", n.error())).toList());
        });

        router.get("/api/notifications/stats", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.notifications.stats());
        });

        router.post("/api/notifications/:id/resend", request -> {
            Support.staff(request, salon.auth);
            return Response.json(JsonMapper.toMap(salon.notifications.resend(request.pathParam("id"))));
        });

        router.get("/api/notifications/templates", request -> {
            Support.staff(request, salon.auth);
            BusinessSettings config = salon.settingsService.current();
            return Response.json(Util.map("templates", config.templates(),
                    "channels", List.of("SMS", "EMAIL", "WHATSAPP"),
                    "note", "Messages are composed here and dry-run: nothing leaves the server."));
        });
    }

    // ================================================================== audit

    private static void audit(Router router, Salon salon) {
        router.get("/api/audit", request -> {
            Actor actor = Support.manager(request, salon.auth);
            return Support.envelope("events", salon.audit.query(request.queryString("q"),
                            request.queryString("severity"), request.queryString("action"),
                            request.queryString("kind"), Support.limit(request, "limit", 80, 1000))
                            .stream().map(JsonMapper::toMap).toList(),
                    Util.map("summary", salon.audit.summary(), "count", salon.audit.count(),
                            "cap", salon.audit.cap(), "actor", actor.label()));
        });

        router.get("/api/audit/summary", request -> {
            Support.manager(request, salon.auth);
            return Response.json(salon.audit.summary());
        });

        router.get("/api/audit/entity/:kind/:id", request -> {
            Support.staff(request, salon.auth);
            return Support.list("events", salon.audit.forEntity(request.pathParam("kind"), request.pathParam("id"))
                    .stream().map(JsonMapper::toMap).toList());
        });

        router.get("/api/audit.csv", request -> {
            Support.manager(request, salon.auth);
            return Support.csv(salon.audit.csv(Support.limit(request, "limit", 2000, 5000)),
                    "sillage-audit-" + LocalDate.now() + ".csv");
        });
    }

    // ================================================================== data

    private static void data(Router router, Salon salon) {
        router.get("/api/exports", request -> {
            Support.staff(request, salon.auth);
            return Support.list("exports", salon.data.exports());
        });

        router.get("/api/export/:kind", request -> {
            Actor actor = Support.staff(request, salon.auth);
            String kind = request.pathParam("kind").replace(".csv", "");
            Map<String, Object> export = salon.data.export(kind, actor);
            return Support.csv(String.valueOf(export.get("csv")), String.valueOf(export.get("filename")));
        });

        router.get("/api/export/:kind/json", request -> {
            Actor actor = Support.staff(request, salon.auth);
            String kind = request.pathParam("kind").replace(".csv", "");
            Map<String, Object> export = salon.data.export(kind, actor);
            return Response.json(export);
        });

        router.post("/api/backup", request -> Response.json(
                salon.data.backup(Support.bodyText(request.json(), "label"), Support.manager(request, salon.auth)), 201));

        router.get("/api/backups", request -> {
            Support.manager(request, salon.auth);
            return Support.list("backups", salon.data.backups());
        });

        router.post("/api/restore/:name", request -> Response.json(
                salon.data.restore(request.pathParam("name"), Support.owner(request, salon.auth))));

        router.get("/api/storage", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.data.storage());
        });

        router.get("/api/integrity", request -> {
            Support.staff(request, salon.auth);
            return Response.json(salon.data.integrity());
        });

        router.post("/api/purge", request -> Response.json(
                salon.data.purge(Support.manager(request, salon.auth))));

        router.post("/api/reset", request -> Response.json(
                salon.data.resetAndSeed(Support.owner(request, salon.auth), true)));

        router.get("/api/loyalty-report", request -> {
            Support.manager(request, salon.auth);
            BusinessSettings config = salon.settingsService.current();
            return Response.json(Util.map("tiers", config.loyaltyTiers(), "leaderboard",
                    salon.loyalty.leaderboard(20), "view", salon.settingsService.loyaltyView(config)));
        });
    }

    /** Everything an endpoint needs to describe itself, used by the built-in explorer. */
    public static Map<String, Object> describe(Salon salon) {
        return Util.map("routes", salon.router.size(), "engine", com.sillage.service.SuggestionEngine.ENGINE,
                "version", com.sillage.SillageApp.VERSION, "json", Json.write(Map.of("ok", true)));
    }
}
