package com.nickrocks.pixelflowbot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.nickrocks.pixelflowbot.game.nonogram.Action;
import com.nickrocks.pixelflowbot.game.nonogram.Board;
import com.nickrocks.pixelflowbot.game.nonogram.Clues;

/**
 * Draws a {@link Board} with its clues and handles touch play.
 *
 * Touch: put a finger on a cell to apply the current tool; drag along the row or column to apply
 * it to more cells in one stroke (one undo step). With Fill or Mark, starting on a cell that already
 * has that state clears instead, so tapping twice toggles. Fill/Mark strokes only change unknown
 * cells, so a drag never overwrites cells you've already decided.
 *
 * Look: unknown = plain dark cell, filled = solid blue, marked empty = small ✕ on a darker cell —
 * so "not decided yet" and "decided empty" can never be confused. A clue turns dim once its line
 * is satisfied.
 */
final class NonoBoardView extends View {
    interface Listener {
        void onMove(Action a, Board.Result r);
        void onStrokeEnd();
    }

    enum Tool { FILL, MARK, CLEAR }

    private Board board;
    private Listener listener;
    Tool tool = Tool.FILL;
    boolean locked;
    private int hiR = -1, hiC = -1;
    private int touchR = -1, touchC = -1;

    private final Paint cellP = new Paint(Paint.ANTI_ALIAS_FLAG), lineP = new Paint(), thickP = new Paint(),
            textP = new Paint(Paint.ANTI_ALIAS_FLAG), crossP = new Paint(Paint.ANTI_ALIAS_FLAG), hiP = new Paint(Paint.ANTI_ALIAS_FLAG),
            bandP = new Paint();
    private final RectF tmp = new RectF();

    // Layout (computed in onSizeChanged / when the board changes).
    private float cell, left, top, clueW, clueH;
    private int maxRowClue, maxColClue;

    NonoBoardView(Context c) {
        super(c);
        lineP.setColor(0x33FFFFFF);
        lineP.setStrokeWidth(dp(1));
        thickP.setColor(0x99FFFFFF);
        thickP.setStrokeWidth(dp(2));
        textP.setTypeface(Ui.MEDIUM);
        textP.setTextAlign(Paint.Align.CENTER);
        crossP.setStyle(Paint.Style.STROKE);
        crossP.setStrokeCap(Paint.Cap.ROUND);
        hiP.setStyle(Paint.Style.STROKE);
        hiP.setColor(0xFFFACC15);
        hiP.setStrokeWidth(dp(3));
        bandP.setColor(0x1467E8F9);
    }

    void setBoard(Board b) {
        board = b;
        hiR = hiC = -1;
        maxRowClue = maxColClue = 1;
        for (int r = 0; r < b.rows; r++) maxRowClue = Math.max(maxRowClue, b.rowClue(r).length);
        for (int c = 0; c < b.cols; c++) maxColClue = Math.max(maxColClue, b.colClue(c).length);
        layout(getWidth(), getHeight());
        requestLayout();
        invalidate();
    }

    void setListener(Listener l) { listener = l; }

