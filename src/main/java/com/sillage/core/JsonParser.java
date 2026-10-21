package com.sillage.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, dependency-free recursive-descent JSON parser.
 *
 * <p>Produced value graph:
 * <ul>
 *   <li>object  -&gt; {@code LinkedHashMap<String,Object>} (insertion order preserved)</li>
 *   <li>array   -&gt; {@code ArrayList<Object>}</li>
 *   <li>string  -&gt; {@code String}</li>
 *   <li>integer -&gt; {@code Long}</li>
 *   <li>decimal -&gt; {@code BigDecimal} (money must never touch binary floating point)</li>
 *   <li>true/false -&gt; {@code Boolean}, null -&gt; {@code null}</li>
 * </ul>
 */
public final class JsonParser {

    private static final int MAX_DEPTH = 64;

    private final String src;
    private int pos;
    private int depth;

    private JsonParser(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        if (text == null) throw new JsonException("empty input");
        JsonParser p = new JsonParser(text);
        p.whitespace();
        Object value = p.readValue();
        p.whitespace();
        if (p.pos < p.src.length()) throw p.error("unexpected trailing content");
        return value;
    }

    /** Lenient parse: returns {@code null} instead of throwing when input is blank or invalid. */
    public static Object parseOr(String text, Object fallback) {
        if (text == null || text.isBlank()) return fallback;
        try {
            return parse(text);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private Object readValue() {
        if (++depth > MAX_DEPTH) throw error("maximum nesting depth exceeded");
        try {
            if (pos >= src.length()) throw error("unexpected end of input");
            char c = src.charAt(pos);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': expect("true");  return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null");  return null;
                default:  return readNumber();
            }
        } finally {
            depth--;
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // '{'
        whitespace();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            whitespace();
            if (peek() != '"') throw error("expected object key");
            String key = readString();
            whitespace();
            if (peek() != ':') throw error("expected ':' after key");
            pos++;
            whitespace();
            map.put(key, readValue());
            whitespace();
            char c = next();
            if (c == '}') return map;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // '['
        whitespace();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            whitespace();
            list.add(readValue());
            whitespace();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private String readString() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw error("unterminated string");
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= src.length()) throw error("unterminated escape");
                char e = src.charAt(pos++);
                switch (e) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) throw error("bad unicode escape");
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: throw error("unknown escape '\\" + e + "'");
                }
            } else if (c < 0x20) {
                throw error("unescaped control character in string");
            } else {
                sb.append(c);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-' || peek() == '+') pos++;
        boolean decimal = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') { pos++; continue; }
            if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { decimal = decimal || c != '+' && c != '-'; pos++; continue; }
            break;
        }
        if (start == pos) throw error("expected a value");
        String text = src.substring(start, pos);
        try {
            if (!decimal && text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
                long l = Long.parseLong(text);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return l; // boxed as Long; mapper narrows
                return l;
            }
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw error("malformed number '" + text + "'");
        }
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) throw error("expected '" + literal + "'");
        pos += literal.length();
    }

    private void whitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    private char peek() {
        if (pos >= src.length()) throw error("unexpected end of input");
        return src.charAt(pos);
    }

    private char next() {
        if (pos >= src.length()) throw error("unexpected end of input");
        return src.charAt(pos++);
    }

    private JsonException error(String message) {
        int line = 1, col = 1;
        for (int k = 0; k < Math.min(pos, src.length()); k++) {
            if (src.charAt(k) == '\n') { line++; col = 1; } else col++;
        }
        return new JsonException(message + " (line " + line + ", column " + col + ")");
    }

    /** Raised for any malformed JSON payload. */
    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
    }
}
