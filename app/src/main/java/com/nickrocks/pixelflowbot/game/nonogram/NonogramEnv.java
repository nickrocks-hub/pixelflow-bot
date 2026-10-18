package com.nickrocks.pixelflowbot.game.nonogram;

import com.nickrocks.pixelflowbot.learn.Env;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Training environment for the in-app Nonogram.
 *
 * The agent gets an {@link Observation} (clues + visible cells + recent actions) and returns an
 * {@link Action}. This class keeps the real {@link Board} and the {@link Puzzle}, and uses the
 * puzzle's hidden solution ONLY to compute rewards and scoring statistics. Nothing it returns to
 * the agent contains the solution: the next state is again just an Observation. (An external game
 * such as Nonogram.com may give no such per-move feedback; this reward exists to train.)
 *
 * Rewards:
 *   fill a cell that is filled in the answer       +1.0
 *   mark empty a cell that is empty in the answer  +0.3
 *   fill / mark against the answer                 −2.0   (counted as a mistake)
 *   clear a cell that was wrong                    +0.5   (fixing a mistake)
 *   clear a cell that was right                    −1.0
 *   action that changes nothing                    −0.5   (repeat, no progress)
 *   action that returns the board to a state already seen this episode   −0.5 extra
 *   off-board coordinates                          −1.0   (rejected, board unchanged)
 *   solved (all clues satisfied)                   +10, episode ends
 *   step limit reached                             −5, episode ends
 */
public final class NonogramEnv implements Env<Observation> {
    public static float R_FILL_OK = 1f, R_MARK_OK = 0.3f, R_WRONG = -2f, R_FIX = 0.5f, R_UNDO_RIGHT = -1f,
            R_NO_CHANGE = -0.5f, R_LOOP = -0.5f, R_INVALID = -1f, R_SOLVED = 10f, R_TIMEOUT = -5f;
    public static final int HISTORY = 16;

    private final PuzzleGenerator gen;
    public int[] sizes = {5};
    /** Which set {@link #reset} draws from. Training uses TRAIN; evaluation uses {@link #load}. */
    public Puzzle.Split split = Puzzle.Split.TRAIN;

    private Puzzle puzzle;
    private Board board;
    private final Set<Long> seen = new HashSet<>();
    private int steps, stepLimit;

    // Episode statistics (scoring only; never part of an observation).
    public int mistakes, repeats, invalid, fixes;

    public NonogramEnv(PuzzleGenerator gen) { this.gen = gen; }

    public Puzzle puzzle() { return puzzle; }
    public int steps() { return steps; }

    /** A training puzzle chosen with {@code rnd} (reproducible for a seeded rnd). */
    @Override
    public Observation reset(Random rnd) {
        int size = sizes[rnd.nextInt(sizes.length)];
        int index = split == Puzzle.Split.EVAL ? rnd.nextInt(PuzzleGenerator.evalCount(size)) : rnd.nextInt(1 << 20);
        return load(gen.get(split, size, index));
    }

    /** Starts an episode on a specific puzzle (used for evaluation and for watching the AI play). */
    public Observation load(Puzzle p) {
        puzzle = p;
        board = Board.of(p);
        seen.clear();
        seen.add(board.stateHash());
        steps = mistakes = repeats = invalid = fixes = 0;
        stepLimit = 3 * p.rows * p.cols;
        return board.observe(HISTORY);
    }

    public Observation observe() { return board.observe(HISTORY); }

    /** True when the filled cells satisfy every clue. */
    public boolean solved() { return board.isSolved(); }

    /** Cells that currently disagree with the answer (scoring/inspection only). */
    public int wrongCells() {
        int n = 0;
        int[] cells = board.cells();
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == Board.FILLED && !puzzle.solutionFilled(i)) n++;
            if (cells[i] == Board.EMPTY && puzzle.solutionFilled(i)) n++;
        }
        return n;
    }

    @Override
    public Outcome<Observation> step(Observation ignored, Object actionObj) {
        Outcome<Observation> o = new Outcome<>();
        steps++;
        Action a = actionObj instanceof Action ? (Action) actionObj : null;
        if (a == null || !board.inBounds(a.row, a.col)) {
            invalid++;
            o.reward = R_INVALID;
            o.note = "invalid coordinates";
        } else {
            int i = a.row * board.cols + a.col;
            int before = board.get(a.row, a.col);
            Board.Result res = board.apply(a);
            if (res == Board.Result.NO_CHANGE) {
                repeats++;
                o.reward = R_NO_CHANGE;
                o.note = "no change";
            } else {
                boolean answer = puzzle.solutionFilled(i);
                switch (a.type) {
                    case FILL:
                        if (answer) { o.reward = R_FILL_OK; o.note = "filled"; }
                        else { o.reward = R_WRONG; o.note = "wrong fill"; mistakes++; }
                        break;
                    case MARK_EMPTY:
                        if (!answer) { o.reward = R_MARK_OK; o.note = "marked"; }
                        else { o.reward = R_WRONG; o.note = "wrong mark"; mistakes++; }
                        break;
                    default:
                        boolean wasWrong = (before == Board.FILLED) != answer;
                        if (wasWrong) { o.reward = R_FIX; o.note = "fixed"; fixes++; }
                        else { o.reward = R_UNDO_RIGHT; o.note = "cleared a correct cell"; }
                }
                if (!seen.add(board.stateHash())) {
                    repeats++;
                    o.reward += R_LOOP;
                    o.note += " (repeated position)";
                }
            }
        }
        if (board.isSolved()) {
            o.done = true;
            o.win = true;
            o.reward += R_SOLVED;
            o.note = "solved";
        } else if (steps >= stepLimit) {
            o.done = true;
            o.reward += R_TIMEOUT;
            o.note += " — step limit";
        }
        o.next = board.observe(HISTORY);
        return o;
    }
}
