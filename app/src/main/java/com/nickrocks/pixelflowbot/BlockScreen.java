package com.nickrocks.pixelflowbot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.nickrocks.pixelflowbot.game.blocks.BlockState;
import com.nickrocks.pixelflowbot.game.blocks.Blocks;

/**
 * Block Puzzle view: the 8×8 board with the tray of 3 pieces below. Drag a piece from the tray
 * onto the board (a ghost shows where it would land, red if it doesn't fit) and let go to place it.
 * Or tap a piece, then tap the board cell for its top-left corner.
 */
final class BlockScreen extends View implements LabScreen<BlockState> {
    private BlockState s;
    private Object last;
    private MoveListener listener;
    private int selected = -1;
    private float dragX = -1, dragY = -1;
    private boolean dragging;

    private final Paint cellP = new Paint(Paint.ANTI_ALIAS_FLAG), gridP = new Paint(), hiP = new Paint(Paint.ANTI_ALIAS_FLAG),
            textP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private float cell, left, top, trayTop, trayCell;

    BlockScreen(Context c) {
        super(c);
        gridP.setColor(0x22FFFFFF);
        hiP.setStyle(Paint.Style.STROKE);
        hiP.setStrokeWidth(dp(3));
        hiP.setColor(0xFFFACC15);
        textP.setColor(Ui.TEXT_FAINT);
        textP.setTextAlign(Paint.Align.CENTER);
        textP.setTextSize(dp(12));
    }

    @Override public View view() { return this; }
    @Override public void setMoveListener(MoveListener l) { listener = l; }
    @Override public Object idleAction() { return null; }
    @Override public String help() { return "Drag a piece from the tray onto the board. Full rows and columns clear. The game ends when no piece fits."; }

