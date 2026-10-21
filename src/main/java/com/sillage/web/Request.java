package com.sillage.web;

import com.sillage.core.ApiException;
import com.sillage.core.JsonMapper;
import com.sillage.core.JsonParser;
import com.sillage.core.Util;
import com.sillage.model.SessionRecord;
import com.sillage.model.UserAccount;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An inbound HTTP request: parsed query string, cookies, headers, lazily-read body and the
 * per-request attributes the pipeline attaches (session, account, request id, timings).
 */
public final class Request {

    /** Bodies larger than this are rejected outright (4 MiB is generous for a salon payload). */
    public static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private final HttpExchange exchange;
    private final String method;
    private final String path;
    private final String rawQuery;
    private final Map<String, String> query;
    private final Map<String, List<String>> queryAll;
    private final Map<String, String> headers;
    private final Map<String, String> cookies;
    private final Map<String, String> pathParams = new LinkedHashMap<>();
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private final String requestId;
    private final long startedNanos;

    private byte[] body;
    private String bodyText;
    private Map<String, Object> jsonBody;
    private boolean bodyRead;

    private SessionRecord session;
    private UserAccount account;
    private String ip;

    public Request(HttpExchange exchange, boolean trustProxy) {
        this.exchange = exchange;
        this.method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String uri = exchange.getRequestURI().getPath();
        this.path = uri == null || uri.isEmpty() ? "/" : normalisePath(uri);
        this.rawQuery = exchange.getRequestURI().getRawQuery();
        this.query = new LinkedHashMap<>();
        this.queryAll = new LinkedHashMap<>();
        parseQuery(this.rawQuery, this.query, this.queryAll);
        this.headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((key, values) -> {
            if (values != null && !values.isEmpty()) headers.put(key.toLowerCase(Locale.ROOT), values.get(0));
        });
        this.cookies = parseCookies(headers.get("cookie"));
        this.requestId = Util.randomToken(8);
        this.startedNanos = System.nanoTime();
        this.ip = resolveIp(exchange, headers, trustProxy);
    }

    private static String normalisePath(String uri) {
        String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
        String cleaned = decoded.replace("//", "/");
        if (cleaned.length() > 1 && cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned.isEmpty() ? "/" : cleaned;
    }

    private static void parseQuery(String raw, Map<String, String> single, Map<String, List<String>> all) {
        if (raw == null || raw.isBlank()) return;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            String decodedKey = URLDecoder.decode(key.replace("+", " "), StandardCharsets.UTF_8);
            String decodedValue = URLDecoder.decode(value.replace("+", " "), StandardCharsets.UTF_8);
            single.putIfAbsent(decodedKey, decodedValue);
            all.computeIfAbsent(decodedKey, k -> new ArrayList<>()).add(decodedValue);
        }
    }

    private static Map<String, String> parseCookies(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        if (header == null || header.isBlank()) return out;
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            out.put(trimmed.substring(0, eq).trim(), URLDecoder.decode(trimmed.substring(eq + 1).trim(), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String resolveIp(HttpExchange exchange, Map<String, String> headers, boolean trustProxy) {
        if (trustProxy) {
            String forwarded = headers.get("x-forwarded-for");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].trim();
                if (!first.isEmpty()) return first;
            }
            String real = headers.get("x-real-ip");
            if (real != null && !real.isBlank()) return real.trim();
        }
        if (exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null) {
            return exchange.getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    // ------------------------------------------------------------------ accessors

    public HttpExchange exchange() { return exchange; }
    public String method() { return method; }
    public String path() { return path; }
    public String rawQuery() { return rawQuery; }
    public String requestId() { return requestId; }
    public long startedNanos() { return startedNanos; }
    public String ip() { return ip; }

    public String userAgent() { return Util.nz(headers.get("user-agent")); }

    public String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }

    public String header(String name, String fallback) { return Util.nz(headers.get(name.toLowerCase(Locale.ROOT)), fallback); }

    public boolean acceptsGzip() {
        String accept = headers.get("accept-encoding");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("gzip");
    }

    public boolean wantsHtml() {
        String accept = Util.nz(headers.get("accept")).toLowerCase(Locale.ROOT);
        return accept.contains("text/html") || accept.contains("*/*") && accept.indexOf("json") < 0;
    }

    public String cookie(String name) { return cookies.get(name); }

    public Map<String, String> cookies() { return cookies; }

    public boolean isSecure() {
        String forwarded = headers.get("x-forwarded-proto");
        return "https".equalsIgnoreCase(forwarded) || exchange.getRequestURI().getScheme() != null
                && exchange.getRequestURI().getScheme().startsWith("https");
    }

    public String queryString(String name) { return query.get(name); }

    public String queryString(String name, String fallback) {
        String value = query.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public List<String> queryAll(String name) { return queryAll.getOrDefault(name, List.of()); }

    public int queryInt(String name, int fallback) {
        String value = query.get(name);
        return value == null || value.isBlank() ? fallback : Util.parseInt(value, fallback);
    }

    public boolean queryBool(String name, boolean fallback) {
        String value = query.get(name);
        if (value == null || value.isBlank()) return fallback;
        return JsonMapper.asBoolean(value);
    }

    public String pathParam(String name) { return pathParams.get(name); }

    public String pathParam(String name, String fallback) { return Util.nz(pathParams.get(name), fallback); }

    public void setPathParam(String name, String value) { pathParams.put(name, value); }

    public Map<String, String> pathParams() { return pathParams; }

    public Map<String, String> query() { return query; }

    // ------------------------------------------------------------------ body

    public byte[] bodyBytes() {
        if (bodyRead) return body;
        bodyRead = true;
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = in.readNBytes(MAX_BODY_BYTES + 1);
            if (buffer.length > MAX_BODY_BYTES) {
                throw ApiException.badRequest("Request body exceeds the " + (MAX_BODY_BYTES / 1024 / 1024) + " MiB limit");
            }
            body = buffer;
            bodyText = new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the request body");
        }
        return body;
    }

    public String bodyText() {
        bodyBytes();
        return bodyText;
    }

    /** The request body as a JSON object (an empty body yields an empty map). */
    public Map<String, Object> json() {
        if (jsonBody != null) return jsonBody;
        String text = bodyText();
        if (text == null || text.isBlank()) {
            jsonBody = new LinkedHashMap<>();
            return jsonBody;
        }
        try {
            Object parsed = JsonParser.parse(text);
            jsonBody = JsonMapper.asMap(parsed);
        } catch (JsonParser.JsonException e) {
            throw new ApiException(400, "MALFORMED_JSON", "The request body is not valid JSON: " + e.getMessage());
        }
        return jsonBody;
    }

    public String jsonField(String name) {
        Object value = json().get(name);
        return value == null ? null : JsonMapper.asText(value);
    }

    // ------------------------------------------------------------------ attributes & identity

    public Object attribute(String name) { return attributes.get(name); }

    public void attribute(String name, Object value) { attributes.put(name, value); }

    public SessionRecord session() { return session; }

    public void session(SessionRecord record) { this.session = record; }

    public UserAccount account() { return account; }

    public void account(UserAccount value) { this.account = value; }

    public boolean isAuthenticated() { return session != null && session.userId() != null; }

    public com.sillage.model.Role role() {
        return session == null ? com.sillage.model.Role.GUEST : session.role();
    }

    public String actorName() {
        if (session == null) return "anonymous";
        return Util.nz(session.displayName(), session.userId() == null ? "guest" : "user");
    }

    public long elapsedMicros() { return (System.nanoTime() - startedNanos) / 1000; }
}
