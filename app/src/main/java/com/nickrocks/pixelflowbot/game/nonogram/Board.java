package com.nickrocks.pixelflowbot.game.nonogram;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The visible game: clues plus what the player has marked. It never holds the solution —
 * the board can only judge "solved" the way a player can, by checking the clues.
 *
 * Cell states: {@link #UNKNOWN} (untouched), {@link #FILLED}, {@link #EMPTY} (marked empty, ✕).
 * Supports undo (moves can be grouped into one stroke, e.g. a finger drag) and restart.
 */
public final class Board {
    public static final int UNKNOWN = 0, FILLED = 1, EMPTY = 2;

    /** What happened when an action was applied. */
    public enum Result { CHANGED, NO_CHANGE, INVALID }

    public final int rows, cols;
    private final int[][] rowClues, colClues;
    private final int[] cells;

    private static final class Change {
        final int index, before, after;
        final int group;
        Change(int index, int before, int after, int group) {
            this.index = index; this.before = before; this.after = after; this.group = group;
        }
    }

    private final ArrayDeque<Change> undo = new ArrayDeque<>();
    private final List<Action> history = new ArrayList<>();
    private int group, strokeDepth;
    private int moves;

    public Board(int[][] rowClues, int[][] colClues) {
        this.rows = rowClues.length;
        this.cols = colClues.length;
        if (rows == 0 || cols == 0) throw new IllegalArgumentException("board needs at least one row and column");
        this.rowClues = deepCopy(rowClues);
        this.colClues = deepCopy(colClues);
        for (int[] c : this.rowClues) check(c, cols);
        for (int[] c : this.colClues) check(c, rows);
        this.cells = new int[rows * cols];
    }

    /** Board for a puzzle (clues only — the solution stays inside the puzzle). */
    public static Board of(Puzzle p) { return new Board(p.rowClues(), p.colClues()); }

    private static void check(int[] clue, int n) {
        if (clue == null) throw new IllegalArgumentException("missing clue");
        if (Clues.minLength(clue) > n) throw new IllegalArgumentException("clue " + Clues.text(clue) + " doesn't fit in " + n + " cells");
        for (int v : clue) if (v <= 0) throw new IllegalArgumentException("clue groups must be positive");
    }

    static int[][] deepCopy(int[][] a) {
        int[][] o = new int[a.length][];
        for (int i = 0; i < a.length; i++) o[i] = a[i].clone();
        return o;
    }

    // ------------------------------------------------------------------ reading

    public int[] rowClue(int r) { return rowClues[r].clone(); }
    public int[] colClue(int c) { return colClues[c].clone(); }
    public int[][] rowClues() { return deepCopy(rowClues); }
    public int[][] colClues() { return deepCopy(colClues); }

    public boolean inBounds(int r, int c) { return r >= 0 && c >= 0 && r < rows && c < cols; }

    public int get(int r, int c) {
        if (!inBounds(r, c)) throw new IndexOutOfBoundsException("cell r" + (r + 1) + " c" + (c + 1) + " is off the board");
        return cells[r * cols + c];
    }

    public int[] cells() { return cells.clone(); }

    /** Number of moves that changed the board (undo doesn't subtract). */
    public int moves() { return moves; }

    public List<Action> history() { return Collections.unmodifiableList(history); }

    public boolean canUndo() { return !undo.isEmpty(); }

    // ------------------------------------------------------------------ moves

    /** Applies an action. Off-board coordinates are rejected; a move that changes nothing is NO_CHANGE. */
    public Result apply(Action a) {
        if (a == null || !inBounds(a.row, a.col)) return Result.INVALID;
        int i = a.row * cols + a.col;
        int to = a.targetState();
        if (cells[i] == to) return Result.NO_CHANGE;
        if (strokeDepth == 0) group++;
        undo.push(new Change(i, cells[i], to, group));
        cells[i] = to;
        history.add(a);
        moves++;
        return Result.CHANGED;
    }

    /** Groups every change until {@link #endStroke} into one undo step (a drag across cells). */
    public void beginStroke() {
        if (strokeDepth++ == 0) group++;
    }

    public void endStroke() {
        if (strokeDepth > 0) strokeDepth--;
    }

    /** Undoes the last move (or the whole last stroke). Returns false if there's nothing to undo. */
    public boolean undo() {
        if (undo.isEmpty()) return false;
        int g = undo.peek().group;
        while (!undo.isEmpty() && undo.peek().group == g) {
            Change ch = undo.pop();
            cells[ch.index] = ch.before;
            if (!history.isEmpty()) history.remove(history.size() - 1);
        }
        return true;
    }

    /** Back to a blank board, history cleared. */
    public void restart() {
        java.util.Arrays.fill(cells, UNKNOWN);
        undo.clear();
        history.clear();
        moves = 0;
        strokeDepth = 0;
    }

    // ------------------------------------------------------------------ rules

    /** True when this row's filled cells produce exactly its clue. */
    public boolean rowSatisfied(int r) {
        boolean[] line = new boolean[cols];
        for (int c = 0; c < cols; c++) line[c] = cells[r * cols + c] == FILLED;
        return Clues.matches(rowClues[r], line);
    }

    public boolean colSatisfied(int c) {
        boolean[] line = new boolean[rows];
        for (int r = 0; r < rows; r++) line[r] = cells[r * cols + c] == FILLED;
        return Clues.matches(colClues[c], line);
    }

    /** Solved = the filled cells satisfy every row and column clue. Unknown cells count as not filled. */
    public boolean isSolved() {
        for (int r = 0; r < rows; r++) if (!rowSatisfied(r)) return false;
        for (int c = 0; c < cols; c++) if (!colSatisfied(c)) return false;
        return true;
    }

    /** A cheap fingerprint of the cell states, used to spot moves that go round in circles. */
    public long stateHash() {
        long h = 1469598103934665603L;
        for (int v : cells) { h ^= v + 1; h *= 1099511628211L; }
        return h;
    }

    /** What an agent may see. The last {@code historyLength} actions are included. */
    public Observation observe(int historyLength) {
        int from = Math.max(0, history.size() - Math.max(0, historyLength));
        return new Observation(rows, cols, rowClues, colClues, cells, history.subList(from, history.size()), moves);
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int v = cells[r * cols + c];
                sb.append(v == FILLED ? '#' : v == EMPTY ? 'x' : '.');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
