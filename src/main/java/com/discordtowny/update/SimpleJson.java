package com.discordtowny.update;

import java.util.*;
import java.util.stream.Stream;

/**
 * Lightweight, zero-dependency JSON parser designed to safely parse GitHub releases API responses.
 *
 * <p>Supports standard JSON objects, arrays, strings (with escapes), numbers, booleans, and null.
 * Throws {@link IllegalArgumentException} on malformed JSON.
 */
public final class SimpleJson {

    private SimpleJson() {}

    public sealed interface JsonValue permits JsonObject, JsonArray, JsonPrimitive, JsonNull {}

    public record JsonObject(Map<String, JsonValue> members) implements JsonValue {
        public JsonObject {
            members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        }

        public boolean contains(String key) {
            return members.containsKey(key);
        }

        public JsonValue get(String key) {
            return members.get(key);
        }

        public String getString(String key) {
            JsonValue val = members.get(key);
            if (val instanceof JsonPrimitive prim && prim.value() instanceof String s) {
                return s;
            }
            return null;
        }

        public Long getLong(String key, Long defaultValue) {
            JsonValue val = members.get(key);
            if (val instanceof JsonPrimitive prim && prim.value() instanceof Number n) {
                return n.longValue();
            }
            return defaultValue;
        }

        public Integer getInt(String key, Integer defaultValue) {
            JsonValue val = members.get(key);
            if (val instanceof JsonPrimitive prim && prim.value() instanceof Number n) {
                return n.intValue();
            }
            return defaultValue;
        }

        public Boolean getBoolean(String key, Boolean defaultValue) {
            JsonValue val = members.get(key);
            if (val instanceof JsonPrimitive prim && prim.value() instanceof Boolean b) {
                return b;
            }
            return defaultValue;
        }

        public JsonObject getObject(String key) {
            JsonValue val = members.get(key);
            return val instanceof JsonObject obj ? obj : null;
        }

        public JsonArray getArray(String key) {
            JsonValue val = members.get(key);
            return val instanceof JsonArray arr ? arr : null;
        }
    }

    public record JsonArray(List<JsonValue> elements) implements JsonValue, Iterable<JsonValue> {
        public JsonArray {
            elements = Collections.unmodifiableList(new ArrayList<>(elements));
        }

        public int size() {
            return elements.size();
        }

        public boolean isEmpty() {
            return elements.isEmpty();
        }

        public JsonValue get(int index) {
            return elements.get(index);
        }

        public JsonObject getObject(int index) {
            JsonValue val = elements.get(index);
            return val instanceof JsonObject obj ? obj : null;
        }

        public String getString(int index) {
            JsonValue val = elements.get(index);
            if (val instanceof JsonPrimitive prim && prim.value() instanceof String s) {
                return s;
            }
            return null;
        }

        @Override
        public Iterator<JsonValue> iterator() {
            return elements.iterator();
        }

        public Stream<JsonValue> stream() {
            return elements.stream();
        }
    }

    public record JsonPrimitive(Object value) implements JsonValue {
        public JsonPrimitive {
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException("Primitive value must be String, Number, or Boolean: " + value);
            }
        }

        public String asString() {
            return String.valueOf(value);
        }
    }

    public enum JsonNull implements JsonValue {
        INSTANCE
    }

    /**
     * Parses a JSON string into a {@link JsonValue}.
     *
     * @param json JSON text to parse
     * @return parsed JsonValue
     * @throws IllegalArgumentException if the JSON is malformed
     */
    public static JsonValue parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON string cannot be null or blank");
        }
        Parser parser = new Parser(json);
        JsonValue result = parser.parseValue();
        parser.skipWhitespace();
        if (parser.hasMore()) {
            throw new IllegalArgumentException("Unexpected character after JSON value at position " + parser.pos);
        }
        return result;
    }

    /**
     * Parses a JSON string expected to be a {@link JsonObject}.
     */
    public static JsonObject parseObject(String json) {
        JsonValue val = parse(json);
        if (val instanceof JsonObject obj) {
            return obj;
        }
        throw new IllegalArgumentException("Expected JSON object but got " + val.getClass().getSimpleName());
    }

    private static final class Parser {
        private final String src;
        private int pos = 0;

        Parser(String src) {
            this.src = src;
        }

        boolean hasMore() {
            return pos < src.length();
        }

        char peek() {
            if (!hasMore()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            return src.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void skipWhitespace() {
            while (hasMore()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        JsonValue parseValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> new JsonPrimitive(parseString());
                case 't', 'f' -> new JsonPrimitive(parseBoolean());
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield new JsonPrimitive(parseNumber());
                    }
                    throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos);
                }
            };
        }

        JsonObject parseObject() {
            expect('{');
            Map<String, JsonValue> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                next();
                return new JsonObject(map);
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                JsonValue val = parseValue();
                map.put(key, val);
                skipWhitespace();
                char c = peek();
                if (c == '}') {
                    next();
                    break;
                }
                expect(',');
            }
            return new JsonObject(map);
        }

        JsonArray parseArray() {
            expect('[');
            List<JsonValue> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                next();
                return new JsonArray(list);
            }
            while (true) {
                JsonValue val = parseValue();
                list.add(val);
                skipWhitespace();
                char c = peek();
                if (c == ']') {
                    next();
                    break;
                }
                expect(',');
            }
            return new JsonArray(list);
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (hasMore()) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char esc = next();
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Incomplete unicode escape at position " + pos);
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new IllegalArgumentException("Invalid unicode escape: \\u" + hex);
                            }
                        }
                        default -> throw new IllegalArgumentException("Invalid escape sequence: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string literal");
        }

        Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean at position " + pos);
        }

        JsonNull parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return JsonNull.INSTANCE;
            }
            throw new IllegalArgumentException("Invalid null literal at position " + pos);
        }

        Number parseNumber() {
            int start = pos;
            if (peek() == '-') {
                next();
            }
            while (hasMore() && Character.isDigit(peek())) {
                next();
            }
            boolean isFloating = false;
            if (hasMore() && peek() == '.') {
                isFloating = true;
                next();
                while (hasMore() && Character.isDigit(peek())) {
                    next();
                }
            }
            if (hasMore() && (peek() == 'e' || peek() == 'E')) {
                isFloating = true;
                next();
                if (hasMore() && (peek() == '+' || peek() == '-')) {
                    next();
                }
                while (hasMore() && Character.isDigit(peek())) {
                    next();
                }
            }
            String numStr = src.substring(start, pos);
            try {
                if (isFloating) {
                    return Double.parseDouble(numStr);
                }
                long l = Long.parseLong(numStr);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return (int) l;
                }
                return l;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number literal '" + numStr + "' at position " + start);
            }
        }

        void expect(char expected) {
            char actual = next();
            if (actual != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' but got '" + actual + "' at position " + (pos - 1));
            }
        }
    }
}
