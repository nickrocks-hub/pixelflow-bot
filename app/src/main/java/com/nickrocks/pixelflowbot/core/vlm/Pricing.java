package com.nickrocks.pixelflowbot.core.vlm;

import java.util.Locale;

/**
 * Claude API prices used to show and cap what a session costs. Source: platform.claude.com
 * pricing page (checked 2026-10-18). Haiku 5.5 prices are for prompts up to 100,000 tokens,
 * which every request here is. If Anthropic changes prices, update this table.
 */
public final class Pricing {
    private Pricing() { }

    public static final class Model {
        public final String id, label;
        public final double inPerMTok, outPerMTok;

        Model(String id, String label, double in, double out) {
            this.id = id; this.label = label; this.inPerMTok = in; this.outPerMTok = out;
        }

        public double cost(int inTokens, int outTokens) {
            return inTokens * inPerMTok / 1e6 + outTokens * outPerMTok / 1e6;
        }
    }

    public static final Model HAIKU = new Model("claude-haiku-5-5", "Claude Haiku 5.5 (fast, cheapest)", 0.10, 0.50);
    public static final Model SONNET = new Model("claude-sonnet-5-5", "Claude Sonnet 5.5 (smarter, ~20× the price)", 2.0, 10.0);
    public static final Model[] ALL = {HAIKU, SONNET};

    public static Model byId(String id) {
        for (Model m : ALL) if (m.id.equals(id)) return m;
        return HAIKU;
    }

    /** Image tokens: one per 28×28 patch (Claude vision docs). */
    public static int imageTokens(int w, int h) {
        return ((w + 27) / 28) * ((h + 27) / 28);
    }

    /** Rough cost of one game step: one screenshot, the prompt, and a short JSON reply. */
    public static double stepEstimate(Model m, int imgW, int imgH) {
        return m.cost(imageTokens(imgW, imgH) + 1800, 350);
    }

    public static String usd(double v) {
        return v < 0.01 ? String.format(Locale.US, "$%.4f", v) : String.format(Locale.US, "$%.2f", v);
    }
}
