package com.sillage.api;

import com.sillage.core.ApiException;
import com.sillage.core.JsonMapper;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.model.Actor;
import com.sillage.model.Role;
import com.sillage.service.AuthService;
import com.sillage.web.Request;
import com.sillage.web.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small helpers shared by the endpoint classes. */
public final class Support {

    private Support() {}

    public static Actor actor(Request request, AuthService auth) {
        return auth.actorOf(request.session(), request.account(), request.ip(), request.userAgent());
    }

    public static Actor staff(Request request, AuthService auth) {
        Actor actor = actor(request, auth);
        actor.requireStaff();
        return actor;
    }

    public static Actor manager(Request request, AuthService auth) {
        Actor actor = actor(request, auth);
        actor.require(Role.MANAGER);
        return actor;
    }

    /** Any signed-in actor - staff or client. Guests are turned away. */
    public static Actor staffOrClient(Request request, AuthService auth) {
        Actor actor = actor(request, auth);
        if (!actor.authenticated()) throw ApiException.unauthorized("Sign in first");
        return actor;
    }

    public static Actor owner(Request request, AuthService auth) {
        Actor actor = actor(request, auth);
        actor.require(Role.OWNER);
        return actor;
    }

    /** Wraps a payload in the envelope every endpoint uses. */
    public static Response envelope(String key, Object payload, Map<String, Object> meta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(key, payload);
        if (meta != null && !meta.isEmpty()) body.put("meta", meta);
        return Response.json(body);
    }

    public static Response list(String key, List<?> items) {
        return envelope(key, items, Map.of("count", items == null ? 0 : items.size()));
    }

    public static Response csv(String content, String filename) {
        return Response.text(content, 200, "text/csv; charset=utf-8")
                .setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .compressible(true);
    }

    public static Response download(String content, String filename, String contentType) {
        return Response.text(content, 200, contentType)
                .setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .compressible(true);
    }

    public static Response plainText(String content) {
        return Response.text(content, 200, "text/plain; charset=utf-8").compressible(true);
    }

    public static Response html(String content) {
        return Response.html(content).compressible(true);
    }

    public static int limit(Request request, String name, int fallback, int max) {
        return Util.clamp(request.queryInt(name, fallback), 1, max);
    }

    public static LocalDate date(Request request, String name, LocalDate fallback) {
        String raw = request.queryString(name);
        if (Util.blank(raw)) return fallback;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Date " + raw + " should look like 2026-10-21");
        }
    }

    public static LocalDate dateOrThrow(Request request, String name) {
        LocalDate value = date(request, name, null);
        if (value == null) throw ApiException.badRequest("A date is required (" + name + "=YYYY-MM-DD)");
        return value;
    }

    public static LocalDateTime dateTime(Request request, String name, LocalDateTime fallback) {
        String raw = request.queryString(name);
        if (Util.blank(raw)) return fallback;
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Timestamp " + raw + " should look like 2026-10-21T14:30");
        }
    }

    public static BigDecimal amount(Request request, String name, BigDecimal fallback) {
        String raw = request.queryString(name);
        if (Util.blank(raw)) return fallback;
        try {
            return Money.of(raw.trim());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Amount " + raw + " is not a number");
        }
    }

    public static BigDecimal bodyAmount(Map<String, Object> body, String name, BigDecimal fallback) {
        Object value = body == null ? null : body.get(name);
        if (value == null) return fallback;
        return Money.of(value);
    }

    public static String text(Request request, String name, String fallback) {
        return Util.nz(request.queryString(name), fallback);
    }

    /** Collects repeated {@code filter=kind:value} query parameters into a map. */
    public static Map<String, String> filters(Request request) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String value : request.queryAll("filter")) {
            int at = value.indexOf(':');
            if (at > 0) out.put(value.substring(0, at).trim(), value.substring(at + 1).trim());
        }
        for (Map.Entry<String, String> entry : request.query().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("f_")) out.put(key.substring(2), entry.getValue());
        }
        return out;
    }

    public static List<String> bodyStrings(Map<String, Object> body, String name) {
        if (body == null) return List.of();
        List<String> out = JsonMapper.asStringList(body.get(name));
        return out == null ? new ArrayList<>() : out;
    }

    public static String bodyText(Map<String, Object> body, String name) {
        return body == null ? null : JsonMapper.asText(body.get(name));
    }

    public static Map<String, Object> page(int total, int offset, int limit) {
        return Util.map("total", total, "offset", offset, "limit", limit,
                "next", offset + limit < total ? offset + limit : null);
    }

    public static <T> List<T> slice(List<T> items, int offset, int limit) {
        if (items == null) return List.of();
        int from = Util.clamp(offset, 0, items.size());
        int to = Util.clamp(from + limit, from, items.size());
        return new ArrayList<>(items.subList(from, to));
    }

    public static Response ok(Object payload) {
        return Response.json(payload);
    }

    public static Response message(String message) {
        return Response.json(Map.of("message", message, "at", LocalDateTime.now().toString()));
    }

    public static Response message(String message, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", message);
        out.put("at", LocalDateTime.now().toString());
        if (extra != null) out.putAll(extra);
        return Response.json(out);
    }
}
