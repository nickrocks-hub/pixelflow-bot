package com.nickrocks.pixelflowbot;

import android.content.Context;
import android.content.SharedPreferences;

import com.nickrocks.pixelflowbot.core.vlm.Pricing;

/**
 * Opt-in settings for the AI vision agent (Claude API). Off until the user turns it on and enters
 * their own API key. Stored in this app's private storage only.
 */
final class CloudSettings {
    private final SharedPreferences p;

    CloudSettings(Context c) { p = c.getSharedPreferences("cloud", Context.MODE_PRIVATE); }

    boolean consent() { return p.getBoolean("consent", false); }
    String apiKey() { return p.getString("key", ""); }
    Pricing.Model model() { return Pricing.byId(p.getString("model", Pricing.HAIKU.id)); }
    double maxUsd() { return Double.longBitsToDouble(p.getLong("max_usd", Double.doubleToLongBits(0.50))); }
    int perMinute() { return p.getInt("per_min", 20); }
    long settleMs() { return p.getLong("settle_ms", 1200); }
    double spentTotal() { return Double.longBitsToDouble(p.getLong("spent_total", Double.doubleToLongBits(0))); }

    boolean ready() { return consent() && !apiKey().trim().isEmpty(); }

    void setConsent(boolean on) { p.edit().putBoolean("consent", on).apply(); }
    void setKey(String k) { p.edit().putString("key", k.trim()).apply(); }
    void setModel(String id) { p.edit().putString("model", id).apply(); }
    void setMaxUsd(double v) { p.edit().putLong("max_usd", Double.doubleToLongBits(v)).apply(); }
    void setPerMinute(int v) { p.edit().putInt("per_min", v).apply(); }
    void setSettleMs(long v) { p.edit().putLong("settle_ms", v).apply(); }
    void addSpent(double v) { p.edit().putLong("spent_total", Double.doubleToLongBits(spentTotal() + v)).apply(); }
}
