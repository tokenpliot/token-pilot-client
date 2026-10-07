package io.tokenpilot.client.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON codec so the client has no runtime dependencies (applications on Jackson 2 and Jackson 3 can both
 * use it). Writing covers the request shapes this client sends; parsing is complete RFC 8259 and maps objects to
 * {@link Map}, arrays to {@link List}, numbers to {@link BigDecimal}.
 */
public final class Json {

    private Json() {
    }

    // --- writing ---

    public static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // --- parsing ---

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("trailing characters");
        }
        return value;
    }

    private static final class Parser {

        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return position >= text.length();
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = text.charAt(position);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                position++;
            }
        }

        Object readValue() {
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(position);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield readNumber();
                    }
                    throw error("unexpected character");
                }
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> object = new LinkedHashMap<>();
            position++;
            skipWhitespace();
            if (peek('}')) {
                position++;
                return object;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw error("expected a string key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                object.put(key, readValue());
                skipWhitespace();
                if (peek(',')) {
                    position++;
                    continue;
                }
                expect('}');
                return object;
            }
        }

        private List<Object> readArray() {
            List<Object> array = new ArrayList<>();
            position++;
            skipWhitespace();
            if (peek(']')) {
                position++;
                return array;
            }
            while (true) {
                skipWhitespace();
                array.add(readValue());
                skipWhitespace();
                if (peek(',')) {
                    position++;
                    continue;
                }
                expect(']');
                return array;
            }
        }

        private String readString() {
            position++;
            StringBuilder value = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return value.toString();
                }
                if (c < 0x20) {
                    throw error("control character in string");
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("unterminated escape");
                }
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw error("truncated unicode escape");
                        }
                        try {
                            value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        } catch (NumberFormatException exception) {
                            throw error("invalid unicode escape");
                        }
                        position += 4;
                    }
                    default -> throw error("invalid escape");
                }
            }
        }

        private BigDecimal readNumber() {
            int start = position;
            if (peek('-')) {
                position++;
            }
            while (!atEnd() && "0123456789.eE+-".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            try {
                return new BigDecimal(text.substring(start, position));
            } catch (NumberFormatException exception) {
                throw error("invalid number");
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, position)) {
                throw error("invalid literal");
            }
            position += literal.length();
            return value;
        }

        private boolean peek(char c) {
            return !atEnd() && text.charAt(position) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            position++;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Invalid JSON at position " + position + ": " + message);
        }
    }
}
