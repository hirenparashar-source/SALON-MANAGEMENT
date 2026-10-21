package com.sillage.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny path-pattern router: {@code /api/customers/:id}, literal segments, {@code :named}
 * captures and a trailing {@code *} wildcard. Registration order wins on ties.
 */
public final class Router {

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    /** A matched route plus its captured path parameters. */
    public record Match(Handler handler, String pattern, String method, Map<String, String> params) {}

    private record Route(String method, String pattern, String[] segments, Handler handler) {}

    private final List<Route> routes = new ArrayList<>();

    public Router add(String method, String pattern, Handler handler) {
        routes.add(new Route(method.toUpperCase(Locale.ROOT), pattern, split(pattern), handler));
        return this;
    }

    public Router get(String pattern, Handler handler) { return add("GET", pattern, handler); }
    public Router post(String pattern, Handler handler) { return add("POST", pattern, handler); }
    public Router put(String pattern, Handler handler) { return add("PUT", pattern, handler); }
    public Router patch(String pattern, Handler handler) { return add("PATCH", pattern, handler); }
    public Router delete(String pattern, Handler handler) { return add("DELETE", pattern, handler); }

    public Match match(Request request) {
        String[] parts = split(request.path());
        List<String> allowedMethods = new ArrayList<>();
        for (Route route : routes) {
            if (route.segments().length < parts.length && !endsWithWildcard(route.segments())) continue;
            Map<String, String> params = new LinkedHashMap<>();
            if (matches(route.segments(), parts, params)) {
                if (route.method().equals(request.method()) || route.method().equals("ANY")) {
                    return new Match(route.handler(), route.pattern(), route.method(), params);
                }
                if (!allowedMethods.contains(route.method())) allowedMethods.add(route.method());
            }
        }
        if (!allowedMethods.isEmpty()) {
            request.attribute("allow", String.join(", ", allowedMethods));
        }
        return null;
    }

    public List<String> methodsFor(String path) {
        String[] parts = split(path);
        List<String> out = new ArrayList<>();
        for (Route route : routes) {
            if (matches(route.segments(), parts, new LinkedHashMap<>()) && !out.contains(route.method())) out.add(route.method());
        }
        return out;
    }

    public int size() { return routes.size(); }

    /** Route inventory for the built-in API explorer. */
    public List<Map<String, Object>> inventory() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Route route : routes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("method", route.method());
            entry.put("path", route.pattern());
            out.add(entry);
        }
        return out;
    }

    private static boolean matches(String[] pattern, String[] parts, Map<String, String> params) {
        int i = 0;
        for (; i < pattern.length; i++) {
            String token = pattern[i];
            if (token.equals("*")) {
                params.put("wildcard", String.join("/", java.util.Arrays.copyOfRange(parts, i, parts.length)));
                return true;
            }
            if (i >= parts.length) return false;
            if (token.startsWith(":")) {
                params.put(token.substring(1), parts[i]);
            } else if (!token.equalsIgnoreCase(parts[i])) {
                return false;
            }
        }
        return i == parts.length;
    }

    private static boolean endsWithWildcard(String[] segments) {
        return segments.length > 0 && segments[segments.length - 1].equals("*");
    }

    private static String[] split(String path) {
        if (path == null || path.equals("/")) return new String[0];
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/");
    }
}
