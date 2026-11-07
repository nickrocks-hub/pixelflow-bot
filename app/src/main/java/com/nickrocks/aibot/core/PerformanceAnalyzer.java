package com.nickrocks.aibot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Summaries, comparisons and trends computed from stored runs. */
public final class PerformanceAnalyzer {
    private PerformanceAnalyzer() { }

    public static final String[][] SUMMARY_ROWS = {
            {"roomsCleared", "Rooms cleared"}, {"roomsEntered", "Rooms entered"}, {"floorsCompleted", "Floors completed"},
            {"enemiesDefeated", "Enemies defeated"}, {"bossesDefeated", "Bosses defeated"}, {"hitsLanded", "Damage dealt (hits landed)"},
            {"damageReceived", "Damage received (half hearts)"}, {"itemsCollected", "Items collected"}, {"decisions", "Total AI decisions"},
            {"keyDecisions", "Explained decisions"}, {"dodgesOk", "Successful dodges"}, {"dodgesFailed", "Failed dodges"},
            {"timesStuck", "Times stuck"}, {"abilitiesUsed", "Abilities used"}, {"mistakes", "Mistakes logged"}};

    @SuppressWarnings("unchecked")
    public static Map<String, Object> stats(Map<String, Object> meta) {
        Object s = meta == null ? null : meta.get("stats");
        return s instanceof Map ? (Map<String, Object>) s : new LinkedHashMap<>();
    }

