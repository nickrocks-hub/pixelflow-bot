package com.nickrocks.aibot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Score per run over time, with a moving average. Pink dots = Adaptive (learning) runs. */
public class TrendView extends View {
    private List<double[]> pts = new ArrayList<>();
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            avg = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TrendView(Context c) {
        super(c);
        axis.setColor(0xFF4A3A5C); axis.setStrokeWidth(dp(1));
        line.setColor(0xFF8E7CA6); line.setStrokeWidth(dp(1.5f)); line.setStyle(Paint.Style.STROKE);
        avg.setColor(0xFFF2C14E); avg.setStrokeWidth(dp(2.5f)); avg.setStyle(Paint.Style.STROKE);
        text.setColor(0xFFB9A8C9); text.setTextSize(dp(11));
    }

    private float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    public void setData(List<double[]> p) { pts = p; invalidate(); }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(170));
    }

    @Override
    protected void onDraw(Canvas c) {
        float l = dp(40), r = getWidth() - dp(10), t = dp(10), b = getHeight() - dp(22);
        c.drawLine(l, b, r, b, axis);
        c.drawLine(l, t, l, b, axis);
        if (pts.isEmpty()) { c.drawText("No finished runs yet", l + dp(10), (t + b) / 2, text); return; }
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (double[] p : pts) { mn = Math.min(mn, p[1]); mx = Math.max(mx, p[1]); }
        if (mx - mn < 1) { mx += 1; mn -= 1; }
        int n = pts.size();
        Path pl = new Path(), pa = new Path();
        double run = 0;
        for (int i = 0; i < n; i++) {
            double[] p = pts.get(i);
            float x = n == 1 ? (l + r) / 2 : l + (r - l) * i / (n - 1f);
            float y = (float) (b - (p[1] - mn) / (mx - mn) * (b - t));
            if (i == 0) pl.moveTo(x, y); else pl.lineTo(x, y);
            // moving average of the last 5
            run = 0; int k = 0;
            for (int j = Math.max(0, i - 4); j <= i; j++) { run += pts.get(j)[1]; k++; }
            float ya = (float) (b - (run / k - mn) / (mx - mn) * (b - t));
            if (i == 0) pa.moveTo(x, ya); else pa.lineTo(x, ya);
        }
        c.drawPath(pl, line);
        c.drawPath(pa, avg);
        for (int i = 0; i < n; i++) {
            double[] p = pts.get(i);
            float x = n == 1 ? (l + r) / 2 : l + (r - l) * i / (n - 1f);
            float y = (float) (b - (p[1] - mn) / (mx - mn) * (b - t));
            dot.setColor(p[2] > 0 ? 0xFFE8457A : 0xFF8E7CA6);
            c.drawCircle(x, y, dp(3), dot);
        }
        c.drawText(String.format(Locale.US, "%.0f", mx), dp(2), t + dp(8), text);
        c.drawText(String.format(Locale.US, "%.0f", mn), dp(2), b, text);
        c.drawText("runs →   score (yellow = 5-run average, pink = Adaptive)", l, getHeight() - dp(6), text);
    }
}
