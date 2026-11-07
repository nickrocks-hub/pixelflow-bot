package com.nickrocks.aibot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON reader/writer (maps, lists, strings, numbers, booleans, null). No libraries needed. */
public final class Json {
    private Json() { }

    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    public static void write(StringBuilder b, Object o) {
        if (o == null) b.append("null");
        else if (o instanceof String) quote(b, (String) o);
        else if (o instanceof Boolean) b.append(o);
        else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("0");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(String.format(java.util.Locale.US, "%.4f", d));
        } else if (o instanceof Number) b.append(o);
        else if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object v : (List<Object>) o) {
                if (!first) b.append(',');
                first = false;
                write(b, v);
            }
            b.append(']');
        } else if (o instanceof float[]) {
            b.append('[');
            float[] a = (float[]) o;
            for (int i = 0; i < a.length; i++) { if (i > 0) b.append(','); write(b, (double) a[i]); }
            b.append(']');
        } else quote(b, o.toString());
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object o = p.value();
        return o;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(String s) {
        try {
            Object o = parse(s);
            return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static double num(Map<String, Object> m, String k, double def) {
        Object o = m == null ? null : m.get(k);
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }

    public static String str(Map<String, Object> m, String k, String def) {
        Object o = m == null ? null : m.get(k);
        return o == null ? def : o.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> sub(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        if (o instanceof Map) return (Map<String, Object>) o;
        Map<String, Object> n = new LinkedHashMap<>();
        if (m != null) m.put(k, n);
        return n;
    }

    private static final class P {
        final String s; int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("end");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    if (s.charAt(i++) != ':') throw new IllegalArgumentException(": expected");
                    m.put(k, value());
                    ws();
                    char d = s.charAt(i++);
                    if (d == '}') return m;
                    if (d != ',') throw new IllegalArgumentException(", expected");
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    char d = s.charAt(i++);
                    if (d == ']') return l;
                    if (d != ',') throw new IllegalArgumentException(", expected");
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i);
            if (n.isEmpty()) throw new IllegalArgumentException("bad value at " + st);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            long v = Long.parseLong(n);
            return v;
        }
        String string() {
            if (s.charAt(i) != '"') throw new IllegalArgumentException("string expected");
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
        }
    }
}