    public static String duration(double ms) {
        long s = (long) (ms / 1000);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    public static String summaryText(Map<String, Object> m) {
        if (m == null) return "(no data)";
        Map<String, Object> st = stats(m);
        StringBuilder b = new StringBuilder();
        b.append("AI RUN RESULTS\n\n");
        b.append("RUN ID: ").append(Json.str(m, "runId", "?")).append('\n');
        b.append("Start time: ").append(RunHistoryRepository.wallTime(Json.num(m, "startWall", 0))).append('\n');
        b.append("Status: ").append(Json.str(m, "status", "?")).append('\n');
        b.append("Outcome: ").append(Json.str(m, "outcome", "?")).append('\n');
        if (m.containsKey("endReason")) b.append("Ended because: ").append(Json.str(m, "endReason", "")).append('\n');
        b.append("Total duration (in-game): ").append(duration(Json.num(m, "durationMs", 0))).append('\n');
        for (String[] r : SUMMARY_ROWS) b.append(r[1]).append(": ").append((long) Json.num(st, r[0], 0)).append('\n');
        b.append("Highest progression reached: ").append(Json.str(st, "progression", "?")).append('\n');
        b.append("Score: ").append(String.format(Locale.US, "%.0f", Json.num(m, "fitness", 0))).append('\n');
        b.append("Skill / style: ").append(Json.str(m, "skill", "?")).append(" / ").append(Json.str(m, "style", "?")).append('\n');
        if (m.containsKey("tuning")) b.append("Behaviour used: ").append(Json.str(m, "tuning", "")).append('\n');
        if (m.containsKey("learning")) b.append("Learning: ").append(Json.str(m, "learning", "")).append('\n');
        Object hl = m.get("highlights");
        if (hl instanceof List && !((List<?>) hl).isEmpty()) {
            b.append("\nTIMELINE\n");
            for (Object o : (List<?>) hl) b.append(o).append('\n');
        }
        return b.toString();
    }

    /** Most important events, for the summary timeline. */
    public static List<String> highlights(List<Map<String, Object>> events, int max) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> e : events) {
            String type = Json.str(e, "type", "");
            String msg = Json.str(e, "msg", "");
            boolean key = type.equals("BOSS") || type.equals("FLOOR") || type.equals("STATE")
                    || (type.equals("ROOM")) || (type.equals("DAMAGE")) || type.equals("ITEM")
                    || (type.equals("KILL") && msg.toLowerCase(Locale.ROOT).contains("boss"));
            if (key) out.add(Json.str(e, "clock", "") + " — " + msg);
        }
        if (out.size() > max) {
            // keep the first and last parts
            List<String> cut = new ArrayList<>(out.subList(0, max / 2));
            cut.add("… " + (out.size() - max) + " more …");
            cut.addAll(out.subList(out.size() - max / 2, out.size()));
            return cut;
        }
        return out;
    }

    /** Rows of {label, run A, run B, change}. */
    public static List<String[]> compare(Map<String, Object> a, Map<String, Object> b) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Outcome", Json.str(a, "outcome", "?"), Json.str(b, "outcome", "?"), ""});
        double da = Json.num(a, "durationMs", 0), db = Json.num(b, "durationMs", 0);
        rows.add(new String[]{"Duration", duration(da), duration(db), sign((db - da) / 1000) + " s"});
        double fa = Json.num(a, "fitness", 0), fb = Json.num(b, "fitness", 0);
        rows.add(new String[]{"Score", fmt(fa), fmt(fb), sign(fb - fa)});
        Map<String, Object> sa = stats(a), sb = stats(b);
        for (String[] r : SUMMARY_ROWS) {
            double x = Json.num(sa, r[0], 0), y = Json.num(sb, r[0], 0);
            rows.add(new String[]{r[1], fmt(x), fmt(y), sign(y - x)});
        }
        rows.add(new String[]{"Skill / style", Json.str(a, "skill", "") + "/" + Json.str(a, "style", ""), Json.str(b, "skill", "") + "/" + Json.str(b, "style", ""), ""});
        return rows;
    }

    /** Score per run in time order: {index, score, adaptive?1:0}. */
    public static List<double[]> trend(List<Map<String, Object>> newestFirst) {
        List<double[]> t = new ArrayList<>();
        for (int i = newestFirst.size() - 1, k = 0; i >= 0; i--, k++) {
            Map<String, Object> m = newestFirst.get(i);
            if (!m.containsKey("fitness")) continue;
            t.add(new double[]{k, Json.num(m, "fitness", 0), "ADAPTIVE".equals(Json.str(m, "skill", "")) ? 1 : 0});
        }
        return t;
    }

    /** "Is it getting better?" in plain words: average score of the latest runs vs the earlier ones. */
    public static String improvement(List<Map<String, Object>> newestFirst) {
        List<double[]> t = trend(newestFirst);
        if (t.size() < 4) return "Play at least 4 runs to see a trend (" + t.size() + " so far).";
        int half = t.size() / 2;
        double early = 0, late = 0;
        for (int i = 0; i < half; i++) early += t.get(i)[1];
        for (int i = half; i < t.size(); i++) late += t.get(i)[1];
        early /= half; late /= (t.size() - half);
        // least-squares slope
        double mx = 0, my = 0;
        for (double[] p : t) { mx += p[0]; my += p[1]; }
        mx /= t.size(); my /= t.size();
        double num = 0, den = 0;
        for (double[] p : t) { num += (p[0] - mx) * (p[1] - my); den += (p[0] - mx) * (p[0] - mx); }
        double slope = den == 0 ? 0 : num / den;
        return String.format(Locale.US, "Average score: first %d runs %.0f, last %d runs %.0f (%s%.0f%%). Trend %+.1f points per run.",
                half, early, t.size() - half, late, late >= early ? "+" : "", early == 0 ? 0 : (late - early) / Math.abs(early) * 100, slope);
    }

    public static Map<String, Object> totals(List<Map<String, Object>> runs) {
        Map<String, Object> m = new LinkedHashMap<>();
        int n = runs.size(), wins = 0, deaths = 0;
        double time = 0, best = Double.NEGATIVE_INFINITY;
        Map<String, Double> sums = new LinkedHashMap<>();
        for (Map<String, Object> r : runs) {
            String o = Json.str(r, "outcome", "");
            if (o.equals("Victory")) wins++;
            if (o.equals("Death")) deaths++;
            time += Json.num(r, "durationMs", 0);
            best = Math.max(best, Json.num(r, "fitness", Double.NEGATIVE_INFINITY));
            Map<String, Object> s = stats(r);
            for (String[] row : SUMMARY_ROWS) sums.merge(row[0], Json.num(s, row[0], 0), Double::sum);
        }
        m.put("runs", n); m.put("victories", wins); m.put("deaths", deaths); m.put("timeMs", time);
        m.put("bestScore", n == 0 ? 0 : best);
        for (Map.Entry<String, Double> e : sums.entrySet()) m.put(e.getKey(), e.getValue());
        return m;
    }

    private static String fmt(double d) { return d == Math.rint(d) ? Long.toString((long) d) : String.format(Locale.US, "%.1f", d); }
    private static String sign(double d) { return (d > 0 ? "+" : "") + fmt(Math.round(d * 10) / 10.0); }
}
