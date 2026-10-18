package com.nickrocks.pixelflowbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nickrocks.pixelflowbot.game.nonogram.Action;
import com.nickrocks.pixelflowbot.game.nonogram.Board;
import com.nickrocks.pixelflowbot.game.nonogram.NonogramEnv;
import com.nickrocks.pixelflowbot.game.nonogram.Observation;
import com.nickrocks.pixelflowbot.game.nonogram.Puzzle;
import com.nickrocks.pixelflowbot.game.nonogram.PuzzleGenerator;
import com.nickrocks.pixelflowbot.game.nonogram.agent.LearnerPlayer;
import com.nickrocks.pixelflowbot.game.nonogram.agent.Player;
import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.ModelStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The in-app Nonogram: play it by touch, watch the AI play it, train the learning agent and
 * compare it with the constraint-solver baseline on the separate evaluation puzzles.
 */
public class NonogramActivity extends Activity {
    private static final int[] SIZES = {5, 10, 15};
    private static final String[] SPLIT_LABELS = {"Practice", "Training set", "Evaluation set"};
    private static final Puzzle.Split[] SPLITS = {Puzzle.Split.PLAY, Puzzle.Split.TRAIN, Puzzle.Split.EVAL};
    private static final String[] AI_LABELS = {"Solver (baseline)", "Learner", "Learner + hints"};
    private static final int[] AI_COLORS = {Ui.INFO, Ui.GAME_AI, 0xFF84CC16};

    private NonoGameHub hub;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService ai = Executors.newSingleThreadExecutor();

    private int size = 5, splitIdx = 0, index = 0;
    private long customSeed = -1;
    private Puzzle puzzle;
    private Board board;
    private long startedAt, solvedAt;
    private boolean solvedShown;

    private NonoBoardView boardView;
    private TextView puzzleTitle, puzzleInfo, status, decisionText, aiAbout, trainLive, weightsText, evalText, modelInfo;
    private Button[] sizeBtns, splitBtns, toolBtns, aiBtns, trainWhichBtns, trainSizeBtns, evalSizeBtns;
    private Button undoBtn, autoBtn, speedBtn, trainBtn, evalBtn, indexBtn;
    private Curve curve;

