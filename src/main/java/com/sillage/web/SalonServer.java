package com.sillage.web;

import com.sillage.core.ApiException;
import com.sillage.core.Config;
import com.sillage.core.Log;
import com.sillage.core.Util;
import com.sillage.data.Repo;
import com.sillage.model.SessionRecord;
import com.sillage.model.UserAccount;
import com.sillage.security.AuditLog;
import com.sillage.security.RateLimiter;
import com.sillage.security.SessionManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * The HTTP front door: a JDK {@code com.sun.net.httpserver} bound to a fixed thread pool, with
 * session resolution, rate limiting, security headers, gzip, conditional GETs and a single place
 * where every exception becomes a JSON (or HTML) error.
 */
public final class SalonServer {

    private final Config config;
    private final Router router;
    private final StaticFiles staticFiles;
    private final SessionManager sessionManager;
    private final Repo<UserAccount> users;
    private final AuditLog audit;
    private final RateLimiter rateLimiter;
    private final Map<String, String> pageRoutes;

    private HttpServer server;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile LocalDateTime startedAt;
    private int boundPort;

    public SalonServer(Config config,
                       Router router,
                       StaticFiles staticFiles,
                       SessionManager sessionManager,
                       Repo<UserAccount> users,
                       AuditLog audit,
                       RateLimiter rateLimiter,
                       Map<String, String> pageRoutes) {
        this.config = config;
        this.router = router;
        this.staticFiles = staticFiles;
        this.sessionManager = sessionManager;
        this.users = users;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.pageRoutes = pageRoutes == null ? Map.of() : pageRoutes;
    }

    public int port() { return boundPort; }

    public LocalDateTime startedAt() { return startedAt; }

    public long requestCount() { return requests.get(); }

    public long errorCount() { return errors.get(); }

