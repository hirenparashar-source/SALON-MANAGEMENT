package com.sillage.core;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSON writer + facade over {@link JsonParser} and {@link JsonMapper}.
 *
 * <p>Serialises plain value graphs (Map/List/String/Number/Boolean/null) as well as Java
 * records, enums, arrays, collections and {@code java.time} types — which is all the
 * application needs, without pulling in a third-party library.
 */
public final class Json {

    private static final Map<Class<?>, RecordComponent[]> COMPONENTS = new ConcurrentHashMap<>();
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private Json() {}

    // ------------------------------------------------------------------ facade

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(512);
        append(sb, value, false, 0);
        return sb.toString();
    }

    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder(1024);
        append(sb, value, true, 0);
        return sb.toString();
    }

    public static Object parse(String text) {
        return JsonParser.parse(text);
    }

    public static <T> T read(String text, Class<T> type) {
        return JsonMapper.from(parse(text), type, null);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String text) {
        Object parsed = parse(text);
        if (parsed instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new JsonParser.JsonException("expected a JSON object at the document root");
    }

    public static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    // ------------------------------------------------------------------ writer

    private static void append(StringBuilder sb, Object value, boolean pretty, int indent) {
        if (value == null) { sb.append("null"); return; }
        if (value instanceof String s) { quote(sb, s); return; }
        if (value instanceof CharSequence cs) { quote(sb, cs.toString()); return; }
        if (value instanceof Boolean b) { sb.append(b.booleanValue()); return; }
        if (value instanceof Character c) { quote(sb, c.toString()); return; }
        if (value instanceof BigDecimal bd) { sb.append(bd.toPlainString()); return; }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(trimDouble(d));
            return;
        }
        if (value instanceof Number n) { sb.append(n.toString()); return; }
        if (value instanceof Enum<?> e) { quote(sb, e.name()); return; }
        if (value instanceof Instant i) { quote(sb, DateTimeFormatter.ISO_INSTANT.format(i.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))); return; }
        if (value instanceof LocalDate d) { quote(sb, d.toString()); return; }
        if (value instanceof LocalDateTime d) { quote(sb, DT.format(d)); return; }
        if (value instanceof LocalTime t) { quote(sb, t.toString()); return; }
        if (value instanceof Temporal || value instanceof TemporalAccessor) { quote(sb, value.toString()); return; }
        if (value instanceof Optional<?> o) { append(sb, o.orElse(null), pretty, indent); return; }
        if (value instanceof Map<?, ?> map) { writeMap(sb, map, pretty, indent); return; }
        if (value instanceof Collection<?> col) { writeArray(sb, col.toArray(), pretty, indent); return; }
        if (value instanceof Iterable<?> it) { writeArray(sb, toArray(it), pretty, indent); return; }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            Object[] items = new Object[len];
            for (int i = 0; i < len; i++) items[i] = Array.get(value, i);
            writeArray(sb, items, pretty, indent);
            return;
        }
        RecordComponent[] components = components(value.getClass());
        if (components != null) { writeRecord(sb, value, components, pretty, indent); return; }
        quote(sb, String.valueOf(value));
    }

    private static Object[] toArray(Iterable<?> it) {
        java.util.List<Object> list = new java.util.ArrayList<>();
        it.forEach(list::add);
        return list.toArray();
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map, boolean pretty, int indent) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getValue() == null) continue; // nulls are omitted: absent == null on the client
            if (!first) sb.append(',');
            first = false;
            separator(sb, pretty, indent + 1);
            quote(sb, String.valueOf(e.getKey()));
            sb.append(':');
            if (pretty) sb.append(' ');
            append(sb, e.getValue(), pretty, indent + 1);
        }
        if (!first) separator(sb, pretty, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Object[] items, boolean pretty, int indent) {
        sb.append('[');
        for (int i = 0; i < items.length; i++) {
            if (i > 0) sb.append(',');
            separator(sb, pretty, indent + 1);
            append(sb, items[i], pretty, indent + 1);
        }
        if (items.length > 0) separator(sb, pretty, indent);
        sb.append(']');
    }

    private static void writeRecord(StringBuilder sb, Object value, RecordComponent[] components, boolean pretty, int indent) {
        sb.append('{');
        boolean first = true;
        for (RecordComponent rc : components) {
            if (rc.isAnnotationPresent(JsonExclude.class)) continue;
            Object field;
            try {
                field = rc.getAccessor().invoke(value);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue;
            }
            if (field == null) continue;
            if (!first) sb.append(',');
            first = false;
            separator(sb, pretty, indent + 1);
            quote(sb, rc.getName());
            sb.append(':');
            if (pretty) sb.append(' ');
            append(sb, field, pretty, indent + 1);
        }
        if (!first) separator(sb, pretty, indent);
        sb.append('}');
    }

    private static void separator(StringBuilder sb, boolean pretty, int indent) {
        if (!pretty) return;
        sb.append('\n');
        for (int i = 0; i < indent; i++) sb.append("  ");
    }

    static RecordComponent[] components(Class<?> type) {
        if (!type.isRecord()) return null;
        return COMPONENTS.computeIfAbsent(type, c -> {
            RecordComponent[] all = c.getRecordComponents();
            RecordComponent[] usable = new RecordComponent[all.length];
            for (int i = 0; i < all.length; i++) {
                all[i].getAccessor().setAccessible(true);
                usable[i] = all[i];
            }
            return usable;
        });
    }

    private static String trimDouble(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String s = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        return s;
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
