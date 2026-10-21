package com.sillage.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Assorted helpers: ids, text, dates, money-adjacent maths, hashing and CSV. */
public final class Util {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a");
    public static final DateTimeFormatter TIME_24 = DateTimeFormatter.ofPattern("HH:mm");
    public static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    private Util() {}

    // ------------------------------------------------------------------ identity

    /** Compact, url-safe, collision-resistant id, e.g. {@code cus_4k1z9x2m}. */
    public static String id(String prefix) {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(prefix == null ? "" : prefix + "_");
        long v = 0;
        for (byte b : bytes) v = (v << 8) | (b & 0xff);
        v = Math.abs(v);
        if (v == 0) v = 1;
        while (v > 0) {
            sb.append(ALPHABET[(int) (v % 36)]);
            v /= 36;
        }
        return sb.toString();
    }

    public static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public static String randomDigits(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append((char) ('0' + RANDOM.nextInt(10)));
        return sb.toString();
    }

    public static int randomInt(int bound) {
        return RANDOM.nextInt(bound);
    }

    // ------------------------------------------------------------------ hashing

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String hmacSha256(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Length-independent comparison; never short-circuits on the first differing byte. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ text

    public static String nz(String s) { return s == null ? "" : s; }

    public static String nz(String s, String fallback) { return s == null || s.isBlank() ? fallback : s; }

    public static boolean blank(String s) { return s == null || s.isBlank(); }

    public static boolean present(String s) { return s != null && !s.isBlank(); }

    public static String trim(String s) { return s == null ? null : s.trim(); }

    public static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    /** Lower-case, de-accented, non-alphanumeric-collapsed — used by search and routing. */
    public static String normalise(String s) {
        if (s == null) return "";
        String decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        String stripped = DIACRITICS.matcher(decomposed).replaceAll("");
        return NON_WORD.matcher(stripped.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    public static String slugify(String s) {
        return normalise(s).replace(' ', '-');
    }

    public static List<String> tokens(String s) {
        String normalised = normalise(s);
        if (normalised.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : normalised.split(" ")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    public static String initials(String name) {
        if (blank(name)) return "\u2014";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && sb.length() < 2; i++) {
            String p = parts[i].replaceAll("[^A-Za-z\\u0900-\\u097F]", "");
            if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0)));
        }
        return sb.length() == 0 ? "\u2014" : sb.toString();
    }

    public static String titleCase(String s) {
        if (blank(s)) return "";
        String[] words = s.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            if (w.length() < 3 && !w.equals("spa")) sb.append(w.toUpperCase(Locale.ROOT));
            else sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    public static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String join(Collection<?> items, String separator) {
        StringBuilder sb = new StringBuilder();
        for (Object o : items) {
            if (o == null) continue;
            if (sb.length() > 0) sb.append(separator);
            sb.append(o);
        }
        return sb.toString();
    }

    public static String humanList(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + " & " + items.get(1);
        return join(items.subList(0, items.size() - 1), ", ") + " & " + items.get(items.size() - 1);
    }

    /** Masks anything sensitive down to the last {@code keep} characters. */
    public static String mask(String value, int keep) {
        if (value == null) return "";
        String trimmed = value.replaceAll("\\s", "");
        if (trimmed.length() <= keep) return "\u2022\u2022\u2022\u2022";
        return "\u2022".repeat(Math.max(4, trimmed.length() - keep)) + trimmed.substring(trimmed.length() - keep);
    }

    // ------------------------------------------------------------------ numbers

    /** Keeps the last four digits of a phone number: 98xxx xx432. */
    public static String maskPhone(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.length() <= 4) return digits.isEmpty() ? "" : "\u2022".repeat(digits.length());
        return digits.substring(0, Math.min(3, digits.length() - 4)) + "\u2022\u2022\u2022 "
                + digits.substring(digits.length() - 4);
    }

    /** Keeps the first character of an address: a\u2022\u2022\u2022\u2022@example.com */
    public static String maskEmail(String value) {
        if (value == null || !value.contains("@")) return nz(value);
        int at = value.indexOf('@');
        String local = value.substring(0, at);
        String domain = value.substring(at);
        if (local.length() <= 2) return local.charAt(0) + "\u2022\u2022\u2022" + domain;
        return local.charAt(0) + "\u2022".repeat(Math.min(5, local.length() - 1)) + domain;
    }

    /** Keeps the first {@code keep} characters of a card or token. */
    public static String maskCard(String value, int keep) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.length() <= keep) return mask(digits, 0);
        return "\u2022".repeat(Math.max(4, digits.length() - keep)) + digits.substring(digits.length() - keep);
    }

    public static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    public static long clamp(long v, long min, long max) { return Math.max(min, Math.min(max, v)); }

    public static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }

    public static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    public static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    public static double percent(double part, double whole) {
        return whole <= 0 ? 0 : round(part / whole * 100.0, 1);
    }

    // ------------------------------------------------------------------ dates & time

    public static LocalDate today() { return LocalDate.now(); }

    public static LocalDateTime now() { return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS); }

    public static String dayName(DayOfWeek d) { return d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH); }

    public static String format(LocalDateTime dt) { return dt == null ? "" : STAMP.format(dt); }

    public static String formatTime(LocalTime t) { return t == null ? "" : TIME.format(t); }

    public static String formatTime(LocalDateTime dt) { return dt == null ? "" : TIME.format(dt); }

    public static String formatDate(LocalDate d) { return d == null ? "" : DATE.format(d); }

    public static String humanDuration(int minutes) {
        if (minutes < 60) return minutes + " min";
        int h = minutes / 60, m = minutes % 60;
        return m == 0 ? (h + (h == 1 ? " hour" : " hours")) : h + "h " + m + "m";
    }

    public static String relativeTime(LocalDateTime when) {
        if (when == null) return "";
        LocalDateTime now = LocalDateTime.now();
        boolean future = when.isAfter(now);
        Duration d = Duration.between(future ? now : when, future ? when : now);
        long minutes = d.toMinutes();
        String unit;
        if (minutes < 1) unit = "just now";
        else if (minutes < 60) unit = minutes + " min" + (minutes == 1 ? "" : "s");
        else if (minutes < 60 * 24) unit = (minutes / 60) + " hr" + (minutes / 60 == 1 ? "" : "s");
        else if (minutes < 60 * 24 * 30) unit = (minutes / 1440) + " day" + (minutes / 1440 == 1 ? "" : "s");
        else unit = (minutes / 43200) + " month" + (minutes / 43200 == 1 ? "" : "s");
        if (unit.equals("just now")) return unit;
        return future ? "in " + unit : unit + " ago";
    }

    public static Instant instantOf(LocalDateTime dt) {
        return dt == null ? Instant.EPOCH : dt.atZone(java.time.ZoneId.systemDefault()).toInstant();
    }

    // ------------------------------------------------------------------ maps & lists

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object value = kv[i + 1];
            if (value == null) continue;
            m.put(String.valueOf(kv[i]), value);
        }
        return m;
    }

    public static List<String> list(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) if (v != null && !v.isBlank()) out.add(v);
        return out;
    }

    public static <T> List<T> nullSafe(List<T> list) { return list == null ? new ArrayList<>() : list; }

    public static <T> List<T> sub(List<T> list, int max) {
        if (list == null) return List.of();
        return list.size() <= max ? list : new ArrayList<>(list.subList(0, max));
    }

    public static boolean containsIgnoreCase(Collection<String> haystack, String needle) {
        if (haystack == null || needle == null) return false;
        for (String s : haystack) if (s != null && s.equalsIgnoreCase(needle)) return true;
        return false;
    }

    // ------------------------------------------------------------------ csv

    public static String toCsv(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendCsvRow(sb, header);
        for (List<String> row : rows) appendCsvRow(sb, row);
        return sb.toString();
    }

    private static void appendCsvRow(StringBuilder sb, List<String> row) {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvCell(row.get(i)));
        }
        sb.append("\r\n");
    }

    public static String csvCell(String value) {
        String s = value == null ? "" : value;
        // A leading =, +, - or @ can turn a CSV cell into a spreadsheet formula.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needsQuotes) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
