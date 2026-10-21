package com.sillage.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A domain/transport error that knows its own HTTP status. Handlers throw it, the router turns
 * it into a consistent JSON error envelope:
 *
 * <pre>{@code { "error": { "code": "VALIDATION", "message": "…", "details": { "email": "…" } } }}</pre>
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public int status() { return status; }

    public String code() { return code; }

    public Map<String, Object> details() { return details; }

    public Map<String, Object> toBody() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", getMessage());
        if (!details.isEmpty()) error.put("details", details);
        error.put("at", Util.now().toString());
        return Map.of("error", error);
    }

    // ------------------------------------------------------------------ factories

    public static ApiException badRequest(String message) { return new ApiException(400, "BAD_REQUEST", message); }

    public static ApiException validation(Map<String, Object> problems) {
        return new ApiException(422, "VALIDATION", problems.isEmpty() ? "Invalid request" : "Some fields need attention",
                Map.of("fields", problems));
    }

    public static ApiException validation(List<String> problems) {
        return new ApiException(422, "VALIDATION", "Some fields need attention", Map.of("problems", problems));
    }

    public static ApiException unauthorized(String message) { return new ApiException(401, "UNAUTHORIZED", message); }

    public static ApiException forbidden(String message) { return new ApiException(403, "FORBIDDEN", message); }

    public static ApiException notFound(String what) { return new ApiException(404, "NOT_FOUND", what + " not found"); }

    public static ApiException conflict(String message) { return new ApiException(409, "CONFLICT", message); }

    public static ApiException locked(String message, Map<String, Object> details) { return new ApiException(423, "LOCKED", message, details); }

    public static ApiException tooMany(String message, long retryAfterSeconds) {
        return new ApiException(429, "RATE_LIMITED", message, Map.of("retryAfterSeconds", retryAfterSeconds));
    }

    public static ApiException payment(String message, Map<String, Object> details) {
        return new ApiException(402, "PAYMENT_FAILED", message, details);
    }

    public static ApiException unavailable(String message) { return new ApiException(503, "UNAVAILABLE", message); }

    public static ApiException server(String message) { return new ApiException(500, "INTERNAL", message); }
}
