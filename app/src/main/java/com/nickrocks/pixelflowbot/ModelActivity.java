package com.nickrocks.pixelflowbot;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nickrocks.pixelflowbot.core.vlm.VisionModel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * The on-device AI: import a model file from the phone's storage, test it, set limits.
 * The app has no internet permission; the model file is something the owner puts on the phone.
 */
public class ModelActivity extends Activity {
    private static final int PICK = 41;

    private LocalSettings ls;
    private TextView modelText, testResult, progress;
    private Button pickBtn, testBtn, deleteBtn, gpuBtn;
    private EditText steps, settle;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean copying;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ls = new LocalSettings(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));

        TextView t = text("On-device AI", 26, Color.WHITE);
        t.setTypeface(Ui.BLACK);
        root.addView(t);
        root.addView(body("Plays games that have no built-in player: it looks at the screen, reads the instructions you wrote in the game's "
                + "profile, works out the board and controls, and plays. The AI model runs on this phone — nothing goes over the internet."));

        root.addView(section("PRIVACY"));
        LinearLayout pc = card();
        pc.addView(body("• This app has no internet permission at all, so Android blocks it (and every library in it) from going online.\n"
                + "• Screenshots are shrunk, given to the model in the app's private storage, and never leave the phone.\n"
                + "• The model, your instructions and what it learns are stored only in this app."));
        root.addView(pc);

        root.addView(section("MODEL FILE"));
        LinearLayout mc = card();
        modelText = body("");
        mc.addView(modelText);
        mc.addView(body("The app can't download a model (it never goes online), so you put one on the phone yourself, once:\n"
                + "1. In a browser, open huggingface.co/google/gemma-3n-E2B-it-litert-lm (accept Google's Gemma licence, free).\n"
                + "2. Under \"Files and versions\", download gemma-3n-E2B-it-int4.litertlm (3.66 GB) into your phone's Downloads. "
                + "Not the \"-Web\" file (that one is for web browsers) and not the \"mediatek\" one (only for certain MediaTek chips).\n"
                + "3. Press Import below and choose it. It is copied into the app; you can delete the download afterwards.\n"
                + "Any LiteRT-LM model that accepts images works. Gemma 3n E4B (about 4.4 GB) is more accurate but slower and needs more memory."));
        pickBtn = small("Import model file…", Ui.ACCENT, v -> pick());
        mc.addView(pickBtn);
        progress = note("");
        mc.addView(progress);
        LinearLayout r = new LinearLayout(this);
        testBtn = small("Test the model", Ui.INFO, v -> test());
        r.addView(testBtn, weight());
        deleteBtn = small("Delete model", Ui.MUTED, v -> delete());
        r.addView(deleteBtn, weight());
        mc.addView(r);
        testResult = body("");
        mc.addView(testResult);
        root.addView(mc);

        root.addView(section("SPEED AND LIMITS"));
        LinearLayout sc = card();
        gpuBtn = small("", Ui.MUTED, v -> { ls.setGpu(!ls.preferGpu()); LocalVision.release(); refresh(); });
        sc.addView(gpuBtn);
        sc.addView(body("Most steps per session:"));
        steps = field("300", InputType.TYPE_CLASS_NUMBER);
        steps.setText(String.valueOf(ls.maxSteps()));
        sc.addView(steps);
        sc.addView(body("Wait after each move before looking again (ms):"));
        settle = field("1200", InputType.TYPE_CLASS_NUMBER);
        settle.setText(String.valueOf(ls.settleMs()));
        sc.addView(settle);
        sc.addView(small("Save limits", Ui.ACCENT, v -> saveLimits()));
        sc.addView(note("Honest expectations: a phone-sized model takes several seconds to tens of seconds per step and is much less capable "
                + "than big cloud models — fine for puzzles, turn-based games and menus, not reflex games, and it will make mistakes. "
                + "It needs a recent phone with about 6 GB of memory or more. It makes the phone warm and uses battery while it plays."));
        root.addView(sc);

        ScrollView sv = new ScrollView(this);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Ui.BG, Ui.BG_2}));
        sv.addView(root);
        setContentView(sv);
        refresh();
    }

    private void refresh() {
        if (ls.ready()) {
            File f = new File(ls.modelPath());
            String loaded = LocalVision.isLoaded(ls.modelPath()) ? " · loaded on " + LocalVision.getBackend() : "";
            modelText.setText("✓ " + ls.modelName() + " (" + gb(f.length()) + ")" + loaded);
            modelText.setTextColor(0xFF5EEAD4);
        } else {
            modelText.setText("No model yet — the AI agent can't play until you import one.");
            modelText.setTextColor(0xFFFCD34D);
        }
        pickBtn.setEnabled(!copying);
        testBtn.setEnabled(ls.ready() && !copying);
        deleteBtn.setEnabled(ls.ready() && !copying);
        testBtn.setAlpha(testBtn.isEnabled() ? 1f : 0.4f);
        deleteBtn.setAlpha(deleteBtn.isEnabled() ? 1f : 0.4f);
        gpuBtn.setText(ls.preferGpu() ? "Use the GPU (faster) — tap to use the CPU only" : "CPU only — tap to use the GPU (faster)");
        Ui.tint(gpuBtn, ls.preferGpu() ? Ui.INFO : Ui.MUTED, dp(12));
    }

    // ------------------------------------------------------------------ import

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, PICK); } catch (Exception e) { toast("No file picker on this phone."); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        importFrom(data.getData());
    }

    private void importFrom(Uri uri) {
        String name = "model.litertlm";
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && c.getString(ni) != null) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) { }
        if (!name.toLowerCase(Locale.US).endsWith(".litertlm")) {
            toast("That isn't a .litertlm model file (" + name + ").");
            return;
        }
        String low = name.toLowerCase(Locale.US);
        if (low.contains("-web")) {
            toast("That's the web-browser version (" + name + "); it doesn't run on Android. Download gemma-3n-E2B-it-int4.litertlm instead.");
            return;
        }
        File dir = ls.modelDir();
        long free = new StatFs(dir.getPath()).getAvailableBytes();
        if (size > 0 && free < size + 200L * 1024 * 1024) {
            toast("Not enough free space: the model needs " + gb(size) + ", " + gb(free) + " free.");
            return;
        }
        final String fname = name.replaceAll("[^A-Za-z0-9._-]", "_");
        final long total = size;
        copying = true;
        refresh();
        progress.setText("Copying " + fname + "…");
        new Thread(() -> {
            File tmp = new File(dir, fname + ".part"), dst = new File(dir, fname);
            String msg;
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new java.io.IOException("can't open the file");
                byte[] buf = new byte[1 << 20];
                long done = 0, lastShown = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (done - lastShown > 64L * 1024 * 1024) {
                        lastShown = done;
                        final long d = done;
                        handler.post(() -> progress.setText("Copying… " + gb(d) + (total > 0 ? " of " + gb(total) : "")));
                    }
                }
                out.flush();
                LocalVision.release();
                // Only one model is kept.
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) if (!f.equals(tmp)) f.delete();
                if (!tmp.renameTo(dst)) throw new java.io.IOException("couldn't save the file");
                ls.setModel(dst.getPath(), fname.replaceAll("\\.litertlm$", ""));
                msg = "✓ Imported. Press \"Test the model\" to load it once (the first load is the slowest).";
            } catch (Exception e) {
                tmp.delete();
                msg = "✗ Import failed: " + e.getMessage();
            }
            final String m = msg;
            handler.post(() -> { copying = false; progress.setText(m); refresh(); });
        }, "model-import").start();
    }

    private void delete() {
        LocalVision.release();
        File[] all = ls.modelDir().listFiles();
        if (all != null) for (File f : all) f.delete();
        ls.clearModel();
        testResult.setText("");
        toast("Model deleted");
        refresh();
    }

    // ------------------------------------------------------------------ test

    /** Loads the model and asks one tiny text question, on the phone. */
    private void test() {
        if (!ls.ready()) return;
        testBtn.setEnabled(false);
        testResult.setText("Loading the model on this phone (can take 10–60 s the first time)…");
        new Thread(() -> {
            String msg;
            try {
                boolean was = LocalVision.isLoaded(ls.modelPath());
                LocalVision m = LocalVision.get(this, ls.modelPath(), ls.modelName(), ls.preferGpu());
                VisionModel.Reply r = m.ask("Reply with the single word OK.", null, "Test.", 10);
                msg = String.format(Locale.US, "✓ Works on the %s%s. It answered \"%s\" in %.1f s.", LocalVision.getBackend(),
                        was ? "" : String.format(Locale.US, " (loaded in %.1f s)", LocalVision.getLoadMs() / 1000.0),
                        r.text.trim().length() > 40 ? r.text.trim().substring(0, 40) + "…" : r.text.trim(), r.ms / 1000.0);
            } catch (Throwable e) {
                String em = String.valueOf(e.getMessage());
                msg = "✗ " + em + (em.contains("magic number")
                        ? "\nThis file isn't an Android LiteRT-LM model (the \"-Web\" version is for browsers). Delete it and import gemma-3n-E2B-it-int4.litertlm."
                        : "\nIf the phone ran out of memory, close other apps or try a smaller model.");
            }
            final String m = msg;
            handler.post(() -> { testResult.setText(m); refresh(); });
        }, "model-test").start();
    }

    private void saveLimits() {
        try {
            ls.setMaxSteps(Math.max(5, Math.min(5000, Integer.parseInt(steps.getText().toString().trim()))));
            ls.setSettleMs(Math.max(200, Math.min(10_000, Long.parseLong(settle.getText().toString().trim()))));
            toast("Saved");
        } catch (NumberFormatException e) { toast("Enter numbers"); }
        refresh();
    }

    static String gb(long bytes) {
        return bytes >= 1L << 30 ? String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30))
                : String.format(Locale.US, "%d MB", bytes >> 20);
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
