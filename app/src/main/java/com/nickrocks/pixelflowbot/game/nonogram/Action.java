package com.nickrocks.pixelflowbot.game.nonogram;

import java.util.Locale;

/** One move: fill a cell, mark it empty, or clear it back to unknown. Coordinates are 0-based. */
public final class Action {
    public enum Type { FILL, MARK_EMPTY, CLEAR }

    public final Type type;
    public final int row, col;

    public Action(Type type, int row, int col) {
        if (type == null) throw new IllegalArgumentException("action type is required");
        this.type = type;
        this.row = row;
        this.col = col;
    }

    public static Action fill(int r, int c) { return new Action(Type.FILL, r, c); }
    public static Action markEmpty(int r, int c) { return new Action(Type.MARK_EMPTY, r, c); }
    public static Action clear(int r, int c) { return new Action(Type.CLEAR, r, c); }

    /** The cell state this action produces. */
    public int targetState() {
        switch (type) {
            case FILL: return Board.FILLED;
            case MARK_EMPTY: return Board.EMPTY;
            default: return Board.UNKNOWN;
        }
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Action)) return false;
        Action a = (Action) o;
        return a.type == type && a.row == row && a.col == col;
    }

    @Override public int hashCode() { return (type.ordinal() * 1009 + row) * 1009 + col; }

    @Override public String toString() {
        String verb = type == Type.FILL ? "fill" : type == Type.MARK_EMPTY ? "mark" : "clear";
        return String.format(Locale.US, "%s r%d c%d", verb, row + 1, col + 1);
    }
}
