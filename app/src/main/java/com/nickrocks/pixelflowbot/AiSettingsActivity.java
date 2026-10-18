package com.nickrocks.pixelflowbot;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nickrocks.pixelflowbot.core.vlm.ClaudeVision;
import com.nickrocks.pixelflowbot.core.vlm.InstructionAgent;
import com.nickrocks.pixelflowbot.core.vlm.Pricing;
import com.nickrocks.pixelflowbot.core.vlm.VisionModel;

import java.util.Locale;

/**
 * Settings for the AI vision agent: what leaves the phone, consent, API key, model, budget.
 * Nothing is sent anywhere until the user turns this on, adds a key and starts a session.
 */
public class AiSettingsActivity extends Activity {
    private CloudSettings cs;
    private Button consentBtn, testBtn;
    private Button[] modelBtns;
    private EditText key, budget, rate, settle;
    private TextView costText, testResult;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cs = new CloudSettings(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));

        TextView t = text("AI vision agent", 26, Color.WHITE);
        t.setTypeface(Ui.BLACK);
        root.addView(t);
        root.addView(body("Lets the bot work with games that have no built-in player: it looks at the screen, reads the instructions you "
                + "wrote in the game's profile, works out the board and controls, and plays — using Anthropic's Claude model over the internet."));

        root.addView(section("WHAT LEAVES YOUR PHONE"));
        LinearLayout pc = card();
        pc.addView(body("• While a session runs (or when you press Calibrate): one screenshot of the game per step, shrunk to "
                + InstructionAgent.IMG_W + " px wide, plus the game's name, your instructions and the notes in its profile.\n"
                + "• Sent to Anthropic's Claude API with your own API key, over HTTPS. Nothing is sent when the agent isn't running.\n"
                + "• Screenshots may show anything on screen, including notifications — turn on Do Not Disturb if that matters to you.\n"
                + "• Your key is stored only in this app's private storage."));
        consentBtn = small("", Ui.MUTED, v -> { cs.setConsent(!cs.consent()); refresh(); });
        pc.addView(consentBtn);
        root.addView(pc);

        root.addView(section("API KEY"));
        LinearLayout kc = card();
        kc.addView(body("Create a key at console.anthropic.com (API keys). Usage is billed to your Anthropic account."));
        key = field("sk-ant-…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(cs.apiKey());
        kc.addView(key);
        LinearLayout kr = new LinearLayout(this);
        kr.addView(small("Save key", Ui.ACCENT, v -> { cs.setKey(key.getText().toString()); toast("Saved"); refresh(); }), weight());
        testBtn = small("Test connection", Ui.INFO, v -> test());
        kr.addView(testBtn, weight());
        kc.addView(kr);
        testResult = body("");
        kc.addView(testResult);
        root.addView(kc);

        root.addView(section("MODEL AND COST"));
        LinearLayout mc = card();
        LinearLayout mr = new LinearLayout(this);
        modelBtns = new Button[Pricing.ALL.length];
        for (int i = 0; i < Pricing.ALL.length; i++) {
            final Pricing.Model m = Pricing.ALL[i];
            modelBtns[i] = small(m.id.contains("haiku") ? "Haiku 5.5" : "Sonnet 5.5", Ui.MUTED, v -> { cs.setModel(m.id); refresh(); });
            mr.addView(modelBtns[i], weight());
        }
        mc.addView(mr);
        costText = body("");
        mc.addView(costText);
        mc.addView(body("Budget per session (US$):"));
        budget = field("0.50", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        budget.setText(String.format(Locale.US, "%.2f", cs.maxUsd()));
        mc.addView(budget);
        mc.addView(body("Most model calls per minute:"));
        rate = field("20", InputType.TYPE_CLASS_NUMBER);
        rate.setText(String.valueOf(cs.perMinute()));
        mc.addView(rate);
        mc.addView(body("Wait after each move before looking again (ms):"));
        settle = field("1200", InputType.TYPE_CLASS_NUMBER);
        settle.setText(String.valueOf(cs.settleMs()));
        mc.addView(settle);
        mc.addView(small("Save limits", Ui.ACCENT, v -> saveLimits()));
        mc.addView(note("Speed: each step takes a few seconds (screenshot → model → move), so this suits puzzles, turn-based games and menus — "
                + "not reflex games. A session stops by itself at the budget."));
        root.addView(mc);

        ScrollView sv = new ScrollView(this);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Ui.BG, Ui.BG_2}));
        sv.addView(root);
        setContentView(sv);
        refresh();
    }

    private void refresh() {
        boolean on = cs.consent();
        consentBtn.setText(on ? "✓ Allowed — tap to turn off" : "Allow sending screenshots to Anthropic");
        Ui.tint(consentBtn, on ? Ui.ACCENT : Ui.MUTED, dp(12));
        Pricing.Model cur = cs.model();
        for (int i = 0; i < Pricing.ALL.length; i++) Ui.tint(modelBtns[i], Pricing.ALL[i] == cur ? Ui.INFO : Ui.MUTED, dp(12));
        int h = InstructionAgent.IMG_W * 21 / 9;
        costText.setText(String.format(Locale.US, "%s: $%.2f / $%.2f per million input / output tokens. One step ≈ %d image tokens + ~1,800 text → about %s per step (%s per 100 steps).%nSpent so far on this phone: %s",
                cur.label, cur.inPerMTok, cur.outPerMTok, Pricing.imageTokens(InstructionAgent.IMG_W, h),
                Pricing.usd(Pricing.stepEstimate(cur, InstructionAgent.IMG_W, h)), Pricing.usd(100 * Pricing.stepEstimate(cur, InstructionAgent.IMG_W, h)),
                Pricing.usd(cs.spentTotal())));
    }

    private void saveLimits() {
        try {
            cs.setMaxUsd(Math.max(0.01, Math.min(20, Double.parseDouble(budget.getText().toString().trim()))));
            cs.setPerMinute(Math.max(1, Math.min(60, Integer.parseInt(rate.getText().toString().trim()))));
            cs.setSettleMs(Math.max(200, Math.min(10_000, Long.parseLong(settle.getText().toString().trim()))));
            toast("Saved");
        } catch (NumberFormatException e) { toast("Enter numbers"); }
        refresh();
    }

    /** One tiny text-only request (a few hundred tokens, a fraction of a cent) to check the key. */
    private void test() {
        cs.setKey(key.getText().toString());
        if (cs.apiKey().isEmpty()) { toast("Enter your API key first."); return; }
        if (!cs.consent()) { toast("Turn on \"Allow sending\" first."); return; }
        testResult.setText("Testing…");
        new Thread(() -> {
            String msg;
            try {
                VisionModel.Reply r = new ClaudeVision(cs.apiKey(), cs.model()).ask("Reply with the single word OK.", null, "Connection test.", 20);
                cs.addSpent(r.costUsd);
                msg = "✓ Works: " + cs.model().id + " answered \"" + r.text.trim() + "\" in " + r.ms + " ms (" + Pricing.usd(r.costUsd) + ").";
            } catch (Exception e) {
                msg = "✗ " + e.getMessage();
            }
            final String m = msg;
            handler.post(() -> { testResult.setText(m); refresh(); });
        }, "ai-test").start();
    }

    // ------------------------------------------------------------------ widgets

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.TEXT_FAINT);
        e.setSingleLine(true);
        return e;
    }

    private TextView section(String s) {
        TextView t = text(s, 11, Ui.TEXT_FAINT);
        t.setTypeface(Ui.BLACK);
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(4), dp(22), 0, dp(8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(Ui.card(dp(18), dp(1), Ui.CARD_STROKE));
        return c;
    }

    private TextView body(String s) {
        TextView t = text(s, 13, Ui.TEXT_DIM);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 11, Ui.TEXT_FAINT);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private Button small(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setTypeface(Ui.MEDIUM);
        b.setBackground(Ui.button(color, dp(12)));
        b.setStateListAnimator(null);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(6), dp(3), 0);
        return lp;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