    public void start() throws IOException {
        InetSocketAddress address = new InetSocketAddress(config.host(), config.port());
        server = HttpServer.create(address, 512);
        boundPort = server.getAddress().getPort();
        int threads = Math.max(6, Math.min(24, Runtime.getRuntime().availableProcessors() * 4));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "sillage-http");
            thread.setDaemon(false);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(1024), factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        startedAt = LocalDateTime.now();
    }

    public void stop() {
        if (server == null) return;
        server.stop(1);
        Log.info("http server stopped");
    }

    // ------------------------------------------------------------------ pipeline

    private void handle(HttpExchange exchange) {
        Request request = null;
        Response response;
        try {
            request = new Request(exchange, config.trustProxy());
            requests.incrementAndGet();
            resolveIdentity(request);
            response = dispatch(request);
            if (response == null) response = notFound(request);
        } catch (ApiException e) {
            response = errorResponse(request, e);
        } catch (Throwable t) {
            errors.incrementAndGet();
            Log.error("unhandled " + (request == null ? "request" : request.method() + " " + request.path()), t);
            response = internalError(request, t);
        }
        try {
            decorate(request, response);
            write(exchange, request, response);
            accessLog(request, response);
        } catch (IOException e) {
            Log.warn("cannot write response: %s", e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void resolveIdentity(Request request) {
        String token = request.cookie(SessionManager.COOKIE);
        if (token == null || token.isBlank()) return;
        sessionManager.resolve(token).ifPresent(session -> {
            request.session(session);
            request.attribute("sessionToken", token);
            if (session.userId() != null) {
                UserAccount account = users.get(session.userId());
                if (account == null || !account.active()) {
                    sessionManager.revoke(session.id(), "account unavailable");
                    request.session(null);
                    return;
                }
                request.account(account);
            }
            sessionManager.touch(session);
        });
    }

    private Response dispatch(Request request) throws Exception {
        String path = request.path();

        if (path.startsWith("/api/")) {
            RateLimiter.Decision decision = rateLimiter.check(ruleFor(path), request.ip());
            if (!decision.allowed()) {
                audit.security("rate_limited", decision.limit() + " requests / " + (decision.windowMillis() / 1000)
                        + "s exceeded on " + path, request.ip(),
                        Map.of("path", path, "retryAfter", decision.retryAfterSeconds()));
                ApiException limited = new ApiException(429, "RATE_LIMITED", "Too many requests \u2014 slow down a little.",
                        Map.of("retryAfterSeconds", decision.retryAfterSeconds(), "limit", decision.limit(),
                                "windowSeconds", decision.windowMillis() / 1000));
                return Response.json(limited.toBody(), 429)
                        .setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            }
            Router.Match match = router.match(request);
            if (match == null) {
                Object allow = request.attribute("allow");
                if (allow != null) throw new MethodNotAllowed(allow.toString());
                throw ApiException.notFound("API route " + request.method() + " " + path);
            }
            match.params().forEach(request::setPathParam);
            return match.handler().handle(request);
        }

        if (!request.method().equals("GET") && !request.method().equals("HEAD")) {
            Router.Match match = router.match(request);
            if (match != null) {
                match.params().forEach(request::pathParam);
                return match.handler().handle(request);
            }
            return null;
        }

        // Pretty page routes: /book -> web/book.html
        String page = pageRoutes.get(path);
        if (page != null) return staticFiles.serve(page, request);

        Router.Match match = router.match(request);
        if (match != null) {
            match.params().forEach(request::pathParam);
            return match.handler().handle(request);
        }

        Response file = staticFiles.serve(path, request);
        if (file != null) return file;

        if (!path.contains(".")) {
            Response spa = staticFiles.serve("/studio.html", request);
            if (spa != null) return spa;
        }
        return null;
    }

    private static final class MethodNotAllowed extends ApiException {
        MethodNotAllowed(String allow) {
            super(405, "METHOD_NOT_ALLOWED", "That method is not allowed here", Map.of("allow", allow));
        }
    }

    /** Which rate-limit bucket a path belongs to. */
    private static String ruleFor(String path) {
        if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/password")) return "login";
        if (path.startsWith("/api/auth/register")) return "register";
        if (path.startsWith("/api/payments")) return "payment";
        if (path.startsWith("/api/search")) return "search";
        if (path.startsWith("/api/export") || path.startsWith("/api/data")) return "export";
        if (path.startsWith("/api/appointments")) return "booking";
        return "api";
    }

    private Response notFound(Request request) {
        if (request.path().startsWith("/api/")) return Response.json(new ApiException(404, "NOT_FOUND", "No route for " + request.method() + " " + request.path()).toBody(), 404);
        Response page = staticFiles.serve("/404.html", request);
        return page != null ? page.status(404) : Response.html(ErrorPages.notFound(request.path()), 404);
    }

    private Response errorResponse(Request request, ApiException e) {
        Response response = Response.json(e.toBody(), e.status());
        if (e.status() == 429) {
            Object retry = e.details().get("retryAfterSeconds");
            if (retry != null) response.setHeader("Retry-After", String.valueOf(retry));
        }
        return response;
    }

    private Response internalError(Request request, Throwable t) {
        if (t instanceof ApiException api) return errorResponse(request, api);
        String message = config.dev() ? Util.nz(t.getMessage(), t.getClass().getSimpleName()) : "Something went wrong on our side.";
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "INTERNAL");
        error.put("message", message);
        error.put("requestId", request == null ? "n/a" : request.requestId());
        error.put("at", LocalDateTime.now().toString());
        body.put("error", error);
        if (request != null && !request.path().startsWith("/api/") && request.wantsHtml()) {
            return Response.html(ErrorPages.serverError(request.requestId()), 500);
        }
        return Response.json(body, 500);
    }

    private void decorate(Request request, Response response) {
        boolean secure = config.secureCookies() || (request != null && request.isSecure());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(self), payment=(self), interest-cohort=()");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("X-XSS-Protection", "0");
        response.setHeader("Content-Security-Policy", contentSecurityPolicy());
        if (secure) response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        if (request != null) {
            response.setHeader("X-Request-Id", request.requestId());
            response.setHeader("X-Response-Time", (request.elapsedMicros() / 1000.0) + "ms");
        }
        if (request != null && request.path().startsWith("/api/") && response.headerValue("Cache-Control") == null) {
            response.cacheControl("no-store");
        }
    }

    private static String contentSecurityPolicy() {
        return String.join("; ", List.of(
                "default-src 'self'",
                "script-src 'self'",
                "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
                "font-src 'self' https://fonts.gstatic.com data:",
                "img-src 'self' data: blob: https:",
                "connect-src 'self'",
                "media-src 'self'",
                "object-src 'none'",
                "frame-ancestors 'none'",
                "base-uri 'self'",
                "form-action 'self'"));
    }

    private void write(HttpExchange exchange, Request request, Response response) throws IOException {
        com.sun.net.httpserver.Headers headers = exchange.getResponseHeaders();
        response.headers().forEach((name, values) -> values.forEach(value -> headers.add(name, value)));

        boolean noBody = response.status() == 204 || response.status() == 304 || request.method().equals("HEAD");
        byte[] body = noBody ? new byte[0] : response.body();
        boolean gzipped = false;
        if (!noBody && body.length > 1024 && response.compressible() && response.isTextual()
                && request != null && request.acceptsGzip()) {
            body = gzip(body);
            gzipped = true;
            headers.set("Content-Encoding", "gzip");
            headers.add("Vary", "Accept-Encoding");
        }
        headers.set("Content-Length", String.valueOf(noBody ? 0 : body.length));
        exchange.sendResponseHeaders(response.status(), noBody ? -1 : body.length);
        if (!noBody && body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
                out.flush();
            }
        }
        if (gzipped && config.dev()) Log.debug("gzipped %s: %d -> %d bytes", request.path(), response.body().length, body.length);
    }

    private static byte[] gzip(byte[] input) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(64, input.length / 3));
        try (GZIPOutputStream out = new GZIPOutputStream(buffer)) {
            out.write(input);
        } catch (IOException e) {
            return input;
        }
        return buffer.toByteArray();
    }

    private void accessLog(Request request, Response response) {
        if (request == null) return;
        String line = "%s %s -> %d (%.1f ms, %s, req %s)".formatted(
                request.method(), request.path(), response.status(), request.elapsedMicros() / 1000.0,
                request.ip(), request.requestId());
        boolean isAsset = request.path().startsWith("/assets/") || request.path().contains(".css") || request.path().contains(".js")
                || request.path().contains(".png") || request.path().contains(".jpg") || request.path().contains(".svg")
                || request.path().contains(".woff");
        if (response.status() >= 500) Log.error(line);
        else if (response.status() >= 400) Log.warn(line);
        else if (isAsset || response.status() == 304) Log.debug(line);
        else Log.info(line);
    }

    public SessionRecord currentSession(Request request) {
        return request == null ? null : request.session();
    }
}