    /** Outline the cell the AI just changed (−1 to clear). */
    void highlight(int r, int c) { hiR = r; hiC = c; invalidate(); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = w;
        if (board != null) {
            float clueCell = 0.62f;
            float unitsW = maxRowClue * clueCell + board.cols;
            float c = (w - dp(4)) / unitsW;
            c = Math.min(c, dp(56));
            h = Math.round(maxColClue * clueCell * c + board.rows * c + dp(4));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { layout(w, h); }

    private void layout(int w, int h) {
        if (board == null || w == 0) return;
        float clueCell = 0.62f;
        cell = Math.min((w - dp(4)) / (maxRowClue * clueCell + board.cols), dp(56));
        clueW = maxRowClue * clueCell * cell;
        clueH = maxColClue * clueCell * cell;
        float totalW = clueW + board.cols * cell;
        left = (w - totalW) / 2f + clueW;
        top = clueH + dp(2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (board == null) return;
        boolean solved = board.isSolved();
        int rows = board.rows, cols = board.cols;
        float gw = cols * cell, gh = rows * cell;

        // Row / column of the finger.
        if (touchR >= 0) {
            canvas.drawRect(left - clueW, top + touchR * cell, left + gw, top + (touchR + 1) * cell, bandP);
            canvas.drawRect(left + touchC * cell, top - clueH, left + (touchC + 1) * cell, top + gh, bandP);
        }

        // Clues.
        float clueStep = 0.62f * cell;
        textP.setTextSize(Math.min(cell * 0.46f, dp(18)));
        float ty = (textP.descent() + textP.ascent()) / 2f;
        for (int r = 0; r < rows; r++) {
            int[] clue = board.rowClue(r);
            textP.setColor(board.rowSatisfied(r) ? Ui.TEXT_FAINT : Ui.TEXT);
            String[] parts = clue.length == 0 ? new String[]{"0"} : Clues.text(clue).split(" ");
            float x = left - dp(4) - clueStep / 2f;
            for (int k = parts.length - 1; k >= 0; k--) {
                canvas.drawText(parts[k], x, top + (r + 0.5f) * cell - ty, textP);
                x -= clueStep;
            }
        }
        for (int c = 0; c < cols; c++) {
            int[] clue = board.colClue(c);
            textP.setColor(board.colSatisfied(c) ? Ui.TEXT_FAINT : Ui.TEXT);
            String[] parts = clue.length == 0 ? new String[]{"0"} : Clues.text(clue).split(" ");
            float y = top - dp(4) - clueStep / 2f;
            for (int k = parts.length - 1; k >= 0; k--) {
                canvas.drawText(parts[k], left + (c + 0.5f) * cell, y - ty, textP);
                y -= clueStep;
            }
        }

        // Cells.
        float pad = Math.max(1f, cell * 0.06f);
        crossP.setStrokeWidth(Math.max(dp(1.5f), cell * 0.07f));
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int v = board.get(r, c);
                float x0 = left + c * cell, y0 = top + r * cell;
                tmp.set(x0 + pad, y0 + pad, x0 + cell - pad, y0 + cell - pad);
                if (v == Board.FILLED) {
                    cellP.setColor(solved ? 0xFF22C55E : Ui.GAME_NONO);
                    canvas.drawRoundRect(tmp, cell * 0.12f, cell * 0.12f, cellP);
                } else if (v == Board.EMPTY) {
                    cellP.setColor(0xFF0B0F24);
                    canvas.drawRoundRect(tmp, cell * 0.12f, cell * 0.12f, cellP);
                    crossP.setColor(Ui.TEXT_FAINT);
                    float m = cell * 0.32f;
                    canvas.drawLine(x0 + m, y0 + m, x0 + cell - m, y0 + cell - m, crossP);
                    canvas.drawLine(x0 + cell - m, y0 + m, x0 + m, y0 + cell - m, crossP);
                } else {
                    cellP.setColor(0xFF232B52);
                    canvas.drawRoundRect(tmp, cell * 0.12f, cell * 0.12f, cellP);
                }
            }
        }

        // Grid lines (thick every 5).
        for (int r = 0; r <= rows; r++) canvas.drawLine(left, top + r * cell, left + gw, top + r * cell, r % 5 == 0 || r == rows ? thickP : lineP);
        for (int c = 0; c <= cols; c++) canvas.drawLine(left + c * cell, top, left + c * cell, top + gh, c % 5 == 0 || c == cols ? thickP : lineP);

        if (hiR >= 0 && hiR < rows && hiC >= 0 && hiC < cols) {
            tmp.set(left + hiC * cell + dp(1), top + hiR * cell + dp(1), left + (hiC + 1) * cell - dp(1), top + (hiR + 1) * cell - dp(1));
            canvas.drawRoundRect(tmp, cell * 0.15f, cell * 0.15f, hiP);
        }
    }

    // ------------------------------------------------------------------ touch

    private int startR, startC, axis;   // axis: 0 = undecided, 1 = row, 2 = column
    private Action.Type strokeType;
    private int strokeFrom;             // CLEAR strokes only clear cells in this state
    private boolean stroking;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (board == null || locked) return false;
        int c = (int) Math.floor((e.getX() - left) / cell), r = (int) Math.floor((e.getY() - top) / cell);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (!board.inBounds(r, c)) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                startR = r;
                startC = c;
                axis = 0;
                int v = board.get(r, c);
                strokeFrom = v;
                if (tool == Tool.CLEAR) strokeType = Action.Type.CLEAR;
                else if (tool == Tool.FILL) strokeType = v == Board.FILLED ? Action.Type.CLEAR : Action.Type.FILL;
                else strokeType = v == Board.EMPTY ? Action.Type.CLEAR : Action.Type.MARK_EMPTY;
                board.beginStroke();
                stroking = true;
                hiR = hiC = -1;
                applyAt(r, c, true);
                touchR = r;
                touchC = c;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!stroking) return true;
                if (axis == 0 && (r != startR || c != startC)) axis = Math.abs(r - startR) >= Math.abs(c - startC) ? 2 : 1;
                if (axis == 1) r = startR;
                if (axis == 2) c = startC;
                r = Math.max(0, Math.min(board.rows - 1, r));
                c = Math.max(0, Math.min(board.cols - 1, c));
                // Fill every cell between the start and the finger (fast drags skip cells otherwise).
                if (axis == 1) for (int k = Math.min(startC, c); k <= Math.max(startC, c); k++) applyAt(startR, k, false);
                else if (axis == 2) for (int k = Math.min(startR, r); k <= Math.max(startR, r); k++) applyAt(k, startC, false);
                touchR = r;
                touchC = c;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (stroking) {
                    board.endStroke();
                    stroking = false;
                    if (listener != null) listener.onStrokeEnd();
                }
                touchR = touchC = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void applyAt(int r, int c, boolean first) {
        int v = board.get(r, c);
        if (!first) {
            if (strokeType == Action.Type.CLEAR && v != strokeFrom) return;
            if (strokeType != Action.Type.CLEAR && v != Board.UNKNOWN) return;
        }
        Action a = new Action(strokeType, r, c);
        Board.Result res = board.apply(a);
        if (res == Board.Result.CHANGED) {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) listener.onMove(a, res);
        }
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
