package com.sillage.web;

import com.sillage.core.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An outbound HTTP response. Immutable-ish builder style; the server writes it to the exchange
 * in one pass, applying gzip when the client accepts it and the body is worth compressing.
 */
public final class Response {

    private int status = 200;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];
    private boolean compressible = true;

    private Response() {}

    public static Response of(int status) {
        Response r = new Response();
        r.status = status;
        return r;
    }

    public static Response json(Object payload) {
        return json(payload, 200);
    }

    public static Response json(Object payload, int status) {
        Response r = new Response();
        r.status = status;
        r.body = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        r.header("Content-Type", "application/json; charset=utf-8");
        r.header("X-Content-Type-Options", "nosniff");
        return r;
    }

    public static Response ok(Object payload) {
        return json(Map.of("ok", true, "data", payload));
    }

    public static Response created(Object payload) {
        return json(Map.of("ok", true, "data", payload), 201);
    }

    public static Response noContent() {
        Response r = new Response();
        r.status = 204;
        r.compressible = false;
        return r;
    }

    public static Response text(String content) {
        return text(content, 200, "text/plain; charset=utf-8");
    }

    public static Response text(String content, int status, String contentType) {
        Response r = new Response();
        r.status = status;
        r.body = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        r.header("Content-Type", contentType);
        return r;
    }

    public static Response html(String content) {
        return text(content, 200, "text/html; charset=utf-8");
    }

    public static Response html(String content, int status) {
        return text(content, status, "text/html; charset=utf-8");
    }

    public static Response redirect(String location) {
        return redirect(location, 302);
    }

    public static Response redirect(String location, int status) {
        Response r = new Response();
        r.status = status;
        r.header("Location", location);
        r.compressible = false;
        return r;
    }

    public static Response bytes(byte[] content, String contentType, String filename) {
        Response r = new Response();
        r.body = content == null ? new byte[0] : content;
        r.header("Content-Type", contentType);
        if (filename != null) r.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        r.compressible = contentType != null && contentType.startsWith("text/");
        return r;
    }

    public Response status(int value) { this.status = value; return this; }

    public Response header(String name, String value) {
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return this;
    }

    public Response setHeader(String name, String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        headers.put(name, list);
        return this;
    }

    public Response cookie(String header) {
        return header("Set-Cookie", header);
    }

    public Response body(byte[] value) { this.body = value == null ? new byte[0] : value; return this; }

    public Response body(String value) { this.body = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8); return this; }

    public Response compressible(boolean value) { this.compressible = value; return this; }

    public Response cacheControl(String value) { return setHeader("Cache-Control", value); }

    public Response etag(String value) { return setHeader("ETag", "\"" + value + "\""); }

    public int status() { return status; }

    public byte[] body() { return body; }

    public boolean compressible() { return compressible; }

    public Map<String, List<String>> headers() { return headers; }

    public String headerValue(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public int contentLength() { return body.length; }

    public String contentType() {
        String value = headerValue("Content-Type");
        return value == null ? "application/octet-stream" : value.toLowerCase(Locale.ROOT);
    }

    public boolean isTextual() {
        String type = contentType();
        return type.startsWith("text/") || type.contains("json") || type.contains("javascript") || type.contains("svg") || type.contains("xml");
    }
}