    private int aiWhich = 0;
    private boolean auto, fast;
    private int autoMoves;
    private boolean aiBusy;
    private int trainWhich = 0;
    private final boolean[] trainSizes = {true, true, false};
    private final boolean[] evalSizes = {true, true, true};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        hub = NonoGameHub.get(this);
        prefs = getSharedPreferences("nono_game", MODE_PRIVATE);
        size = prefs.getInt("size", 5);
        splitIdx = prefs.getInt("split", 0);
        index = prefs.getInt("index", 0);
        customSeed = prefs.getLong("seed", -1);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(18), dp(14), dp(28));

        TextView title = text("▦ Nonogram", 28, Color.WHITE);
        title.setTypeface(Ui.BLACK);
        root.addView(title);
        TextView sub = text("Fill cells so every row and column matches its clue: each number is a run of filled cells, in order, "
                + "with at least one empty cell between runs. “0” means the line is empty.", 13, Ui.TEXT_DIM);
        sub.setPadding(0, dp(4), 0, dp(6));
        root.addView(sub);

        // ---- Puzzle picker
        LinearLayout pick = card();
        sizeBtns = chips(pick, new String[]{"5×5", "10×10", "15×15"}, i -> { size = SIZES[i]; index = 0; customSeed = -1; loadPuzzle(); });
        splitBtns = chips(pick, SPLIT_LABELS, i -> { splitIdx = i; index = 0; customSeed = -1; loadPuzzle(); });
        LinearLayout nav = new LinearLayout(this);
        nav.addView(small("‹ Prev", Ui.MUTED, v -> { if (index > 0) { index--; customSeed = -1; loadPuzzle(); } }), weight());
        indexBtn = small("#1", Ui.MUTED, v -> seedDialog());
        nav.addView(indexBtn, weight());
        nav.addView(small("Next ›", Ui.MUTED, v -> { index++; customSeed = -1; loadPuzzle(); }), weight());
        pick.addView(nav);
        puzzleInfo = text("", 11, Ui.TEXT_FAINT);
        puzzleInfo.setPadding(dp(4), dp(6), 0, 0);
        pick.addView(puzzleInfo);
        root.addView(pick);

        // ---- Board
        puzzleTitle = text("", 15, Ui.NEON);
        puzzleTitle.setTypeface(Ui.MEDIUM);
        puzzleTitle.setPadding(dp(4), dp(14), 0, dp(6));
        root.addView(puzzleTitle);
        boardView = new NonoBoardView(this);
        boardView.setListener(new NonoBoardView.Listener() {
            @Override public void onMove(Action a, Board.Result r) { boardChanged(); }
            @Override public void onStrokeEnd() { boardChanged(); }
        });
        root.addView(boardView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        status = text("", 14, Ui.TEXT);
        status.setTypeface(Ui.MEDIUM);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, dp(4));
        root.addView(status);

        LinearLayout tools = new LinearLayout(this);
        String[] tl = {"■ Fill", "✕ Mark empty", "○ Clear"};
        toolBtns = new Button[3];
        for (int i = 0; i < 3; i++) {
            final int k = i;
            toolBtns[i] = small(tl[i], Ui.MUTED, v -> { boardView.tool = NonoBoardView.Tool.values()[k]; refreshTools(); });
            tools.addView(toolBtns[i], weight());
        }
        root.addView(tools);
        LinearLayout acts = new LinearLayout(this);
        undoBtn = small("↶ Undo", Ui.MUTED, v -> { stopAuto(); if (board.undo()) { boardView.highlight(-1, -1); boardChanged(); } });
        acts.addView(undoBtn, weight());
        acts.addView(small("⟲ Restart", Ui.MUTED, v -> restartDialog()), weight());
        acts.addView(small("💡 Hint", Ui.INFO, v -> hint()), weight());
        root.addView(acts);
        root.addView(note("Tap a cell to use the tool; drag along a row or column to do several at once. Tapping a cell that "
                + "already has the tool's state clears it. Hint asks the constraint solver (not the learner)."));

        // ---- Watch the AI
        root.addView(section("WATCH THE AI PLAY"));
        LinearLayout aiCard = card();
        aiBtns = chips(aiCard, AI_LABELS, i -> { aiWhich = i; refreshAi(); });
        aiAbout = text("", 12, Ui.TEXT_DIM);
        aiAbout.setPadding(dp(4), dp(6), 0, dp(4));
        aiCard.addView(aiAbout);
        LinearLayout aiRow = new LinearLayout(this);
        aiRow.addView(small("Step", Ui.MUTED, v -> { stopAuto(); aiStep(); }), weight());
        autoBtn = small("▶ Play", Ui.ACCENT, v -> { if (auto) stopAuto(); else startAuto(); });
        aiRow.addView(autoBtn, weight());
        speedBtn = small("Speed: slow", Ui.MUTED, v -> { fast = !fast; refreshAi(); });
        aiRow.addView(speedBtn, weight());
        aiCard.addView(aiRow);
        decisionText = text("The AI sees only the clues, the cells and its own recent moves — never the answer.", 12, Ui.TEXT);
        decisionText.setTypeface(Ui.MONO);
        decisionText.setPadding(dp(4), dp(8), 0, 0);
        aiCard.addView(decisionText);
        root.addView(aiCard);

        // ---- Training
        root.addView(section("TRAIN THE LEARNER"));
        LinearLayout tc = card();
        trainWhichBtns = chips(tc, new String[]{"Learner", "Learner + solver hints"}, i -> { if (!hub.training) { trainWhich = i; refreshTraining(); } });
        trainSizeBtns = chips(tc, new String[]{"5×5", "10×10", "15×15"}, i -> {
            trainSizes[i] = !trainSizes[i];
            if (!trainSizes[0] && !trainSizes[1] && !trainSizes[2]) trainSizes[i] = true;
            refreshTraining();
        });
        LinearLayout tr = new LinearLayout(this);
        trainBtn = small("▶ Train", Ui.ACCENT, v -> {
            if (hub.training) hub.stopTraining();
            else if (hub.evaluating) toast("Wait for the evaluation to finish.");
            else hub.startTraining(trainWhich, sizes(trainSizes));
        });
        tr.addView(trainBtn, weight());
        tr.addView(small("Save version", Ui.INFO, v -> saveVersion()), weight());
        tr.addView(small("Reset…", Ui.DANGER, v -> resetDialog()), weight());
        tc.addView(tr);
        trainLive = text("", 12, Ui.TEXT);
        trainLive.setTypeface(Ui.MONO);
        trainLive.setPadding(dp(4), dp(8), 0, 0);
        tc.addView(trainLive);
        curve = new Curve();
        tc.addView(curve, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)));
        modelInfo = text("", 11, Ui.TEXT_FAINT);
        modelInfo.setPadding(dp(4), dp(6), 0, 0);
        tc.addView(modelInfo);
        TextView wl = text("What it has learned (strongest weights):", 12, Ui.TEXT_DIM);
        wl.setPadding(dp(4), dp(10), 0, dp(2));
        tc.addView(wl);
        weightsText = text("", 11, Ui.TEXT);
        weightsText.setTypeface(Ui.MONO);
        weightsText.setPadding(dp(4), 0, 0, 0);
        tc.addView(weightsText);
        tc.addView(note("Trains only on the training puzzles. The training environment uses the hidden answer to give rewards "
                + "(correct fill +1, correct ✕ +0.3, wrong −2, solved +10); the agent itself only ever sees clues and cells. "
                + "“Learner + solver hints” is also given the single-line solver's verdict as input and is saved as a separate model."));
        root.addView(tc);

        // ---- Evaluation
        root.addView(section("EVALUATE"));
        LinearLayout ec = card();
        evalSizeBtns = chips(ec, new String[]{"5×5", "10×10", "15×15"}, i -> {
            evalSizes[i] = !evalSizes[i];
            if (!evalSizes[0] && !evalSizes[1] && !evalSizes[2]) evalSizes[i] = true;
            refreshEval();
        });
        evalBtn = small("Run evaluation", Ui.ACCENT, v -> {
            if (hub.evaluating) hub.stopEval = true;
            else if (hub.training) toast("Stop training first.");
            else { stopAuto(); hub.startEvaluation(sizes(evalSizes)); }
        });
        ec.addView(evalBtn);
        evalText = text("", 11, Ui.TEXT);
        evalText.setTypeface(Ui.MONO);
        evalText.setPadding(dp(4), dp(8), 0, 0);
        ec.addView(evalText);
        ec.addView(note("Fixed evaluation puzzles (60 × 5×5, 40 × 10×10, 20 × 15×15), kept out of training. "
                + "Learners play greedily and don't learn during evaluation."));
        root.addView(ec);

        ScrollView sv = new ScrollView(this);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Ui.BG, Ui.BG_2}));
        sv.addView(root);
        setContentView(sv);

        refreshTools();
        refreshAi();
        loadPuzzle();
    }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        stopAuto();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ai.shutdownNow();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshStatus();
            refreshTraining();
            refreshEval();
            handler.postDelayed(this, 500);
        }
    };

    // ------------------------------------------------------------------ puzzle

    private void loadPuzzle() {
        stopAuto();
        Puzzle.Split split = SPLITS[splitIdx];
        if (split == Puzzle.Split.EVAL) index = Math.min(index, PuzzleGenerator.evalCount(size) - 1);
        prefs.edit().putInt("size", size).putInt("split", splitIdx).putInt("index", index).putLong("seed", customSeed).apply();
        puzzleTitle.setText("Generating…");
        final int s = size, i = index;
        final long seed = customSeed;
        ai.execute(() -> {
            Puzzle p;
            try {
                p = seed >= 0 ? hub.gen.fromSeed(s, seed) : hub.gen.get(split, s, i);
            } catch (RuntimeException e) {
                handler.post(() -> toast("Couldn't make that puzzle: " + e.getMessage()));
                return;
            }
            handler.post(() -> show(p));
        });
    }

    private void show(Puzzle p) {
        puzzle = p;
        board = Board.of(p);
        boardView.setBoard(board);
        startedAt = System.currentTimeMillis();
        solvedAt = 0;
        solvedShown = false;
        autoMoves = 0;
        String set = customSeed >= 0 ? "Custom seed" : SPLIT_LABELS[splitIdx];
        puzzleTitle.setText(set + " · " + (customSeed >= 0 ? "seed " + customSeed : "#" + (index + 1)) + " · " + p.rows + "×" + p.cols);
        indexBtn.setText(customSeed >= 0 ? "Seed…" : "#" + (index + 1) + " · seed…");
        puzzleInfo.setText(String.format(Locale.US, "%s · seed %016x%s · exactly one solution (checked)%s",
                customSeed >= 0 ? "P" + p.rows + "-custom" : p.id(), p.seed,
                p.attempt > 0 ? " (+" + p.attempt + ")" : "",
                p.proofNodes <= 1 ? ", solvable by line logic" : ", needs some search"));
        for (int k = 0; k < 3; k++) Ui.tint(sizeBtns[k], SIZES[k] == size ? Ui.GAME_NONO : Ui.MUTED, dp(12));
        for (int k = 0; k < 3; k++) Ui.tint(splitBtns[k], k == splitIdx && customSeed < 0 ? Ui.GAME_NONO : Ui.MUTED, dp(12));
        decisionText.setText("The AI sees only the clues, the cells and its own recent moves — never the answer.");
        refreshStatus();
    }

    private void boardChanged() {
        boardView.invalidate();
        refreshStatus();
    }

    private void refreshStatus() {
        if (board == null) return;
        boolean solved = board.isSolved();
        if (solved && solvedAt == 0) solvedAt = System.currentTimeMillis();
        if (!solved) solvedAt = 0;
        long t = ((solved ? solvedAt : System.currentTimeMillis()) - startedAt) / 1000;
        if (solved) {
            status.setText(String.format(Locale.US, "🎉 Solved! %d moves · %d:%02d", board.moves(), t / 60, t % 60));
            status.setTextColor(0xFF4ADE80);
            if (!solvedShown) {
                solvedShown = true;
                boardView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
            }
        } else {
            int rowsDone = 0, colsDone = 0;
            for (int r = 0; r < board.rows; r++) if (board.rowSatisfied(r)) rowsDone++;
            for (int c = 0; c < board.cols; c++) if (board.colSatisfied(c)) colsDone++;
            status.setText(String.format(Locale.US, "%d moves · %d:%02d · rows %d/%d · columns %d/%d",
                    board.moves(), t / 60, t % 60, rowsDone, board.rows, colsDone, board.cols));
            status.setTextColor(Ui.TEXT);
            solvedShown = false;
        }
        undoBtn.setAlpha(board.canUndo() ? 1f : 0.5f);
        boardView.invalidate();
    }

    private void refreshTools() {
        for (int i = 0; i < 3; i++) {
            Ui.tint(toolBtns[i], boardView.tool.ordinal() == i ? (i == 0 ? Ui.GAME_NONO : i == 1 ? Ui.INFO : Ui.ACCENT) : Ui.MUTED, dp(12));
        }
    }

    private void restartDialog() {
        if (board == null || board.moves() == 0) { restart(); return; }
        new AlertDialog.Builder(this).setTitle("Restart this puzzle?").setMessage("All cells go back to unknown.")
                .setPositiveButton("Restart", (d, w) -> restart()).setNegativeButton("Cancel", null).show();
    }

    private void restart() {
        stopAuto();
        board.restart();
        boardView.highlight(-1, -1);
        startedAt = System.currentTimeMillis();
        solvedAt = 0;
        autoMoves = 0;
        boardChanged();
    }

    private void seedDialog() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("Puzzle number or seed");
        new AlertDialog.Builder(this).setTitle("Go to puzzle").setView(et)
                .setPositiveButton("Puzzle #", (d, w) -> {
                    try { index = Math.max(0, Integer.parseInt(et.getText().toString().trim()) - 1); customSeed = -1; loadPuzzle(); }
                    catch (NumberFormatException e) { toast("Enter a number."); }
                })
                .setNeutralButton("Custom seed", (d, w) -> {
                    try { customSeed = Math.abs(Long.parseLong(et.getText().toString().trim())); loadPuzzle(); }
                    catch (NumberFormatException e) { toast("Enter a number."); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ AI

    private void refreshAi() {
        for (int i = 0; i < 3; i++) Ui.tint(aiBtns[i], i == aiWhich ? AI_COLORS[i] : Ui.MUTED, dp(12));
        String[] about = {
                "Constraint solver: proves each move from the clues (line logic, contradiction tests, search). A baseline — it is not learned and never changes.",
                "Learning agent: picks moves with weights it learned from experience. Its inputs are counts and two hand-written tricks (overlap, unreachable cells) — no solver.",
                "Learning agent with solver hints: same, but it is also told what the single-line solver concludes for each cell. Its moves are labelled solver-assisted."};
        aiAbout.setText(about[aiWhich] + (aiWhich > 0 ? "\nModel: " + hub.loadNotes[aiWhich - 1] + modelLine(aiWhich - 1) : ""));
        autoBtn.setText(auto ? "⏸ Pause" : "▶ Play");
        Ui.tint(autoBtn, auto ? Ui.DANGER : Ui.ACCENT, dp(12));
        speedBtn.setText(fast ? "Speed: fast" : "Speed: slow");
    }

    private String modelLine(int k) {
        Agent a = hub.learners[k].agent;
        long eps = a.stats.get(Agent.Mode.TRAINING).episodes;
        return eps == 0 ? " · untrained (plays at random until trained)" : " · " + eps + " training puzzles";
    }

    private void startAuto() {
        if (hub.evaluating) { toast("Wait for the evaluation to finish."); return; }
        if (board.isSolved()) { toast("Already solved — Restart or pick another puzzle."); return; }
        auto = true;
        autoMoves = 0;
        refreshAi();
        aiStep();
    }

    private void stopAuto() {
        auto = false;
        if (autoBtn != null) refreshAi();
    }

    private void hint() {
        if (board == null || board.isSolved()) return;
        Observation obs = board.observe(NonogramEnv.HISTORY);
        ai.execute(() -> {
            Player.Decision d = hub.solver.decide(obs);
            handler.post(() -> {
                if (d.action == null) { decisionText.setText("Hint · " + d.source.label + "\n" + d.reason); return; }
                boardView.highlight(d.action.row, d.action.col);
                decisionText.setText("Hint · " + d.source.label + "\nSuggests: " + d.action + "\nWhy: " + d.reason);
            });
        });
    }

    /** One AI move on the current board: observe → decide (background) → apply. */
    private void aiStep() {
        if (board == null || aiBusy || hub.evaluating) return;
        if (board.isSolved()) { stopAuto(); return; }
        aiBusy = true;
        final Board target = board;
        final int movesBefore = board.moves();
        final Observation obs = board.observe(NonogramEnv.HISTORY);
        final int which = aiWhich;
        ai.execute(() -> {
            Player.Decision d;
            try {
                Player p = hub.player(which);
                if (p instanceof LearnerPlayer) {
                    Agent a = ((LearnerPlayer) p).agent;
                    synchronized (hub.lock) {
                        Agent.Mode prev = a.mode;
                        a.mode = Agent.Mode.PLAY;          // greedy, no learning while watching
                        try { d = p.decide(obs); } finally { a.mode = prev; }
                    }
                } else d = p.decide(obs);
            } catch (RuntimeException e) {
                d = new Player.Decision(null, Player.Source.CONSTRAINT_SOLVER, "error: " + e, Float.NaN);
            }
            final Player.Decision fd = d;
            handler.post(() -> applyDecision(target, movesBefore, fd));
        });
    }

    private void applyDecision(Board target, int movesBefore, Player.Decision d) {
        aiBusy = false;
        if (target != board || board.moves() != movesBefore) {   // the board changed meanwhile: decide again
            if (auto) handler.postDelayed(this::aiStep, 50);
            return;
        }
        if (d.action == null) {
            decisionText.setText(d.source.label + "\nNo move: " + d.reason);
            stopAuto();
            return;
        }
        Board.Result r = board.apply(d.action);
        boardView.highlight(d.action.row, d.action.col);
        autoMoves++;
        String res = r == Board.Result.CHANGED ? "" : r == Board.Result.NO_CHANGE ? "  (no change — repeated move)" : "  (invalid)";
        decisionText.setText(d.source.label + "\n" + d.action + res + "\nWhy: " + d.reason);
        boardChanged();
        if (board.isSolved()) { stopAuto(); return; }
        if (r != Board.Result.CHANGED) { stopAuto(); return; }
        if (auto && autoMoves > 3 * board.rows * board.cols) {
            decisionText.append("\nStopped: too many moves without solving.");
            stopAuto();
            return;
        }
        if (auto) handler.postDelayed(this::aiStep, fast ? 40 : 350);
    }

    // ------------------------------------------------------------------ training / evaluation

    private void refreshTraining() {
        for (int i = 0; i < 2; i++) Ui.tint(trainWhichBtns[i], i == (hub.training ? hub.trainingWhich : trainWhich) ? Ui.GAME_AI : Ui.MUTED, dp(12));
        for (int i = 0; i < 3; i++) Ui.tint(trainSizeBtns[i], trainSizes[i] ? Ui.INFO : Ui.MUTED, dp(12));
        trainBtn.setText(hub.training ? "■ Stop" : "▶ Train");
        Ui.tint(trainBtn, hub.training ? Ui.DANGER : Ui.ACCENT, dp(12));
        int k = hub.training ? hub.trainingWhich : trainWhich;
        Agent a = hub.learners[k].agent;
        {   // read without the lock: the trainer holds it for a whole puzzle, the UI must not wait
            trainLive.setText(hub.training
                    ? String.format(Locale.US, "%s%n%.0f steps/s", hub.trainLine, hub.stepsPerSecond)
                    : "Stopped. " + (a.stats.get(Agent.Mode.TRAINING).episodes == 0 ? "Not trained yet."
                    : "Trained on " + a.stats.get(Agent.Mode.TRAINING).episodes + " puzzles · " + a.model.updates + " updates."));
            weightsText.setText(a.model.updates == 0 ? "Nothing yet." : a.model.explain(8).trim());
        }
        List<ModelStore.Info> vs = hub.stores[k].list();
        modelInfo.setText(vs.isEmpty() ? "No saved versions yet (autosaves every 100 puzzles)." : "Latest saved: " + vs.get(0));
        curve.invalidate();
    }

    private void refreshEval() {
        for (int i = 0; i < 3; i++) Ui.tint(evalSizeBtns[i], evalSizes[i] ? Ui.INFO : Ui.MUTED, dp(12));
        evalBtn.setText(hub.evaluating ? "■ Stop evaluation" : "Run evaluation");
        Ui.tint(evalBtn, hub.evaluating ? Ui.DANGER : Ui.ACCENT, dp(12));
        String t = hub.evalText;
        evalText.setText(t.isEmpty() ? "No results yet." : t);
    }

    private void saveVersion() {
        int k = hub.training ? hub.trainingWhich : trainWhich;
        try {
            ModelStore.Info i = hub.saveVersion(k, "");
            toast("Saved " + hub.learners[k].name() + " v" + i.version);
        } catch (Exception e) { toast("Save failed: " + e.getMessage()); }
    }

    private void resetDialog() {
        if (hub.training) { toast("Stop training first."); return; }
        String name = hub.learners[trainWhich].name();
        new AlertDialog.Builder(this).setTitle("Reset " + name + "?")
                .setMessage("Deletes this model's weights, statistics and saved versions. The other model is not touched.")
                .setPositiveButton("Reset", (d, w) -> { hub.reset(trainWhich); toast(name + " reset."); refreshAi(); })
                .setNegativeButton("Cancel", null).show();
    }

    private static int[] sizes(boolean[] on) {
        int n = 0;
        for (boolean b : on) if (b) n++;
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < 3; i++) if (on[i]) out[k++] = SIZES[i];
        return out;
    }

    /** Training win rate over the last 50 puzzles. */
    private final class Curve extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        Curve() {
            super(NonogramActivity.this);
            line.setColor(Ui.NEON);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            grid.setColor(0x22FFFFFF);
            label.setColor(Ui.TEXT_FAINT);
            label.setTextSize(dp(10));
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), top = dp(14), bot = h - dp(4);
            for (int k = 0; k <= 4; k++) { float y = bot - (bot - top) * k / 4f; c.drawLine(0, y, w, y, grid); }
            c.drawText("training puzzles solved (last 50) — 100% at top", 0, dp(10), label);
            List<float[]> pts;
            pts = new ArrayList<>(hub.curve);
            if (pts.size() < 2) return;
            float e0 = pts.get(0)[0], e1 = pts.get(pts.size() - 1)[0];
            Path p = new Path();
            for (int i = 0; i < pts.size(); i++) {
                float x = e1 > e0 ? (pts.get(i)[0] - e0) / (e1 - e0) * w : 0;
                float y = bot - (bot - top) * pts.get(i)[1];
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            c.drawPath(p, line);
        }
    }

    // ------------------------------------------------------------------ widgets

    private interface IntCallback { void on(int i); }

    private Button[] chips(LinearLayout parent, String[] labels, IntCallback cb) {
        LinearLayout row = new LinearLayout(this);
        Button[] out = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            out[i] = small(labels[i], Ui.MUTED, v -> cb.on(k));
            row.addView(out[i], weight());
        }
        parent.addView(row);
        return out;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(4), dp(3), dp(2));
        return lp;
    }

    private TextView section(String s) {
        TextView t = text(s, 11, Ui.TEXT_FAINT);
        t.setTypeface(Ui.BLACK);
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(4), dp(22), 0, dp(8));
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 11, Ui.TEXT_FAINT);
        t.setPadding(dp(4), dp(6), 0, 0);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(10), dp(10), dp(10), dp(12));
        c.setBackground(Ui.card(dp(18), dp(1), Ui.CARD_STROKE));
        return c;
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
        b.setPadding(dp(6), dp(8), dp(6), dp(8));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(40));
        b.setOnClickListener(l);
        return b;
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
