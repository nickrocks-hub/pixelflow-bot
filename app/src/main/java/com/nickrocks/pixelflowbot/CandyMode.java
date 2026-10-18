package com.nickrocks.pixelflowbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.nickrocks.pixelflowbot.candy.Board;
import com.nickrocks.pixelflowbot.candy.BoardCheck;
import com.nickrocks.pixelflowbot.candy.CandySolver;
import com.nickrocks.pixelflowbot.candy.CandyVision;
import com.nickrocks.pixelflowbot.vision.Frame;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class CandyMode {
    static final String CANDY_PKG = "com.king.candycrushsaga";
    private static final int C_CANDY = Ui.GAME_CANDY;
    private static final int C_GREY = Ui.MUTED;
    private static final int C_READ = Ui.INFO;
    private static final int C_START = Ui.ACCENT;
    private static final int C_STOP = Ui.DANGER;
    private static final int IDLE = 0;
    private static final int MAX_MOVES = 150;
    private static final int MAX_REJECTS = 5;
    private static final int MAX_UNREADABLE = 24;
    private static final int PAUSED = 2;
    private static final int PLAYING = 1;
    private static final String PREF_GAME = "game";
    private static final String PREF_GRAVITY = "cc_gravity";
    private static final String PREF_GRID = "cc_grid";
    private static final String PREF_GRID_MODE = "cc_grid_mode";
    private static final long REJECT_MS = 2200;
    private static final long SETTLE_MS = 1200;
    private static final long STEP_MS = 350;
    private static final long SWIPE_MS = 220;
    private static final String TAG = "PFBot.Candy";
    private static CandyMode current;
    private float baseline;
    private View calibOverlay;
    private CandyVision.Grid candidate;
    private int candidateHits;
    private LinearLayout candyBox;
    private boolean candyMode;
    private String game = "pixelflow";
    private NonogramMode nono;
    private AmazeMode amaze;
    private AutoNext next;
    private Button nextToggle, nextTeach;
    private View modeRow, nextRow;
    private int levelsDone;
    private boolean parked;
    private View candyRow3, candyRow4;
    private boolean dead;
    private String gamePkg;
    private Button gravityButton;
    private Button gridModeButton;
    private View gridOverlay;
    private volatile boolean holdGrid;
    private TextView info;
    private String lastProblem;
    private Result lastResult;
    private long lastResultAt;
    private String lastSig;
    private long lastSwipeAt;
    private CandyVision.Grid locked;
    private Button modeButton;
    private int moves;
    private int noMove;
    private LinearLayout panel;
    private Button pauseButton;
    private CandySolver.Move pending;
    private String preSwipeSig;
    private final SharedPreferences prefs;
    private Board prevBoard;
    private boolean readBusy;
    private int readId;
    private Recorder rec;
    private int rejectStreak;
    private int stableReads;
    private Button startButton;
    private final TapService svc;
    private int unreadable;
    private WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CandyVision vision = new CandyVision();
    private final CandySolver solver = new CandySolver();
    private final List<View> pixelFlowViews = new ArrayList();
    private int state = 0;
    private String line = "";
    private final Set<String> banned = new HashSet();
    private final int[] gravityVotes = new int[4];
    private final Runnable stepRunnable = new Runnable() { // from class: com.nickrocks.pixelflowbot.CandyMode.14
        @Override // java.lang.Runnable
        public void run() {
            CandyMode.this.step();
        }
    };

    private interface ResultSink {
        void accept(Result result);
    }

    public static void attach(TapService tapService) {
        try {
            if (current != null) {
                current.shutdown();
            }
            current = new CandyMode(tapService);
            current.install();
        } catch (Throwable th) {
            Log.e(TAG, "Candy Crush mode could not attach; Pixel Flow mode is unaffected", th);
        }
    }

    private CandyMode(TapService tapService) {
        this.svc = tapService;
        this.prefs = tapService.getSharedPreferences(TapService.PREFS, 0);
    }

    private Object field(String str) {
        try {
            Field declaredField = TapService.class.getDeclaredField(str);
            declaredField.setAccessible(true);
            return declaredField.get(this.svc);
        } catch (Throwable th) {
            return null;
        }
    }

    private boolean flag(String str) {
        Object field = field(str);
        return (field instanceof Boolean) && ((Boolean) field).booleanValue();
    }

    private String currentPackage() {
        try {
            Method declaredMethod = TapService.class.getDeclaredMethod("currentPackage", new Class[0]);
            declaredMethod.setAccessible(true);
            return (String) declaredMethod.invoke(this.svc, new Object[0]);
        } catch (Throwable th) {
            try {
                AccessibilityNodeInfo rootInActiveWindow = this.svc.getRootInActiveWindow();
                if (rootInActiveWindow != null && rootInActiveWindow.getPackageName() != null) {
                    return rootInActiveWindow.getPackageName().toString();
                }
            } catch (Throwable th2) {
            }
            return "?";
        }
    }

    int dp(int i) {
        return Math.round(i * this.svc.getResources().getDisplayMetrics().density);
    }

    Button button(String str, int i, View.OnClickListener onClickListener) {
        try {
            Method declaredMethod = TapService.class.getDeclaredMethod("makeButton", String.class, Integer.TYPE, View.OnClickListener.class);
            declaredMethod.setAccessible(true);
            return (Button) declaredMethod.invoke(this.svc, str, Integer.valueOf(i), onClickListener);
        } catch (Throwable th) {
            Button button = new Button(this.svc);
            button.setText(str);
            button.setTextColor(-1);
            button.setTextSize(12.0f);
            button.setAllCaps(false);
            button.setBackgroundColor(i);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setPadding(dp(10), dp(5), dp(10), dp(5));
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-2, -2);
            layoutParams.setMargins(dp(3), dp(4), dp(3), 0);
            button.setLayoutParams(layoutParams);
            button.setOnClickListener(onClickListener);
            return button;
        }
    }

    void toast(String str) {
        Toast.makeText(this.svc, str, 0).show();
    }

    private void install() {
        Object field = field("panel");
        if (!(field instanceof LinearLayout)) {
            throw new IllegalStateException("panel not found");
        }
        this.panel = (LinearLayout) field;
        this.wm = (WindowManager) this.svc.getSystemService("window");
        for (int i = 1; i < this.panel.getChildCount(); i++) {
            this.pixelFlowViews.add(this.panel.getChildAt(i));
        }
        LinearLayout linearLayout = new LinearLayout(this.svc);
        this.modeButton = button("◆ Pixel Flow  ⇄", C_GREY, new View.OnClickListener() { // from class: com.nickrocks.pixelflowbot.CandyMode.1
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                CandyMode.this.toggleMode();
            }
        });
        linearLayout.addView(this.modeButton);
        this.panel.addView(linearLayout, 1);
        this.next = new AutoNext(this.svc, this);
        LinearLayout nextRow = new LinearLayout(this.svc);
        this.nextToggle = button("⟳ Next: on", C_GREY, v -> {
            this.next.setEnabled(this.game, !this.next.enabled(this.game));
            refreshNext();
        });
        this.nextTeach = button("Teach next", C_GREY, v -> this.next.teach(this.game, this::say));
        nextRow.addView(this.nextToggle);
        nextRow.addView(this.nextTeach);
        nextRow.addView(button("Forget", C_GREY, v -> {
            this.next.clearSteps(this.game);
            refreshNext();
            say("Forgot the taught next-level buttons for " + gameName(this.game) + ".");
        }));
        this.panel.addView(nextRow, 2);
        this.modeRow = linearLayout;
        this.nextRow = nextRow;
        this.candyBox = new LinearLayout(this.svc);
        this.candyBox.setOrientation(1);
        LinearLayout linearLayout2 = new LinearLayout(this.svc);
        this.startButton = button("▶ Start", C_START, new View.OnClickListener() { // from class: com.nickrocks.pixelflowbot.CandyMode.2
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                CandyMode.this.start();
            }
        });
        this.pauseButton = button("⏸ Pause", C_GREY, new View.OnClickListener() { // from class: com.nickrocks.pixelflowbot.CandyMode.3
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                if (CandyMode.this.state == 1) {
                    CandyMode.this.pause("Paused by you");
                }
            }
        });
        linearLayout2.addView(this.startButton);
        linearLayout2.addView(this.pauseButton);
        linearLayout2.addView(button("■ STOP", C_STOP, new View.OnClickListener() { // from class: com.nickrocks.pixelflowbot.CandyMode.4
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                CandyMode.this.stopEverything();
            }
        }));
        this.candyBox.addView(linearLayout2);
        this.info = new TextView(this.svc);
        this.info.setTextColor(Ui.TEXT_DIM);
        this.info.setTextSize(11.0f);
        this.info.setPadding(0, dp(4), 0, 0);
        this.info.setMaxWidth(dp(300));
        this.info.setText("Candy Crush: open a level, then Start. Read = show what the bot sees.");
        this.candyBox.addView(this.info);
        LinearLayout linearLayout3 = new LinearLayout(this.svc);
        linearLayout3.addView(button("Read", C_READ, new View.OnClickListener() { // from class: com.nickrocks.pixelflowbot.CandyMode.5
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                CandyMode.this.readOnce();
            }
        }));
        linearLayout3.addView(button("Calibrate", C_GREY, new View.OnClickListener() { // from class: com.nickrocks.pixelflowbot.CandyMode.6
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                CandyMode.this.calibrate();
            }
        }));
        this.gridModeButton = button("Grid: auto", C_GREY, new View.OnClickListener() { // from class: com.nickrocks.pixelflowbot.CandyMode.7
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                CandyMode.this.toggleGridMode();
            }
        });
        linearLayout3.addView(this.gridModeButton);
        this.candyBox.addView(linearLayout3);
        this.candyRow3 = linearLayout3;
        LinearLayout linearLayout4 = new LinearLayout(this.svc);
        this.gravityButton = button("Gravity: auto", C_GREY, new View.OnClickListener() { // from class: com.nickrocks.pixelflowbot.CandyMode.8
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                CandyMode.this.cycleGravity();
            }
        });
        linearLayout4.addView(this.gravityButton);
        linearLayout4.addView(button("Save frame", C_GREY, new View.OnClickListener() { // from class: com.nickrocks.pixelflowbot.CandyMode.9
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                if (!CaptureService.isRunning()) {
                    CandyMode.this.toast("Capture is off. Start it from the app first.");
                } else {
                    CaptureService.requestSaveFrame();
                }
            }
        }));
        this.candyBox.addView(linearLayout4);
        this.candyRow4 = linearLayout4;
        this.panel.addView(this.candyBox);
        try {
            this.nono = new NonogramMode(this.svc, this);
            this.panel.addView(this.nono.box());
        } catch (Throwable th) {
            Log.e(TAG, "Nonogram mode could not attach", th);
            this.nono = null;
        }
        try {
            this.amaze = new AmazeMode(this.svc, this);
            this.panel.addView(this.amaze.box());
        } catch (Throwable th) {
            Log.e(TAG, "Amaze GO mode could not attach", th);
            this.amaze = null;
        }
        this.panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() { // from class: com.nickrocks.pixelflowbot.CandyMode.10
            @Override // android.view.View.OnAttachStateChangeListener
            public void onViewAttachedToWindow(View view) {
            }

            @Override // android.view.View.OnAttachStateChangeListener
            public void onViewDetachedFromWindow(View view) {
                CandyMode.this.shutdown();
            }
        });
        refreshButtons();
        setGame(this.prefs.getString(PREF_GAME, "pixelflow"));
    }

    /** Cycles Pixel Flow → Candy Crush → Nonogram → Pixel Flow. */
    void toggleMode() {
        if (this.next != null && this.next.busy()) {
            toast("Going to the next level — press STOP first to switch games.");
            return;
        }
        if ("candy".equals(this.game) && this.state != 0) {
            toast("Stop Candy Crush auto-play first (Pause, then STOP or finish).");
            return;
        }
        if ("nonogram".equals(this.game) && this.nono != null && this.nono.busy()) {
            toast("Stop the nonogram solver first.");
            return;
        }
        if ("amaze".equals(this.game) && this.amaze != null && this.amaze.busy()) {
            toast("Stop the Amaze GO solver first.");
            return;
        }
        if ("pixelflow".equals(this.game) && (flag("auto") || flag("live") || flag("readBusy"))) {
            toast("Pixel Flow is busy. Stop its auto-play/Live first.");
            return;
        }
        String next;
        if ("pixelflow".equals(this.game)) next = "candy";
        else if ("candy".equals(this.game)) next = this.nono != null ? "nonogram" : this.amaze != null ? "amaze" : "pixelflow";
        else if ("nonogram".equals(this.game)) next = this.amaze != null ? "amaze" : "pixelflow";
        else next = "pixelflow";
        setGame(next);
    }

    private void setGame(String g) {
        if (!"candy".equals(g) && !("nonogram".equals(g) && this.nono != null) && !("amaze".equals(g) && this.amaze != null)) g = "pixelflow";
        this.game = g;
        this.candyMode = "candy".equals(g);
        boolean pf = "pixelflow".equals(g);
        this.prefs.edit().putString(PREF_GAME, g).apply();
        for (View v : this.pixelFlowViews) v.setVisibility(pf ? View.VISIBLE : View.GONE);
        if (pf) {
            try {
                Method m = TapService.class.getDeclaredMethod("updateButtons");
                m.setAccessible(true);
                m.invoke(this.svc);
            } catch (Throwable th) {
            }
        }
        this.candyBox.setVisibility(this.candyMode ? View.VISIBLE : View.GONE);
        if (this.nono != null) this.nono.box().setVisibility("nonogram".equals(g) ? View.VISIBLE : View.GONE);
        if (this.amaze != null) this.amaze.box().setVisibility("amaze".equals(g) ? View.VISIBLE : View.GONE);
        this.modeButton.setText("◆ " + gameName(g) + "  ⇄");
        this.modeButton.setTextColor(-1);
        tint(this.modeButton, pf ? Ui.GAME_PIXELFLOW : this.candyMode ? Ui.GAME_CANDY : "amaze".equals(g) ? Ui.GAME_AMAZE : Ui.GAME_NONO);
        refreshNext();
    }

    // ---------------------------------------------------------------- auto next level

    String gameName(String g) {
        return "candy".equals(g) ? "Candy Crush" : "nonogram".equals(g) ? "Nonogram" : "amaze".equals(g) ? "Amaze GO" : "Pixel Flow";
    }

    private void refreshNext() {
        if (this.next == null || this.nextToggle == null) return;
        boolean on = this.next.enabled(this.game);
        int n = this.next.steps(this.game).size();
        this.nextToggle.setText(on ? "⟳ Next level: ON" : "⟳ Next level: off");
        tint(this.nextToggle, on ? Ui.ACCENT : C_GREY);
        boolean builtin = "nonogram".equals(this.game) || "amaze".equals(this.game);
        this.nextTeach.setText(n > 0 ? "Teach (" + n + " step" + (n > 1 ? "s" : "") + ")" : builtin ? "Teach (built-in)" : "Teach next ⚠");
    }

    /** Shows a message in the current game's info line. */
    void say(String msg) {
        refreshNext();
        if ("candy".equals(this.game)) { setInfo(msg); return; }
        if ("nonogram".equals(this.game) && this.nono != null) { this.nono.note(msg); return; }
        if ("amaze".equals(this.game) && this.amaze != null) { this.amaze.note(msg); return; }
        try {
            Method m = TapService.class.getDeclaredMethod("showNote", String.class);
            m.setAccessible(true);
            m.invoke(this.svc, msg);
        } catch (Throwable th) {
            toast(msg);
        }
    }

    /** Hides the game switch and next-level rows while a bot is working. */
    void setCompact(boolean on) {
        if (this.modeRow != null) this.modeRow.setVisibility(on ? View.GONE : View.VISIBLE);
        if (this.nextRow != null) this.nextRow.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    /** Candy's own compact mode: hide its tool rows too. */
    private void candyCompact(boolean on) {
        setCompact(on);
        if (this.candyRow3 != null) this.candyRow3.setVisibility(on ? View.GONE : View.VISIBLE);
        if (this.candyRow4 != null) this.candyRow4.setVisibility(on ? View.GONE : View.VISIBLE);
        if (this.info != null) this.info.setMaxLines(on ? 2 : 12);
    }

    /**
     * Swipes can't pass through the panel, so park it above or below the board.
     * Returns true if it had to move (the caller re-reads afterwards).
     */
    private boolean parkOffBoard(Board b) {
        int[] pr = panelRect();
        if (pr == null) return false;
        float half = b.cell / 2f;
        int top = Math.round(b.cy(0) - half), bottom = Math.round(b.cy(b.rows - 1) + half);
        int left = Math.round(b.cx(0) - half), right = Math.round(b.cx(b.cols - 1) + half);
        boolean over = pr[0] < right && pr[2] > left && pr[1] < bottom && pr[3] > top;
        if (!over) return false;
        int h = pr[3] - pr[1], gap = dp(6);
        int screenH = this.svc.getResources().getDisplayMetrics().heightPixels;
        if (top - gap - h >= 0) movePanel(top - gap - h);
        else if (bottom + gap + h <= screenH) movePanel(bottom + gap);
        else { movePanel(dp(4)); toast("The board fills the screen — the panel may block a few swipes."); }
        return true;
    }

    /** Moves the floating panel's top edge to screen y. */
    void movePanel(int y) {
        try {
            Method m = TapService.class.getDeclaredMethod("movePanelTo", Integer.TYPE);
            m.setAccessible(true);
            m.invoke(this.svc, y);
        } catch (Throwable th) {
            Log.w(TAG, "move panel failed", th);
        }
    }

    void cancelNext() { if (this.next != null) this.next.cancel(); }

    static void cancelAutoNext() { if (current != null) current.cancelNext(); }

    /** Pixel Flow just won a level (called by TapService). */
    static void pixelFlowWon() {
        CandyMode c = current;
        if (c == null || c.dead || !c.next.enabled("pixelflow") || !"pixelflow".equals(c.game)) return;
        c.levelsDone++;
        c.next.run("pixelflow", null,
                f -> com.nickrocks.pixelflowbot.vision.GameState.detectScreen(f) == com.nickrocks.pixelflowbot.vision.GameState.Screen.GAMEPLAY,
                60_000, c::say,
                () -> {
                    c.say("Level " + (c.levelsDone + 1) + " of this run — starting auto-play");
                    try {
                        Method m = TapService.class.getDeclaredMethod("startAuto");
                        m.setAccessible(true);
                        m.invoke(c.svc);
                    } catch (Throwable th) {
                        c.say("Couldn't restart Pixel Flow auto-play: " + th);
                    }
                },
                why -> c.say("Auto-next stopped: " + why));
    }

    private void candyNext() {
        if (!this.next.enabled("candy")) return;
        this.levelsDone++;
        final int[] exclude = panelRect();
        this.next.run("candy", null,
                f -> {
                    Result r = analyse(f, exclude);
                    return r.board != null && r.error == null && BoardCheck.problem(r.board, null, 0.0f) == null;
                },
                60_000, this::say,
                () -> {
                    say("Next level found — starting");
                    start();
                },
                why -> say("Auto-next stopped: " + why));
    }

    /** Amaze GO cleared a level: press Next Level / the sun / Continue, then play the new board. */
    void amazeSolved() {
        if (this.amaze == null || !this.next.enabled("amaze") || !"amaze".equals(this.game)) return;
        this.levelsDone++;
        this.amaze.parkForNextLevel();
        final com.nickrocks.pixelflowbot.amaze.AmazeVision v = new com.nickrocks.pixelflowbot.amaze.AmazeVision();
        this.next.run("amaze", com.nickrocks.pixelflowbot.amaze.AmazeNext::find,
                f -> {
                    com.nickrocks.pixelflowbot.amaze.AmazeVision.Board b = v.read(f);
                    return b.lives > 0 && (b.cutOff || !b.arrows.isEmpty());
                },
                90_000, this::say,
                () -> this.amaze.autoStart(),
                why -> {
                    this.amaze.setCompact(false);
                    say("Auto-next stopped: " + why);
                });
    }

    /** Nonogram finished a puzzle. */
    void nonogramSolved() {
        if (this.nono == null || !this.next.enabled("nonogram") || !"nonogram".equals(this.game)) return;
        this.levelsDone++;
        this.nono.parkForNextLevel();
        final com.nickrocks.pixelflowbot.nono.NonoVision v = new com.nickrocks.pixelflowbot.nono.NonoVision();
        this.next.run("nonogram", com.nickrocks.pixelflowbot.nono.NextButton::find,
                f -> {
                    com.nickrocks.pixelflowbot.nono.NonoVision.Reading r = v.readBoard(f);
                    return r.grid != null && r.toggle >= 0;
                },
                90_000, this::say,
                () -> this.nono.autoStart(),
                why -> {
                    this.nono.setCompact(false);
                    say("Auto-next stopped: " + why);
                });
    }

    View panelView() { return this.panel; }

    String foregroundPackage() { return currentPackage(); }

    private void tint(Button button, int i) {
        Ui.tint(button, i, dp(14));
    }

    private void refreshButtons() {
        String str;
        if (this.startButton != null) {
            Button button = this.startButton;
            if (this.state == 0) {
                str = "▶ Start";
            } else {
                str = this.state == 2 ? "▶ Resume" : "▶ Playing";
            }
            button.setText(str);
            this.pauseButton.setEnabled(this.state == 1);
            this.pauseButton.setAlpha(this.state == 1 ? 1.0f : 0.4f);
            this.gridModeButton.setText(manualGridMode() ? "Grid: manual" : "Grid: auto");
            int i = this.prefs.getInt(PREF_GRAVITY, -1);
            this.gravityButton.setText(i < 0 ? "Gravity: auto" : "Gravity: " + new String[]{"↓", "↑", "←", "→"}[i]);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setInfo(String str) {
        String str2;
        this.line = str;
        if (this.info != null) {
            switch (this.state) {
                case 1:
                    str2 = "CANDY AUTO · moves " + this.moves + "/" + MAX_MOVES + "\n";
                    break;
                case 2:
                    str2 = "CANDY PAUSED · moves " + this.moves + "\n";
                    break;
                default:
                    str2 = "";
                    break;
            }
            this.info.setText(str2 + str);
        }
    }

    private boolean manualGridMode() {
        return "manual".equals(this.prefs.getString(PREF_GRID_MODE, "auto"));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void toggleGridMode() {
        if (!manualGridMode()) {
            if (CandyVision.Grid.decode(this.prefs.getString(PREF_GRID, null), "manual") == null) {
                toast("No saved grid yet. Press Calibrate first.");
                return;
            }
            this.prefs.edit().putString(PREF_GRID_MODE, "manual").apply();
        } else {
            this.prefs.edit().putString(PREF_GRID_MODE, "auto").apply();
        }
        this.locked = null;
        refreshButtons();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cycleGravity() {
        int i = this.prefs.getInt(PREF_GRAVITY, -1) + 1;
        this.prefs.edit().putInt(PREF_GRAVITY, i <= 3 ? i : -1).apply();
        refreshButtons();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public int effectiveGravity() {
        int i = this.prefs.getInt(PREF_GRAVITY, -1);
        if (i < 0) {
            int i2 = 0;
            for (int i3 = 0; i3 < 4; i3++) {
                if (this.gravityVotes[i3] > this.gravityVotes[i2]) {
                    i2 = i3;
                }
            }
            int i4 = 0;
            for (int i5 = 0; i5 < 4; i5++) {
                if (i5 != i2) {
                    i4 = Math.max(i4, this.gravityVotes[i5]);
                }
            }
            if (this.gravityVotes[i2] < 3 || this.gravityVotes[i2] < i4 + 2) {
                return 0;
            }
            return i2;
        }
        return i;
    }

    int[] panelRect() {
        if (this.panel == null || this.panel.getVisibility() != 0 || !this.panel.isAttachedToWindow()) {
            return null;
        }
        int[] iArr = new int[2];
        this.panel.getLocationOnScreen(iArr);
        return new int[]{iArr[0], iArr[1], iArr[0] + this.panel.getWidth(), iArr[1] + this.panel.getHeight()};
    }

    private static final class Result {
        Board board;
        String error;
        int frameH;
        int frameW;
        CandyVision.Grid grid;
        long ms;

        private Result() {
        }
    }

    private void capture(final ResultSink sink) {
        final int i = this.readId + 1;
        this.readId = i;
        final boolean[] zArr = {false};
        // Hide the panel while the frame is taken so it can never cover part of the board.
        final View p = this.panel;
        if (p != null) p.setVisibility(View.INVISIBLE);
        final ResultSink resultSink = r -> {
            if (p != null) p.setVisibility(View.VISIBLE);
            sink.accept(r);
        };
        this.handler.postDelayed(() -> {
            if (i == this.readId && !zArr[0]) CaptureService.requestFrame(new AnonymousClass11(null, i, zArr, resultSink));
        }, 130L);
        this.handler.postDelayed(new Runnable() { // from class: com.nickrocks.pixelflowbot.CandyMode.12
            @Override // java.lang.Runnable
            public void run() {
                if (i == CandyMode.this.readId && !zArr[0]) {
                    zArr[0] = true;
                    CaptureService.requestFrame(null);
                    if (CandyMode.this.lastResult != null && System.currentTimeMillis() - CandyMode.this.lastResultAt < 15000) {
                        resultSink.accept(CandyMode.this.lastResult);
                        return;
                    }
                    Result result = new Result();
                    result.error = "no new frame";
                    resultSink.accept(result);
                }
            }
        }, 1500L);
    }

    /* renamed from: com.nickrocks.pixelflowbot.CandyMode$11, reason: invalid class name */
    class AnonymousClass11 implements Consumer<Frame> {
        final /* synthetic */ boolean[] val$done;
        final /* synthetic */ int[] val$exclude;
        final /* synthetic */ int val$id;
        final /* synthetic */ ResultSink val$sink;

        AnonymousClass11(int[] iArr, int i, boolean[] zArr, ResultSink resultSink) {
            this.val$exclude = iArr;
            this.val$id = i;
            this.val$done = zArr;
            this.val$sink = resultSink;
        }

        @Override // java.util.function.Consumer
        public void accept(final Frame frame) {
            CandyMode.this.worker.execute(new Runnable() { // from class: com.nickrocks.pixelflowbot.CandyMode.11.1
                @Override // java.lang.Runnable
                public void run() {
                    final Result analyse = CandyMode.this.analyse(frame, AnonymousClass11.this.val$exclude);
                    CandyMode.this.handler.post(new Runnable() { // from class: com.nickrocks.pixelflowbot.CandyMode.11.1.1
                        @Override // java.lang.Runnable
                        public void run() {
                            if (AnonymousClass11.this.val$id == CandyMode.this.readId && !AnonymousClass11.this.val$done[0]) {
                                AnonymousClass11.this.val$done[0] = true;
                                CandyMode.this.lastResult = analyse;
                                CandyMode.this.lastResultAt = System.currentTimeMillis();
                                AnonymousClass11.this.val$sink.accept(analyse);
                            }
                        }
                    });
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Result analyse(Frame frame, int[] iArr) {
        CandyVision.Grid detect;
        Result result = new Result();
        long currentTimeMillis = System.currentTimeMillis();
        result.frameW = frame.w;
        result.frameH = frame.h;
        try {
            if (manualGridMode()) {
                detect = CandyVision.Grid.decode(this.prefs.getString(PREF_GRID, null), "manual");
                if (detect == null) {
                    result.error = "no calibrated grid (press Calibrate)";
                    return result;
                }
            } else {
                detect = this.vision.detect(frame.px, frame.w, frame.h);
                synchronized (this) {
                    if (detect == null) {
                        detect = this.locked;
                        if (detect == null) {
                            result.error = "board not found: " + this.vision.lastNote;
                            return result;
                        }
                    } else if (this.locked == null || this.locked.similar(detect)) {
                        this.locked = detect;
                        this.candidate = null;
                    } else {
                        if (this.candidate == null || !this.candidate.similar(detect)) {
                            this.candidate = detect;
                            this.candidateHits = 1;
                        } else {
                            this.candidateHits++;
                        }
                        if (this.candidateHits >= 3 && !this.holdGrid) {
                            this.locked = detect;
                            this.candidate = null;
                        }
                        detect = this.locked;
                    }
                }
            }
            Board read = this.vision.read(frame.px, frame.w, frame.h, detect, iArr);
            result.grid = detect;
            result.board = read;
        } catch (Throwable th) {
            Log.e(TAG, "analyse failed", th);
            result.error = "error: " + th;
        } finally {
            result.ms = System.currentTimeMillis() - currentTimeMillis;
        }
        return result;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void readOnce() {
        if (this.state != 1 && !this.readBusy) {
            if (CaptureService.isRunning()) {
                this.readBusy = true;
                setInfo("Reading…");
                capture(new ResultSink() { // from class: com.nickrocks.pixelflowbot.CandyMode.13
                    /* JADX WARN: Multi-variable type inference failed */
                    @Override // com.nickrocks.pixelflowbot.CandyMode.ResultSink
                    public void accept(Result result) {
                        List arrayList;
                        CandyMode.this.readBusy = false;
                        if (result.board == null) {
                            CandyMode.this.setInfo("Can't read the board: " + result.error);
                            return;
                        }
                        CandyMode.this.solver.setGravity(CandyMode.this.effectiveGravity());
                        String problem = result.error != null ? result.error : BoardCheck.problem(result.board, null, 0.0f);
                        if (problem == null) {
                            arrayList = CandyMode.this.solver.rank(result.board, null);
                        } else {
                            arrayList = new ArrayList();
                        }
                        CandySolver.Move move = arrayList.isEmpty() ? null : (CandySolver.Move) arrayList.get(0);
                        StringBuilder sb = new StringBuilder();
                        sb.append(result.grid).append(" · ").append(result.ms).append(" ms\n");
                        if (problem != null) {
                            sb.append(problem).append('\n');
                        }
                        sb.append(CandyMode.this.vision.lastNote).append(", ").append(arrayList.size()).append(" moves\n");
                        sb.append(move == null ? "No move" : "Would swipe " + move + ": " + move.why);
                        CandyMode.this.setInfo(sb.toString());
                        Log.i(CandyMode.TAG, "Read:\n" + result.board.toText());
                        CandyMode.this.showGrid(result.board, move, 4000L);
                    }
                });
                return;
            }
            toast("Capture is off. Start it from the app first.");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void start() {
        if (this.state != 2) {
            if (this.state == 0) {
                if (CaptureService.isRunning()) {
                    String currentPackage = currentPackage();
                    if (currentPackage == null || currentPackage.equals("?") || currentPackage.equals(this.svc.getPackageName())) {
                        toast("Open Candy Crush first, then press Start.");
                        return;
                    }
                    this.gamePkg = currentPackage;
                    this.state = 1;
                    this.moves = 0;
                    this.parked = false;
                    candyCompact(true);
                    movePanel(dp(4));
                    resetReadState();
                    this.banned.clear();
                    Arrays.fill(this.gravityVotes, 0);
                    this.locked = null;
                    this.baseline = 0.0f;
                    this.rec = new Recorder(this.svc);
                    this.rec.line("{\"t\":" + System.currentTimeMillis() + ",\"event\":\"start\",\"mode\":\"candy\",\"app\":" + Recorder.q(this.gamePkg) + ",\"version\":\"0.6-candy\"}");
                    refreshButtons();
                    setInfo((currentPackage.equals(CANDY_PKG) ? "" : "Note: app in front is " + currentPackage + ", not Candy Crush Saga.\n") + "Starting… log: " + this.rec.where());
                    this.handler.postDelayed(this.stepRunnable, 300L);
                    return;
                }
                toast("Start screen capture in the app first.");
                return;
            }
            return;
        }
        resume();
    }

    private void resetReadState() {
        this.unreadable = 0;
        this.rejectStreak = 0;
        this.noMove = 0;
        this.stableReads = 0;
        this.lastSig = null;
        this.preSwipeSig = null;
        this.pending = null;
        this.prevBoard = null;
        this.lastSwipeAt = 0L;
        this.holdGrid = false;
        this.lastProblem = null;
    }

    private void resume() {
        String currentPackage = currentPackage();
        if (this.gamePkg == null || this.gamePkg.equals(currentPackage)) {
            this.state = 1;
            resetReadState();
            this.baseline = 0.0f;
            if (this.rec != null) {
                this.rec.line("{\"t\":" + System.currentTimeMillis() + ",\"event\":\"resume\"}");
            }
            refreshButtons();
            setInfo("Resuming…");
            this.handler.postDelayed(this.stepRunnable, 300L);
            return;
        }
        toast("Open Candy Crush first, then Resume.");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pause(String str) {
        if (this.state == 1) {
            this.state = 2;
            this.handler.removeCallbacks(this.stepRunnable);
            if (this.rec != null) {
                this.rec.line("{\"t\":" + System.currentTimeMillis() + ",\"event\":\"pause\",\"why\":" + Recorder.q(str) + "}");
            }
            refreshButtons();
            setInfo("Paused: " + str);
            toast(str);
        }
    }

    private void end(String str) {
        if (this.state != 0) {
            this.state = 0;
            candyCompact(false);
            this.handler.removeCallbacks(this.stepRunnable);
            CaptureService.requestFrame(null);
            if (this.rec != null) {
                this.rec.line("{\"t\":" + System.currentTimeMillis() + ",\"event\":\"end\",\"why\":" + Recorder.q(str) + ",\"moves\":" + this.moves + "}");
            }
            refreshButtons();
            setInfo("Auto-play ended: " + str + "\nMoves: " + this.moves + (this.rec != null ? "\nLog: " + this.rec.where() : ""));
            toast("Auto-play ended: " + str);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void stopEverything() {
        cancelNext();
        end("stopped by you");
        TapService.stopEverything();
    }

    private void later(long j) {
        this.handler.removeCallbacks(this.stepRunnable);
        if (this.state != 1 || this.dead) {
            return;
        }
        this.handler.postDelayed(this.stepRunnable, j);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void step() {
        if (this.state == 1 && !this.dead) {
            if (TapService.isRunning()) {
                if (CaptureService.isRunning()) {
                    String currentPackage = currentPackage();
                    if (this.gamePkg == null || this.gamePkg.equals(currentPackage)) {
                        capture(new ResultSink() { // from class: com.nickrocks.pixelflowbot.CandyMode.15
                            @Override // com.nickrocks.pixelflowbot.CandyMode.ResultSink
                            public void accept(Result result) {
                                CandyMode.this.onRead(result);
                            }
                        });
                        return;
                    } else {
                        pause("Candy Crush is not in front (" + currentPackage + ")");
                        return;
                    }
                }
                end("screen capture stopped");
                return;
            }
            end("tap service stopped");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onRead(Result result) {
        String str;
        if (this.state == 1 && !this.dead) {
            long currentTimeMillis = System.currentTimeMillis();
            Board board = result.board;
            String str2 = result.error;
            String problem = str2 == null ? BoardCheck.problem(board, this.prevBoard, this.baseline) : str2;
            if (problem != null || board == null) {
                this.unreadable++;
                String replaceAll = String.valueOf(problem).replaceAll("[0-9]+", "#");
                if (this.rec != null && !replaceAll.equals(this.lastProblem)) {
                    this.rec.line("{\"t\":" + currentTimeMillis + ",\"event\":\"read_problem\",\"why\":" + Recorder.q(String.valueOf(problem)) + (result.grid != null ? ",\"grid\":" + Recorder.q(result.grid.toString()) : "") + (board != null ? ",\"board\":" + Recorder.q(board.toText().replace('\n', '/')) : "") + "}");
                }
                this.lastProblem = replaceAll;
                if (this.unreadable >= MAX_UNREADABLE) {
                    if (this.moves > 0) {
                        str = "the board is gone — level finished or a popup appeared (" + problem + ")";
                    } else {
                        str = "can't find the Candy Crush board (" + problem + "). Try Read, or Calibrate.";
                    }
                    if (this.rec != null) {
                        this.rec.line("{\"t\":" + currentTimeMillis + ",\"event\":\"lost_board\",\"why\":" + Recorder.q(String.valueOf(problem)) + "}");
                    }
                    if (this.moves <= 0) {
                        pause(str);
                        return;
                    } else {
                        end(str);
                        candyNext();
                        return;
                    }
                }
                setInfo("Looking for the board… (" + problem + ")");
                later(450L);
                return;
            }
            this.unreadable = 0;
            this.lastProblem = null;
            if (!this.parked) {
                this.parked = true;
                if (parkOffBoard(board)) { later(400L); return; }
            }
            if (this.baseline <= 0.0f) {
                this.baseline = board.candyBrightness;
            } else {
                this.baseline = (0.9f * this.baseline) + (0.1f * board.candyBrightness);
            }
            String signature = board.signature();
            if (signature.equals(this.lastSig)) {
                this.stableReads++;
            } else {
                this.stableReads = 0;
                this.lastSig = signature;
            }
            if (this.pending != null) {
                long j = currentTimeMillis - this.lastSwipeAt;
                if (signature.equals(this.preSwipeSig)) {
                    if (j >= REJECT_MS) {
                        this.banned.add(this.pending.key());
                        this.rejectStreak++;
                        if (this.rec != null) {
                            this.rec.line("{\"t\":" + currentTimeMillis + ",\"event\":\"rejected\",\"move\":" + Recorder.q(this.pending.toString()) + "}");
                        }
                        this.pending = null;
                        this.holdGrid = false;
                        if (this.rejectStreak >= 5) {
                            pause("5 swipes in a row didn't change the board (locked candies, or the board is covered?)");
                            return;
                        }
                    } else {
                        later(STEP_MS);
                        return;
                    }
                } else if ((this.stableReads >= 1 && j >= SETTLE_MS) || j >= 9000) {
                    this.moves++;
                    this.rejectStreak = 0;
                    this.banned.clear();
                    voteGravity(this.prevBoard, board, this.pending);
                    this.pending = null;
                    this.holdGrid = false;
                    this.noMove = 0;
                } else {
                    setInfo(this.line.startsWith("Move") ? this.line : "Waiting for the board to settle…");
                    later(STEP_MS);
                    return;
                }
            }
            if (this.stableReads >= 1 && currentTimeMillis - this.lastSwipeAt >= 900) {
                if (this.moves < MAX_MOVES) {
                    this.solver.setGravity(effectiveGravity());
                    List<CandySolver.Move> rank = this.solver.rank(board, this.banned);
                    if (rank.isEmpty()) {
                        this.noMove++;
                        if (this.noMove >= 12) {
                            pause("No valid move on the board as read. Press Read to see what the bot sees.");
                            return;
                        } else {
                            setInfo("No move found — waiting (the game may shuffle)…");
                            later(800L);
                            return;
                        }
                    }
                    CandySolver.Move move = rank.get(0);
                    int[] panelRect = panelRect();
                    float cx = board.cx(move.c1);
                    float cy = board.cy(move.r1);
                    float cx2 = board.cx(move.c2);
                    float cy2 = board.cy(move.r2);
                    if (panelRect != null && (inRect(panelRect, cx, cy) || inRect(panelRect, cx2, cy2))) {
                        pause("The panel covers the board. Drag the panel (top line) away from the board.");
                        return;
                    }
                    if (!swipe(cx, cy, cx2, cy2)) {
                        pause("Swipe rejected (is the accessibility service still enabled?)");
                        return;
                    }
                    this.pending = move;
                    this.holdGrid = true;
                    this.preSwipeSig = signature;
                    this.prevBoard = board;
                    this.lastSwipeAt = currentTimeMillis;
                    this.stableReads = 0;
                    setInfo("Move " + (this.moves + 1) + ": " + move + " · " + move.why);
                    if (this.rec != null) {
                        this.rec.line("{\"t\":" + currentTimeMillis + ",\"event\":\"move\",\"n\":" + (this.moves + 1) + ",\"swipe\":" + Recorder.q(move.toString()) + ",\"why\":" + Recorder.q(move.why) + ",\"score\":" + (Math.round(move.score * 10.0d) / 10.0d) + ",\"gravity\":\"" + CandySolver.GRAVITY_NAMES[this.solver.getGravity()] + "\",\"grid\":" + Recorder.q(String.valueOf(result.grid)) + ",\"board\":" + Recorder.q(board.toText().replace('\n', '/')) + "}");
                    }
                    later(450L);
                    return;
                }
                end("move limit reached (150)");
                return;
            }
            later(STEP_MS);
        }
    }

    private static boolean inRect(int[] iArr, float f, float f2) {
        return f >= ((float) iArr[0]) && f <= ((float) iArr[2]) && f2 >= ((float) iArr[1]) && f2 <= ((float) iArr[3]);
    }

    private void voteGravity(Board board, Board board2, CandySolver.Move move) {
        if (board != null && board2 != null && board.rows == board2.rows && board.cols == board2.cols) {
            int min = Math.min(move.r1, move.r2);
            int max = Math.max(move.r1, move.r2);
            int min2 = Math.min(move.c1, move.c2);
            int max2 = Math.max(move.c1, move.c2);
            int i = 0;
            int i2 = 0;
            int i3 = 0;
            int i4 = 0;
            for (int i5 = 0; i5 < board2.rows; i5++) {
                for (int i6 = 0; i6 < board2.cols; i6++) {
                    int idx = board2.idx(i5, i6);
                    if (board.kind[idx] != board2.kind[idx] || board.color[idx] != board2.color[idx] || board.special[idx] != board2.special[idx]) {
                        if (i6 >= min2 - 1 && i6 <= max2 + 1) {
                            if (i5 < min) {
                                i++;
                            } else if (i5 > max) {
                                i2++;
                            }
                        }
                        if (i5 >= min - 1 && i5 <= max + 1) {
                            if (i6 < min2) {
                                i3++;
                            } else if (i6 > max2) {
                                i4++;
                            }
                        }
                    }
                }
            }
            int abs = Math.abs(i - i2);
            int abs2 = Math.abs(i3 - i4);
            if (abs < abs2 || abs < 2) {
                if (abs2 <= abs || abs2 < 2) {
                    return;
                }
                int[] iArr = this.gravityVotes;
                char c = i3 > i4 ? (char) 3 : (char) 2;
                iArr[c] = iArr[c] + 1;
                return;
            }
            int[] iArr2 = this.gravityVotes;
            char c2 = i > i2 ? (char) 0 : (char) 1;
            iArr2[c2] = iArr2[c2] + 1;
        }
    }

    private boolean swipe(float f, float f2, float f3, float f4) {
        Path path = new Path();
        path.moveTo(f, f2);
        path.lineTo(f3, f4);
        return this.svc.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0L, SWIPE_MS)).build(), new AccessibilityService.GestureResultCallback() { // from class: com.nickrocks.pixelflowbot.CandyMode.16
            @Override // android.accessibilityservice.AccessibilityService.GestureResultCallback
            public void onCancelled(GestureDescription gestureDescription) {
                Log.w(CandyMode.TAG, "swipe cancelled");
            }
        }, this.handler);
    }

    private WindowManager.LayoutParams fullScreenParams(boolean z) {
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(-1, -1, 2032, z ? 776 : 792, -3);
        layoutParams.gravity = 51;
        if (Build.VERSION.SDK_INT >= 28) {
            layoutParams.layoutInDisplayCutoutMode = 1;
        }
        return layoutParams;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showGrid(final Board board, final CandySolver.Move move, long j) {
        removeGrid();
        final Paint paint = new Paint(1);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        final Paint paint2 = new Paint(1);
        paint2.setColor(-1);
        paint2.setTextAlign(Paint.Align.CENTER);
        paint2.setTypeface(Typeface.DEFAULT_BOLD);
        paint2.setShadowLayer(4.0f, 0.0f, 0.0f, -16777216);
        final Paint paint3 = new Paint(1);
        paint3.setColor(-5317);
        paint3.setStrokeWidth(dp(4));
        paint3.setStyle(Paint.Style.STROKE);
        View view = new View(this.svc) { // from class: com.nickrocks.pixelflowbot.CandyMode.17
            @Override // android.view.View
            protected void onDraw(Canvas canvas) {
                int[] r8 = new int[2]; getLocationOnScreen(r8);
                float f = board.cell / 2.0f;
                paint2.setTextSize(board.cell * 0.28f);
                for (int r = 0; r < board.rows; r++) {
                    for (int c = 0; c < board.cols; c++) {
                        int idx = board.idx(r, c);
                        float cx = board.cx(c) - r8[0];
                        float cy = board.cy(r) - r8[1];
                        int k = board.kind[idx];
                        if (k != 0) {
                            paint.setColor(k == 2 ? -1442781697 : (k == 1 ? -1426089984 : -1426122940));
                            canvas.drawRect(2.0f + (cx - f), 2.0f + (cy - f), (cx + f) - 2.0f, (cy + f) - 2.0f, paint);
                            canvas.drawText(board.cellText(idx).trim(), cx, (board.cell * 0.1f) + cy, paint2);
                        }
                    }
                }
                if (move != null) {
                    canvas.drawLine(board.cx(move.c1) - r8[0], board.cy(move.r1) - r8[1], board.cx(move.c2) - r8[0], board.cy(move.r2) - r8[1], paint3);
                    canvas.drawCircle(board.cx(move.c1) - r8[0], board.cy(move.r1) - r8[1], board.cell * 0.18f, paint3);
                }
            }
        };
        try {
            this.wm.addView(view, fullScreenParams(false));
            this.gridOverlay = view;
            this.handler.postDelayed(new Runnable() { // from class: com.nickrocks.pixelflowbot.CandyMode.18
                @Override // java.lang.Runnable
                public void run() {
                    CandyMode.this.removeGrid();
                }
            }, j);
        } catch (Throwable th) {
            Log.e(TAG, "grid overlay failed", th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeGrid() {
        if (this.gridOverlay != null) {
            try {
                this.wm.removeView(this.gridOverlay);
            } catch (Throwable th) {
            }
            this.gridOverlay = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void calibrate() {
        if (this.state != 1) {
            if (this.calibOverlay == null) {
                final float[] fArr = new float[6];
                final int[] iArr = {0};
                final Paint paint = new Paint();
                paint.setColor(1711276032);
                final Paint paint2 = new Paint(1);
                paint2.setColor(-1);
                paint2.setTextSize(dp(16));
                paint2.setTextAlign(Paint.Align.CENTER);
                paint2.setShadowLayer(4.0f, 0.0f, 0.0f, -16777216);
                final Paint paint3 = new Paint(1);
                paint3.setColor(-5317);
                paint3.setStrokeWidth(dp(2));
                paint3.setStyle(Paint.Style.STROKE);
                final String[] strArr = {"1/3: tap the CENTRE of the top-left cell", "2/3: tap the centre of the cell just RIGHT of it", "3/3: tap the centre of the bottom-right cell"};
                View view = new View(this.svc) { // from class: com.nickrocks.pixelflowbot.CandyMode.19
                    long downAt;

                    @Override // android.view.View
                    protected void onDraw(Canvas canvas) {
                        canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), paint);
                        int[] r8 = new int[2]; getLocationOnScreen(r8);
                        float height = 0.12f * getHeight();
                        canvas.drawText(iArr[0] < 3 ? strArr[iArr[0]] : "", getWidth() / 2.0f, height, paint2);
                        canvas.drawText("(use the board's outer corners even if they are holes · long-press to cancel)", getWidth() / 2.0f, height + CandyMode.this.dp(CandyMode.MAX_UNREADABLE), paint2);
                        for (int i = 0; i < iArr[0]; i++) {
                            float f = fArr[i * 2] - r8[0];
                            float f2 = fArr[(i * 2) + 1] - r8[1];
                            canvas.drawCircle(f, f2, CandyMode.this.dp(14), paint3);
                            canvas.drawLine(f - CandyMode.this.dp(20), f2, f + CandyMode.this.dp(20), f2, paint3);
                            canvas.drawLine(f, f2 - CandyMode.this.dp(20), f, f2 + CandyMode.this.dp(20), paint3);
                        }
                    }

                    @Override // android.view.View
                    public boolean onTouchEvent(MotionEvent motionEvent) {
                        if (motionEvent.getActionMasked() == 0) {
                            this.downAt = motionEvent.getEventTime();
                        } else if (motionEvent.getActionMasked() == 1) {
                            if (motionEvent.getEventTime() - this.downAt > 800) {
                                CandyMode.this.removeCalib();
                                CandyMode.this.toast("Calibration cancelled");
                            } else {
                                fArr[iArr[0] * 2] = motionEvent.getRawX();
                                fArr[(iArr[0] * 2) + 1] = motionEvent.getRawY();
                                int[] iArr2 = iArr;
                                iArr2[0] = iArr2[0] + 1;
                                invalidate();
                                if (iArr[0] == 3) {
                                    CandyMode.this.finishCalibration(fArr);
                                }
                            }
                        }
                        return true;
                    }
                };
                try {
                    this.wm.addView(view, fullScreenParams(true));
                    this.calibOverlay = view;
                    return;
                } catch (Throwable th) {
                    toast("Couldn't show the calibration screen: " + th.getMessage());
                    return;
                }
            }
            removeCalib();
            return;
        }
        toast("Pause first.");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void finishCalibration(float[] fArr) {
        removeCalib();
        float hypot = (float) Math.hypot(fArr[2] - fArr[0], fArr[3] - fArr[1]);
        if (hypot >= dp(16)) {
            int round = Math.round((fArr[4] - fArr[0]) / hypot) + 1;
            int round2 = Math.round((fArr[5] - fArr[1]) / hypot) + 1;
            if (round < 3 || round2 < 3 || round > 12 || round2 > 14) {
                toast("That gives a " + round + "x" + round2 + " board, which looks wrong. Try again.");
                return;
            }
            this.prefs.edit().putString(PREF_GRID, new CandyVision.Grid(fArr[0], fArr[1], (((fArr[4] - fArr[0]) / (round - 1)) + ((fArr[5] - fArr[1]) / (round2 - 1))) / 2.0f, round2, round, "manual").encode()).putString(PREF_GRID_MODE, "manual").apply();
            this.locked = null;
            refreshButtons();
            toast("Grid saved: " + round + "x" + round2 + ". Grid mode is now manual.");
            this.handler.postDelayed(new Runnable() { // from class: com.nickrocks.pixelflowbot.CandyMode.20
                @Override // java.lang.Runnable
                public void run() {
                    CandyMode.this.readOnce();
                }
            }, 400L);
            return;
        }
        toast("Taps 1 and 2 were too close. Try again.");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeCalib() {
        if (this.calibOverlay != null) {
            try {
                this.wm.removeView(this.calibOverlay);
            } catch (Throwable th) {
            }
            this.calibOverlay = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void shutdown() {
        if (!this.dead) {
            this.dead = true;
            if (this.nono != null) this.nono.shutdown();
            if (this.amaze != null) this.amaze.shutdown();
            if (this.next != null) this.next.shutdown();
            if (this.state != 0 && this.rec != null) {
                this.rec.line("{\"t\":" + System.currentTimeMillis() + ",\"event\":\"end\",\"why\":\"panel closed\",\"moves\":" + this.moves + "}");
            }
            this.state = 0;
            this.handler.removeCallbacksAndMessages(null);
            removeGrid();
            removeCalib();
            this.worker.shutdownNow();
            if (current == this) {
                current = null;
            }
        }
    }
}
