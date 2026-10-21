package com.sillage.security;

import com.sillage.core.ApiException;
import com.sillage.core.JsonMapper;
import com.sillage.core.Money;
import com.sillage.core.Util;
import com.sillage.model.Role;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Request validation with field-level errors. Every rule adds at most one message per field, and
 * {@link #validate()} throws a 422 carrying the whole map so a form can light up in one round trip.
 *
 * <p>Validation is also the first line of injection defence: nothing reaches the store unless it
 * matched a shape, a length bound and (where relevant) a character class.
 */
public final class Validator {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+()\\-. ]{7,20}$");
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._\\-]{3,32}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9._\\-/]{2,48}$");
    private static final Pattern URL = Pattern.compile("^https?://[^\\s]{4,300}$");
    private static final Pattern UPI = Pattern.compile("^[a-zA-Z0-9._\\-]{2,64}@[a-zA-Z]{2,32}$");
    private static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]{3}$");
    private static final Pattern PINCODE = Pattern.compile("^[0-9]{5,6}$");

    private final Map<String, Object> body;
    private final Map<String, Object> problems = new LinkedHashMap<>();

    public Validator(Map<String, Object> body) {
        this.body = body == null ? Map.of() : body;
    }

    public static Validator of(Map<String, Object> body) { return new Validator(body); }

    public Map<String, Object> body() { return body; }

    public boolean hasErrors() { return !problems.isEmpty(); }

    public Map<String, Object> problems() { return problems; }

    public Validator fail(String field, String message) {
        problems.putIfAbsent(field, message);
        return this;
    }

    public Validator check(boolean condition, String field, String message) {
        if (!condition) fail(field, message);
        return this;
    }

    public void validate() {
        if (!problems.isEmpty()) throw ApiException.validation(problems);
    }

    public void validate(String action) {
        if (!problems.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fields", problems);
            details.put("action", action);
            throw new ApiException(422, "VALIDATION", "Some fields need attention", details);
        }
    }

    // ------------------------------------------------------------------ readers

    public Object raw(String field) { return body.get(field); }

    public String string(String field) { return string(field, 0, 512, false); }

    public String required(String field) { return string(field, 1, 512, true); }

    public String required(String field, int max) { return string(field, 1, max, true); }

    public String optional(String field, int max) { return string(field, 0, max, false); }

    public String string(String field, int min, int max, boolean required) {
        Object value = body.get(field);
        String text = value == null ? null : JsonMapper.asText(value).trim();
        if (text == null || text.isEmpty()) {
            if (required) fail(field, "This field is required");
            return null;
        }
        if (text.length() < min) { fail(field, "Use at least " + min + " characters"); return text; }
        if (text.length() > max) { fail(field, "Keep this under " + max + " characters"); return Util.truncate(text, max); }
        return text;
    }

    public String email(String field, boolean required) {
        String value = string(field, 0, 180, required);
        if (value == null) return null;
        if (!EMAIL.matcher(value).matches()) { fail(field, "Enter a valid e-mail address"); return value; }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    public String phone(String field, boolean required) {
        String value = string(field, 0, 20, required);
        if (value == null) return null;
        if (!PHONE.matcher(value).matches()) { fail(field, "Enter a valid phone number"); return value; }
        return value;
    }

    public String username(String field, boolean required) {
        String value = string(field, 0, 32, required);
        if (value == null) return null;
        if (!USERNAME.matcher(value).matches()) { fail(field, "3\u201332 characters: letters, numbers, dot, dash, underscore"); return value; }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    public String code(String field, boolean required) {
        String value = string(field, 0, 48, required);
        if (value == null) return null;
        if (!CODE.matcher(value).matches()) { fail(field, "Letters, numbers, dot, dash, slash only"); return value; }
        return value;
    }

    public String url(String field, boolean required) {
        String value = string(field, 0, 300, required);
        if (value == null) return null;
        if (!URL.matcher(value).matches()) fail(field, "Enter a full http(s) address");
        return value;
    }

    public String upi(String field, boolean required) {
        String value = string(field, 0, 80, required);
        if (value == null) return null;
        if (!UPI.matcher(value).matches()) fail(field, "Enter a valid UPI id, e.g. name@bank");
        return value;
    }

    public String gstin(String field, boolean required) {
        String value = string(field, 0, 20, required);
        if (value == null) return null;
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        if (!GSTIN.matcher(upper).matches()) fail(field, "Enter a valid 15-character GSTIN");
        return upper;
    }

    public String pincode(String field, boolean required) {
        String value = string(field, 0, 6, required);
        if (value == null) return null;
        if (!PINCODE.matcher(value).matches()) fail(field, "Enter a valid PIN code");
        return value;
    }

    /** Strips control characters and angle brackets that have no business in a stored note. */
    public String text(String field, int max, boolean required) {
        String value = string(field, 0, max, required);
        return value == null ? null : sanitise(value);
    }

    public static String sanitise(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]", "");
        cleaned = cleaned.replace("<", "\u2039").replace(">", "\u203A");
        return cleaned.trim();
    }

    public int integer(String field, int min, int max, int fallback) {
        Object value = body.get(field);
        if (value == null || (value instanceof String s && s.isBlank())) return fallback;
        long parsed = JsonMapper.asLong(value);
        if (parsed < min || parsed > max) {
            fail(field, "Enter a number between " + min + " and " + max);
            return fallback;
        }
        return (int) parsed;
    }

    public BigDecimal money(String field, BigDecimal min, BigDecimal max, boolean required) {
        Object value = body.get(field);
        if (value == null || (value instanceof String s && s.isBlank())) {
            if (required) fail(field, "Enter an amount");
            return BigDecimal.ZERO;
        }
        BigDecimal amount = Money.of(value);
        if (min != null && amount.compareTo(min) < 0) { fail(field, "Amount must be at least " + Money.inr(min)); return amount; }
        if (max != null && amount.compareTo(max) > 0) { fail(field, "Amount must not exceed " + Money.inr(max)); return amount; }
        return amount;
    }

    public boolean bool(String field, boolean fallback) {
        Object value = body.get(field);
        if (value == null) return fallback;
        return JsonMapper.asBoolean(value);
    }

    public LocalDate date(String field, boolean required) {
        Object value = body.get(field);
        if (value == null || JsonMapper.asText(value).isBlank()) {
            if (required) fail(field, "Choose a date");
            return null;
        }
        LocalDate parsed = JsonMapper.asDate(value);
        if (parsed == null) fail(field, "Use the format YYYY-MM-DD");
        return parsed;
    }

    public LocalDateTime dateTime(String field, boolean required) {
        Object value = body.get(field);
        if (value == null || JsonMapper.asText(value).isBlank()) {
            if (required) fail(field, "Choose a date and time");
            return null;
        }
        LocalDateTime parsed = JsonMapper.asDateTime(value);
        if (parsed == null) fail(field, "Use the format YYYY-MM-DDTHH:mm");
        return parsed;
    }

    public List<String> strings(String field, int maxItems, int maxItemLength) {
        List<String> values = JsonMapper.asStringList(body.get(field));
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            String item = sanitise(value);
            if (item == null || item.isBlank()) continue;
            if (item.length() > maxItemLength) item = item.substring(0, maxItemLength);
            if (!cleaned.contains(item)) cleaned.add(item);
            if (cleaned.size() >= maxItems) break;
        }
        return cleaned;
    }

    public <E extends Enum<E>> E oneOf(String field, Class<E> type, E fallback, boolean required) {
        String value = string(field, 0, 40, required);
        if (value == null) return fallback;
        E parsed = JsonMapper.from(value, type, null);
        if (parsed == null) {
            List<String> options = new ArrayList<>();
            for (E constant : type.getEnumConstants()) options.add(constant.name());
            fail(field, "Choose one of " + Util.join(options, ", "));
            return fallback;
        }
        return parsed;
    }

    public Role role(String field, Role fallback) {
        String value = string(field, 0, 20, false);
        return value == null ? fallback : Role.parse(value);
    }

    /** Card number: digits only, 12\u201319 long, Luhn-valid. */
    public String cardNumber(String field) {
        String value = JsonMapper.asText(body.get(field));
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) { fail(field, "Enter the card number"); return null; }
        if (digits.length() < 12 || digits.length() > 19) { fail(field, "A card number has 12\u201319 digits"); return digits; }
        if (!luhn(digits)) { fail(field, "That card number failed its checksum"); return digits; }
        return digits;
    }

    public String cardExpiry(String field) {
        String value = JsonMapper.asText(body.get(field));
        if (value == null || value.isBlank()) { fail(field, "Enter the expiry (MM/YY)"); return null; }
        String cleaned = value.trim().replaceAll("[^0-9]", "");
        if (cleaned.length() != 4) { fail(field, "Expiry must be MM/YY"); return null; }
        int month = Util.parseInt(cleaned.substring(0, 2), 0);
        int year = 2000 + Util.parseInt(cleaned.substring(2), 0);
        if (month < 1 || month > 12) { fail(field, "Month must be 01\u201312"); return null; }
        LocalDate expiry = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        if (expiry.isBefore(LocalDate.now())) { fail(field, "That card has expired"); return null; }
        if (expiry.isAfter(LocalDate.now().plusYears(20))) { fail(field, "That expiry is too far ahead"); return null; }
        return String.format("%02d/%02d", month, year % 100);
    }

    public String cvv(String field) {
        String value = JsonMapper.asText(body.get(field));
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.length() < 3 || digits.length() > 4) { fail(field, "CVV is 3 or 4 digits"); return null; }
        return digits;
    }

    public String otp(String field, int length) {
        String value = JsonMapper.asText(body.get(field));
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.length() != length) { fail(field, "Enter the " + length + "-digit code"); return null; }
        return digits;
    }

    /** The Luhn mod-10 checksum every card network uses. */
    public static boolean luhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') return false;
            int value = c - '0';
            if (doubleIt) {
                value *= 2;
                if (value > 9) value -= 9;
            }
            sum += value;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
