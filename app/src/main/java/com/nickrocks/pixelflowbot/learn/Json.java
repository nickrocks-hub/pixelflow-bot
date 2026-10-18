package com.nickrocks.pixelflowbot.learn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON reader/writer so the learning core has no Android or library dependencies. */
public final class Json {
    private Json() { }

    public static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public static String arr(float[] a) {
        if (a == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            float v = a[i];
            if (v == (int) v) sb.append((int) v); else sb.append(v);
        }
        return sb.append(']').toString();
    }

    public static String arr(String[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(q(a[i])); }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ parsing

    public static Object parse(String s) {
        int[] pos = {0};
        Object v = value(s, pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }

    public static float[] floats(Object o) {
        if (!(o instanceof List)) return null;
        List<?> l = (List<?>) o;
        float[] a = new float[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = ((Number) l.get(i)).floatValue();
        return a;
    }

    public static double num(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v instanceof String ? (String) v : def;
    }

    private static void ws(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
    }

    private static Object value(String s, int[] p) {
        ws(s, p);
        if (p[0] >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(p[0]);
        if (c == '{') {
            p[0]++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws(s, p);
            if (s.charAt(p[0]) == '}') { p[0]++; return m; }
            while (true) {
                ws(s, p);
                String k = (String) value(s, p);
                ws(s, p);
                p[0]++; // :
                m.put(k, value(s, p));
                ws(s, p);
                char d = s.charAt(p[0]++);
                if (d == '}') return m;
            }
        }
        if (c == '[') {
            p[0]++;
            List<Object> l = new ArrayList<>();
            ws(s, p);
            if (s.charAt(p[0]) == ']') { p[0]++; return l; }
            while (true) {
                l.add(value(s, p));
                ws(s, p);
                char d = s.charAt(p[0]++);
                if (d == ']') return l;
            }
        }
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            p[0]++;
            while (true) {
                char d = s.charAt(p[0]++);
                if (d == '"') return sb.toString();
                if (d == '\\') {
                    char e = s.charAt(p[0]++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16)); p[0] += 4; break;
                        default: sb.append(e);
                    }
                } else sb.append(d);
            }
        }
        if (s.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p[0])) { p[0] += 4; return null; }
        int st = p[0];
        while (p[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(p[0])) >= 0) p[0]++;
        return Double.parseDouble(s.substring(st, p[0]));
    }
}
