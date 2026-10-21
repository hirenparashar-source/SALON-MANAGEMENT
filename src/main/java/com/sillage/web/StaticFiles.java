package com.sillage.web;

import com.sillage.core.Log;
import com.sillage.core.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the front end straight off the filesystem with conditional GET (ETag + Last-Modified),
 * strict path-traversal defence and sensible cache lifetimes.
 */
public final class StaticFiles {

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final long ASSET_MAX_AGE = 60 * 60 * 24 * 7; // 7 days for images and fonts
    private static final int NOT_CACHEABLE_LIMIT = 1024;

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("gif", "image/gif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("csv", "text/csv; charset=utf-8"),
            Map.entry("xml", "application/xml; charset=utf-8"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"));

    private final Path root;

    public StaticFiles(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            Log.warn("static root %s is not writable: %s", this.root, e.getMessage());
        }
    }

    public Path root() { return root; }

    public boolean exists(String relativePath) {
        return resolve(relativePath) != null;
    }

    /** Resolves a request path to a real file inside the web root, or {@code null}. */
    public Path resolve(String requestPath) {
        if (requestPath == null) return null;
        String cleaned = requestPath.replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty() || cleaned.equals(".")) cleaned = "index.html";
        Path candidate = root.resolve(cleaned).normalize();
        if (!candidate.startsWith(root)) return null;           // traversal attempt
        if (!Files.isRegularFile(candidate)) return null;
        return candidate;
    }

    public Response serve(String requestPath, Request request) {
        Path file = resolve(requestPath);
        if (file == null) return null;
        return serveFile(file, request);
    }

    public Response serveFile(Path file, Request request) {
        try {
            long size = Files.size(file);
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            String etag = Util.sha256Hex(file.toString() + "|" + size + "|" + modified.toEpochMilli()).substring(0, 16);

            String ifNoneMatch = request.header("if-none-match");
            long ifModifiedSince = parseHttpDate(request.header("if-modified-since"));
            boolean unchanged = (ifNoneMatch != null && (ifNoneMatch.equals("\"" + etag + "\"") || ifNoneMatch.equals(etag) || ifNoneMatch.equals("W/\"" + etag + "\"")))
                    || (ifModifiedSince > 0 && modified.toEpochMilli() / 1000 <= ifModifiedSince / 1000);

            String contentType = contentTypeFor(file.getFileName().toString());
            Response response;
            if (unchanged && request.method().equals("GET")) {
                response = Response.of(304).compressible(false);
            } else {
                byte[] bytes = Files.readAllBytes(file);
                response = Response.bytes(bytes, contentType, null);
            }

            response.etag(etag);
            response.setHeader("Last-Modified", HTTP_DATE.format(modified));
            boolean isAsset = file.toString().replace('\\', '/').contains("/assets/");
            boolean isHtml = contentType.contains("text/html");
            // CSS and JS are small and they change with the design, so they revalidate on
            // every load (an unchanged file costs one empty 304 against the ETag above).
            // Images and fonts are heavy and stable, so they keep the long lifetime.
            boolean isText = contentType.startsWith("text/") || contentType.contains("javascript")
                    || contentType.contains("json") || contentType.contains("svg");
            if (isHtml || (isAsset && isText)) response.cacheControl("no-cache");
            else if (isAsset) response.cacheControl("public, max-age=" + ASSET_MAX_AGE);
            else response.cacheControl(size < NOT_CACHEABLE_LIMIT ? "no-cache" : "public, max-age=300");
            return response;
        } catch (IOException e) {
            Log.warn("cannot serve %s: %s", file, e.getMessage());
            return null;
        }
    }

    public static String contentTypeFor(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        return CONTENT_TYPES.getOrDefault(name.substring(dot + 1), "application/octet-stream");
    }

    private static long parseHttpDate(String value) {
        if (value == null || value.isBlank()) return -1;
        try {
            return HTTP_DATE.parse(value, Instant::from).toEpochMilli();
        } catch (Exception e) {
            return -1;
        }
    }
}
