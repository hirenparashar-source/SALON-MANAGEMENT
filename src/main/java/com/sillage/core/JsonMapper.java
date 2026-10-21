package com.sillage.core;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflection based mapper between the plain JSON value graph produced by {@link JsonParser}
 * and immutable Java records.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Records are the persistence and transport format — no annotations, no bytecode magic.</li>
 *   <li>Missing keys fall back to a sensible default (0 / false / empty collection / ZERO money),
 *       so schema evolution never breaks an existing on-disk document.</li>
 *   <li>Unknown keys are ignored, which keeps old data files readable by newer builds.</li>
 *   <li>{@link #toMap(Object)} + {@link #merge(Map, Map)} give PATCH semantics for free.</li>
 * </ul>
 */
public final class JsonMapper {

    private JsonMapper() {}

    // ------------------------------------------------------------------ json -> record

    @SuppressWarnings("unchecked")
    public static <T> T from(Object json, Class<T> type, Type genericType) {
        if (json == null) return (T) defaultValue(type, genericType);
        if (type == Object.class || type == null) return (T) json;

        if (type == String.class || type == CharSequence.class) return (T) asText(json);
        if (type == boolean.class || type == Boolean.class) return (T) Boolean.valueOf(asBoolean(json));
        if (type == int.class || type == Integer.class) return (T) Integer.valueOf((int) asLong(json));
        if (type == long.class || type == Long.class) return (T) Long.valueOf(asLong(json));
        if (type == short.class || type == Short.class) return (T) Short.valueOf((short) asLong(json));
        if (type == byte.class || type == Byte.class) return (T) Byte.valueOf((byte) asLong(json));
        if (type == double.class || type == Double.class) return (T) Double.valueOf(asDecimal(json).doubleValue());
        if (type == float.class || type == Float.class) return (T) Float.valueOf(asDecimal(json).floatValue());
        if (type == BigDecimal.class) return (T) asDecimal(json);

        if (type.isEnum()) return (T) asEnum(json, type);
        if (type == Instant.class) return (T) asInstant(json);
        if (type == LocalDate.class) return (T) asDate(json);
        if (type == LocalDateTime.class) return (T) asDateTime(json);
        if (type == LocalTime.class) return (T) asTime(json);

        if (type.isArray()) {
            List<Object> items = asList(json);
            Class<?> component = type.getComponentType();
            Object array = Array.newInstance(component, items.size());
            for (int i = 0; i < items.size(); i++) Array.set(array, i, from(items.get(i), component, null));
            return (T) array;
        }

        if (Collection.class.isAssignableFrom(type)) {
            Class<?> element = typeArgument(genericType, 0, Object.class);
            List<Object> items = asList(json);
            Collection<Object> out = Set.class.isAssignableFrom(type) ? new LinkedHashSet<>() : new ArrayList<>();
            for (Object item : items) out.add(from(item, element, null));
            return (T) out;
        }

        if (Map.class.isAssignableFrom(type)) {
            Class<?> valueType = typeArgument(genericType, 1, Object.class);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : asMap(json).entrySet()) out.put(String.valueOf(e.getKey()), from(e.getValue(), valueType, null));
            return (T) out;
        }

        Map<?, ?> source = asMap(json);
        RecordComponent[] components = Json.components(type);
        if (components != null) {
            Object[] args = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent rc = components[i];
                types[i] = rc.getType();
                Object raw = source.get(rc.getName());
                args[i] = raw == null && !source.containsKey(rc.getName())
                        ? defaultValue(rc.getType(), rc.getGenericType())
                        : from(raw, rc.getType(), rc.getGenericType());
            }
            try {
                Constructor<T> ctor = type.getDeclaredConstructor(types);
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("cannot construct " + type.getName() + ": " + rootMessage(e), e);
            }
        }

        // Plain mutable bean fallback (no-arg constructor + field injection by name).
        try {
            Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            T instance = ctor.newInstance();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !source.containsKey(f.getName())) continue;
                    f.setAccessible(true);
                    f.set(instance, from(source.get(f.getName()), f.getType(), f.getGenericType()));
                }
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("cannot map JSON object onto " + type.getName() + ": " + rootMessage(e), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        return from(JsonParser.parse(json), type, null);
    }

    // ------------------------------------------------------------------ record -> map

    /** Structural conversion into plain Map/List/scalars — the basis of PATCH and diffing. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object value) {
        Object plain = toPlain(value);
        if (plain instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new IllegalArgumentException("expected a record/object, got " + (value == null ? "null" : value.getClass().getName()));
    }

    private static Object toPlain(Object value) {
        if (value == null) return null;
        if (value instanceof String || value instanceof Boolean || value instanceof Number || value instanceof Character) return value;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof Instant || value instanceof LocalDate || value instanceof LocalDateTime || value instanceof LocalTime) return value.toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), toPlain(e.getValue()));
            return out;
        }
        if (value instanceof Collection<?> col) {
            List<Object> out = new ArrayList<>(col.size());
            for (Object o : col) out.add(toPlain(o));
            return out;
        }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) out.add(toPlain(Array.get(value, i)));
            return out;
        }
        RecordComponent[] components = Json.components(value.getClass());
        if (components != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (RecordComponent rc : components) {
                if (rc.isAnnotationPresent(JsonExclude.class)) continue;
                try {
                    out.put(rc.getName(), toPlain(rc.getAccessor().invoke(value)));
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // unreadable component: skip
                }
            }
            return out;
        }
        return String.valueOf(value);
    }

    /** Returns {@code base} with every non-null entry of {@code patch} applied (shallow). */
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> patch) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        if (patch != null) out.putAll(patch);
        return out;
    }

    // ------------------------------------------------------------------ coercion helpers

    public static String asText(Object json) {
        if (json == null) return null;
        if (json instanceof String s) return s;
        if (json instanceof Number || json instanceof Boolean || json instanceof Character) return json.toString();
        if (json instanceof BigDecimal bd) return bd.toPlainString();
        if (json instanceof Instant || json instanceof LocalDate || json instanceof LocalDateTime || json instanceof LocalTime) return json.toString();
        return Json.write(json);
    }

    public static boolean asBoolean(Object json) {
        if (json == null) return false;
        if (json instanceof Boolean b) return b;
        if (json instanceof Number n) return n.doubleValue() != 0;
        String s = asText(json).trim();
        return s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("on");
    }

    public static long asLong(Object json) {
        if (json == null) return 0L;
        if (json instanceof Number n) return n.longValue();
        String s = asText(json).trim().replace(",", "");
        if (s.isEmpty()) return 0L;
        try {
            return s.indexOf('.') >= 0 ? new BigDecimal(s).longValue() : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static int asInt(Object json, int fallback) {
        if (json == null) return fallback;
        long l = asLong(json);
        return l > Integer.MAX_VALUE || l < Integer.MIN_VALUE ? fallback : (int) l;
    }

    public static BigDecimal asDecimal(Object json) {
        if (json == null) return BigDecimal.ZERO;
        if (json instanceof BigDecimal bd) return bd;
        if (json instanceof Long l) return BigDecimal.valueOf(l);
        if (json instanceof Number n) return new BigDecimal(n.toString());
        String s = asText(json).trim().replace(",", "").replaceAll("[^0-9.+-]", "");
        if (s.isEmpty() || s.equals("-") || s.equals("+") || s.equals(".")) return BigDecimal.ZERO;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    public static double asDouble(Object json, double fallback) {
        if (json == null) return fallback;
        BigDecimal d = asDecimal(json);
        return d == BigDecimal.ZERO && !(json instanceof Number) && asText(json).isBlank() ? fallback : d.doubleValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Enum<?> asEnum(Object json, Class<?> type) {
        String name = asText(json);
        if (name == null || name.isBlank()) return null;
        String normalised = name.trim().replace(' ', '_').replace('-', '_');
        for (Object constant : type.getEnumConstants()) {
            Enum<?> e = (Enum<?>) constant;
            if (e.name().equalsIgnoreCase(normalised)) return e;
        }
        try {
            return Enum.valueOf((Class<Enum>) type, normalised.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Instant asInstant(Object json) {
        if (json == null) return null;
        if (json instanceof Instant i) return i;
        String s = asText(json).trim();
        if (s.isEmpty()) return null;
        try {
            if (s.endsWith("Z") || s.contains("+") || s.matches(".*T.*:.*:.*")) return Instant.parse(s);
            return LocalDateTime.parse(s).atZone(java.time.ZoneOffset.UTC).toInstant();
        } catch (RuntimeException e) {
            try {
                return LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    public static LocalDate asDate(Object json) {
        if (json == null) return null;
        if (json instanceof LocalDate d) return d;
        String s = asText(json).trim();
        if (s.isEmpty()) return null;
        try {
            if (s.length() > 10) return LocalDateTime.parse(s).toLocalDate();
            return LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static LocalDateTime asDateTime(Object json) {
        if (json == null) return null;
        if (json instanceof LocalDateTime d) return d;
        String s = asText(json).trim();
        if (s.isEmpty()) return null;
        try {
            if (s.endsWith("Z")) return Instant.parse(s).atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
            if (s.length() == 10) return LocalDate.parse(s).atStartOfDay();
            return LocalDateTime.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static LocalTime asTime(Object json) {
        if (json == null) return null;
        if (json instanceof LocalTime t) return t;
        String s = asText(json).trim();
        if (s.isEmpty()) return null;
        try {
            if (s.length() == 5) s = s + ":00";
            return LocalTime.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static List<Object> asList(Object json) {
        if (json == null) return List.of();
        if (json instanceof List<?> l) return new ArrayList<>(l);
        if (json instanceof Collection<?> c) return new ArrayList<>(c);
        if (json.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int len = Array.getLength(json);
            for (int i = 0; i < len; i++) out.add(Array.get(json, i));
            return out;
        }
        List<Object> single = new ArrayList<>(1);
        single.add(json);
        return single;
    }

    public static Map<String, Object> asMap(Object json) {
        if (json instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        return new LinkedHashMap<>();
    }

    public static List<String> asStringList(Object json) {
        List<String> out = new ArrayList<>();
        for (Object o : asList(json)) {
            String s = asText(o);
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    // ------------------------------------------------------------------ internals

    private static Object defaultValue(Class<?> type, Type genericType) {
        if (!type.isPrimitive()) {
            if (type == BigDecimal.class) return BigDecimal.ZERO;
            if (List.class.isAssignableFrom(type)) return new ArrayList<>();
            if (Set.class.isAssignableFrom(type)) return new LinkedHashSet<>();
            if (Map.class.isAssignableFrom(type)) return new LinkedHashMap<>();
            if (Collection.class.isAssignableFrom(type)) return new ArrayList<>();
            return null;
        }
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    private static Class<?> typeArgument(Type genericType, int index, Class<?> fallback) {
        if (genericType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (index < args.length && args[index] instanceof Class<?> c) return c;
        }
        return fallback;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
