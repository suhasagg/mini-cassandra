package com.example.minicassandra.util;

import java.util.Iterator;
import java.util.Map;

public final class JsonUtil {
    private JsonUtil() {
    }

    public static String object(Map<String, ?> map) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        Iterator<? extends Map.Entry<String, ?>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ?> entry = iterator.next();
            sb.append(quote(entry.getKey())).append(':').append(value(entry.getValue()));
            if (iterator.hasNext()) sb.append(',');
        }
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static String value(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return quote(s);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) return object((Map<String, ?>) map);
        if (value instanceof Iterable<?> iterable) {
            StringBuilder sb = new StringBuilder("[");
            Iterator<?> iterator = iterable.iterator();
            while (iterator.hasNext()) {
                sb.append(value(iterator.next()));
                if (iterator.hasNext()) sb.append(',');
            }
            return sb.append(']').toString();
        }
        return quote(value.toString());
    }

    public static String quote(String text) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
