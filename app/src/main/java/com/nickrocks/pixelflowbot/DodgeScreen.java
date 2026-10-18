package com.nickrocks.pixelflowbot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.nickrocks.pixelflowbot.game.dodge.DodgeState;

/** Dodge view: 5 lanes, falling blocks, your ship at the bottom. Tap the left or right half to move one lane. */
final class DodgeScreen extends View implements LabScreen<DodgeState> {
    private DodgeState s;
    private MoveListener listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), lane = new Paint(), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    DodgeScreen(Context c) {
        super(c);
        lane.setColor(0x14FFFFFF);
        text.setColor(Ui.TEXT_FAINT);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(dp(12));
    }

    @Override public View view() { return this; }
    @Override public void setMoveListener(MoveListener l) { listener = l; }
    @Override public Object idleAction() { return 0; }
    @Override public String help() { return "Tap the left or right half of the field to move one lane. Blocks fall faster every 10 s. Survive 60 s to win."; }

    @Override
    public void show(DodgeState state, Object lastAction) {
        s = state;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, Math.round(width * 1.25f));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (s == null) return;
        int L = DodgeState.LANES, R = DodgeState.ROWS;
        float lw = getWidth() / (float) L, rh = (getHeight() - dp(20)) / (float) R;
        for (int l = 0; l < L; l++) if (l % 2 == 0) c.drawRect(l * lw, 0, (l + 1) * lw, R * rh, lane);
        for (int row = 0; row < R; row++) {
            for (int l = 0; l < L; l++) {
                if (!s.at(row, l)) continue;
                p.setColor(0xFFF43F5E);
                r.set(l * lw + lw * 0.1f, row * rh + rh * 0.1f, (l + 1) * lw - lw * 0.1f, (row + 1) * rh - rh * 0.1f);
                c.drawRoundRect(r, dp(6), dp(6), p);
            }
        }
        p.setColor(s.crashed ? 0xFF64748B : 0xFF22D3EE);
        float cx = (s.lane + 0.5f) * lw, cy = (R - 0.5f) * rh;
        android.graphics.Path ship = new android.graphics.Path();
        ship.moveTo(cx, cy - rh * 0.4f);
        ship.lineTo(cx + lw * 0.32f, cy + rh * 0.38f);
        ship.lineTo(cx - lw * 0.32f, cy + rh * 0.38f);
        ship.close();
        c.drawPath(ship, p);
        c.drawText(s.crashed ? "Crashed" : "◀ tap left · tap right ▶", getWidth() / 2f, getHeight() - dp(4), text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && listener != null && s != null && !s.crashed) {
            listener.onMove(e.getX() < getWidth() / 2f ? -1 : 1);
            return true;
        }
        return e.getActionMasked() == MotionEvent.ACTION_DOWN;
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
