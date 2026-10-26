package de.rothner.qc45;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Strict parsing for the backend decision used by fleet AutoCharge. */
final class OcppAuthorization {
    static boolean accepted(String json, long now) {
        try {
            Object root = new Parser(json).parse();
            if (!(root instanceof Map)) return false;
            Object info = ((Map<?,?>)root).get("idTagInfo");
            if (!(info instanceof Map)) return false;
            Map<?,?> fields = (Map<?,?>)info;
            if (!"Accepted".equals(fields.get("status"))) return false;
            if (!fields.containsKey("expiryDate")) return true;
            Object expiry = fields.get("expiryDate");
            if (!(expiry instanceof String) || now < 1704067200000L) return false;
            String value = (String)expiry;
            String pattern = value.indexOf('.') >= 0
                ? "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" : "yyyy-MM-dd'T'HH:mm:ssXXX";
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
            format.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            java.util.Date date = format.parse(value, position);
            return date != null && position.getIndex() == value.length() && date.getTime() > now;
        } catch (RuntimeException e) { return false; }
    }

    static int transactionId(String json) {
        try {
            Object root = new Parser(json).parse();
            if (!(root instanceof Map)) return -1;
            Object value = ((Map<?,?>)root).get("transactionId");
            if (!(value instanceof java.math.BigDecimal)) return -1;
            int id = ((java.math.BigDecimal)value).intValueExact();
            return id >= 0 ? id : -1;
        } catch (RuntimeException e) { return -1; }
    }

    /** Small bounded JSON reader; duplicate keys and trailing input are errors. */
    private static final class Parser {
        final String text;
        int at;
        Parser(String text) {
            if (text == null || text.length() > 65536) throw new IllegalArgumentException("JSON size");
            this.text = text;
        }
        Object parse() {
            Object value = value(0);
            space();
            if (at != text.length()) fail();
            return value;
        }
        Object value(int depth) {
            if (depth > 12) fail();
            space();
            if (at >= text.length()) fail();
            char c = text.charAt(at);
            if (c == '"') return string();
            if (c == '{') {
                at++;
                Map<String,Object> result = new HashMap<String,Object>();
                if (take('}')) return result;
                do {
                    space();
                    String key = string();
                    if (!take(':') || result.containsKey(key)) fail();
                    result.put(key, value(depth + 1));
                    if (take('}')) return result;
                } while (take(','));
                fail();
            }
            if (c == '[') {
                at++;
                if (take(']')) return Boolean.FALSE;
                do {
                    value(depth + 1);
                    if (take(']')) return Boolean.FALSE;
                } while (take(','));
                fail();
            }
            for (String literal : new String[]{"true", "false", "null"}) {
                if (text.startsWith(literal, at)) { at += literal.length(); return null; }
            }
            int start = at;
            while (at < text.length() && "-+0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
            String number = text.substring(start, at);
            if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) fail();
            return new java.math.BigDecimal(number);
        }
        String string() {
            if (at >= text.length() || text.charAt(at++) != '"') { fail(); }
            StringBuilder result = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return result.toString();
                if (c < 32) fail();
                if (c == '\\') {
                    if (at >= text.length()) fail();
                    c = text.charAt(at++);
                    int index = "\"\\/bfnrt".indexOf(c);
                    if (index >= 0) c = "\"\\/\b\f\n\r\t".charAt(index);
                    else if (c == 'u' && at + 4 <= text.length()) {
                        c = (char)Integer.parseInt(text.substring(at, at + 4), 16);
                        at += 4;
                    } else fail();
                }
                result.append(c);
            }
            fail();
            return "";
        }
        boolean take(char c) {
            space();
            if (at < text.length() && text.charAt(at) == c) { at++; return true; }
            return false;
        }
        void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
        void fail() { throw new IllegalArgumentException("invalid OCPP JSON"); }
    }
}