    @Override
    public void show(BlockState state, Object lastAction) {
        s = state;
        last = lastAction;
        if (s != null && selected >= 0 && s.tray[selected] < 0) selected = -1;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, Math.round(width * 1.38f));
    }

    private void layoutFor(int w) {
        cell = (w - dp(8)) / (float) Blocks.N;
        cell = Math.min(cell, dp(52));
        left = (w - cell * Blocks.N) / 2f;
        top = dp(4);
        trayTop = top + cell * Blocks.N + dp(18);
        trayCell = Math.min(cell * 0.55f, (w - dp(24)) / 15f);
    }

    private static int color(int shape) {
        int[] pal = {0xFF38BDF8, 0xFF22C55E, 0xFFF59E0B, 0xFFEC4899, 0xFFA78BFA, 0xFF14B8A6};
        return pal[shape % pal.length];
    }

    @Override
    protected void onDraw(Canvas c) {
        layoutFor(getWidth());
        if (s == null) return;
        int N = Blocks.N;
        float pad = Math.max(1f, cell * 0.06f);
        for (int r = 0; r < N; r++) {
            for (int k = 0; k < N; k++) {
                tmp.set(left + k * cell + pad, top + r * cell + pad, left + (k + 1) * cell - pad, top + (r + 1) * cell - pad);
                cellP.setColor(s.board[r * N + k] ? (s.over ? 0xFF64748B : Ui.GAME_NONO) : 0xFF232B52);
                c.drawRoundRect(tmp, cell * 0.14f, cell * 0.14f, cellP);
            }
        }
        // Last move outline.
        if (last instanceof Integer) {
            int a = (Integer) last, r0 = (a % 64) / N, c0 = a % N;
            c.drawRect(left + c0 * cell, top + r0 * cell, left + (c0 + 1) * cell, top + (r0 + 1) * cell, hiP);
        }
        // Ghost while dragging.
        if (selected >= 0 && dragging && s.tray[selected] >= 0) {
            int[] rc = anchor(dragX, dragY, s.tray[selected]);
            if (rc != null) {
                boolean ok = Blocks.fits(s.board, s.tray[selected], rc[0], rc[1]);
                cellP.setColor(ok ? 0x8838BDF8 : 0x88F43F5E);
                for (int[] d : Blocks.SHAPES[s.tray[selected]]) {
                    int rr = rc[0] + d[0], cc = rc[1] + d[1];
                    if (rr < 0 || cc < 0 || rr >= N || cc >= N) continue;
                    tmp.set(left + cc * cell + pad, top + rr * cell + pad, left + (cc + 1) * cell - pad, top + (rr + 1) * cell - pad);
                    c.drawRoundRect(tmp, cell * 0.14f, cell * 0.14f, cellP);
                }
            }
        }
        // Tray.
        float slotW = getWidth() / 3f;
        for (int k = 0; k < 3; k++) {
            int sh = s.tray[k];
            if (sh < 0) continue;
            if (dragging && k == selected) continue;
            float cx = slotW * k + slotW / 2f, cy = trayTop + trayCell * 2.6f;
            boolean fits = Blocks.fitsAnywhere(s.board, sh);
            drawPiece(c, sh, cx, cy, trayCell, fits ? color(sh) : 0xFF475569, k == selected);
        }
        c.drawText(s.over ? "Game over — no piece fits" : "tray", getWidth() / 2f, trayTop + trayCell * 5.6f, textP);
    }

    private void drawPiece(Canvas c, int sh, float cx, float cy, float u, int col, boolean sel) {
        float w = Blocks.width(sh) * u, h = Blocks.height(sh) * u;
        float x0 = cx - w / 2f, y0 = cy - h / 2f;
        cellP.setColor(col);
        for (int[] d : Blocks.SHAPES[sh]) {
            tmp.set(x0 + d[1] * u + 1, y0 + d[0] * u + 1, x0 + (d[1] + 1) * u - 1, y0 + (d[0] + 1) * u - 1);
            c.drawRoundRect(tmp, u * 0.15f, u * 0.15f, cellP);
        }
        if (sel) {
            tmp.set(x0 - dp(4), y0 - dp(4), x0 + w + dp(4), y0 + h + dp(4));
            c.drawRoundRect(tmp, dp(6), dp(6), hiP);
        }
    }

    /** Board cell for the piece's top-left so the piece is centred under the finger (lifted a bit). */
    private int[] anchor(float x, float y, int sh) {
        float liftY = y - cell * 1.2f;
        float px = x - Blocks.width(sh) * cell / 2f, py = liftY - Blocks.height(sh) * cell / 2f;
        int c0 = Math.round((px - left) / cell), r0 = Math.round((py - top) / cell);
        if (r0 < -4 || c0 < -4 || r0 > Blocks.N || c0 > Blocks.N) return null;
        return new int[]{r0, c0};
    }

    private int traySlotAt(float x, float y) {
        if (y < trayTop) return -1;
        int k = (int) (x / (getWidth() / 3f));
        return k >= 0 && k < 3 && s.tray[k] >= 0 ? k : -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (s == null || s.over || listener == null) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int k = traySlotAt(x, y);
                if (k >= 0) {
                    selected = k;
                    dragging = true;
                    dragX = x;
                    dragY = y;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
                // Tap on the board with a piece selected: top-left corner goes here.
                if (selected >= 0 && y < trayTop) {
                    int c0 = (int) ((x - left) / cell), r0 = (int) ((y - top) / cell);
                    tryPlace(r0, c0);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging) { dragX = x; dragY = y; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    dragging = false;
                    int[] rc = anchor(x, y, s.tray[selected]);
                    if (y < trayTop && rc != null) tryPlace(rc[0], rc[1]);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void tryPlace(int r, int c) {
        if (selected < 0 || s.tray[selected] < 0) return;
        if (!Blocks.fits(s.board, s.tray[selected], r, c)) {
            performHapticFeedback(android.view.HapticFeedbackConstants.REJECT);
            return;
        }
        int slot = selected;
        selected = -1;
        listener.onMove(BlockState.encode(slot, r, c));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
