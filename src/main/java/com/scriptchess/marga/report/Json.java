package com.scriptchess.marga.report;

import java.util.Collection;
import java.util.Map;

/** Tiny JSON serializer for the report payloads: Map, Collection, String[], String, Number, Boolean, null. */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder(1024);
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            string(out, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(out, String.valueOf(e.getKey()));
                out.append(':');
                append(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> list) {
            array(out, list.toArray());
        } else if (value instanceof Object[] array) {
            array(out, array);
        } else {
            throw new IllegalArgumentException("Unsupported JSON type: " + value.getClass());
        }
    }

    private static void array(StringBuilder out, Object[] items) {
        out.append('[');
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            append(out, items[i]);
        }
        out.append(']');
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c"); // never let data close a <script> tag
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
}