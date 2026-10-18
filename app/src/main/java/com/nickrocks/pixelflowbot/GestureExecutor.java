package com.nickrocks.pixelflowbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.SystemClock;

import com.nickrocks.pixelflowbot.core.ActionExecutor;
import com.nickrocks.pixelflowbot.core.GameAction;

import java.util.List;

/**
 * Performs {@link GameAction}s with Android accessibility gestures (the user must have turned on
 * the tap service). Taps, long presses, swipes, multi-point drags and several simultaneous
 * strokes (up to {@link GestureDescription#getMaxStrokeCount()}) are one gesture each. HOLD uses a
 * continued stroke that RELEASE finishes at the same place (Android 8+). MACRO is a record-only
 * kind and is refused.
 */
final class GestureExecutor implements ActionExecutor {
    private final AccessibilityService svc;
    private final Handler handler;
    private GestureDescription.StrokeDescription held;   // the stroke a HOLD left pressed
    private float heldX, heldY;

    GestureExecutor(AccessibilityService svc, Handler handler) {
        this.svc = svc;
        this.handler = handler;
    }

    @Override public int maxSimultaneousStrokes() { return GestureDescription.getMaxStrokeCount(); }

    @Override
    public void execute(GameAction a, Callback cb) {
        long t0 = SystemClock.uptimeMillis();
        List<GameAction.Stroke> ss = a.strokes();
        if (a.kind == GameAction.Kind.MACRO || a.kind == GameAction.Kind.LOGICAL || ss.isEmpty()) { cb.done(false, 0, "nothing to perform"); return; }
        if (ss.size() > maxSimultaneousStrokes()) { cb.done(false, 0, "too many simultaneous strokes for this phone"); return; }
        if (a.durationMs() > GestureDescription.getMaxGestureDuration()) { cb.done(false, 0, "gesture longer than Android allows"); return; }
        GestureDescription.Builder b = new GestureDescription.Builder();
        GestureDescription.StrokeDescription last = null;
        try {
            for (GameAction.Stroke s : ss) {
                Path p = new Path();
                if (a.kind == GameAction.Kind.RELEASE && held != null) {
                    p.moveTo(heldX, heldY);       // a continued stroke must start where the hold is
                    last = held.continueStroke(p, 0, s.durationMs, false);
                    held = null;
                } else {
                    p.moveTo(s.xy[0], s.xy[1]);
                    for (int i = 2; i < s.xy.length; i += 2) p.lineTo(s.xy[i], s.xy[i + 1]);
                    last = new GestureDescription.StrokeDescription(p, s.startMs, s.durationMs, s.continues);
                    if (s.continues) { heldX = s.xy[s.xy.length - 2]; heldY = s.xy[s.xy.length - 1]; }
                }
                b.addStroke(last);
            }
        } catch (RuntimeException e) {
            cb.done(false, 0, "couldn't build the gesture: " + e.getMessage());
            return;
        }
        final GestureDescription.StrokeDescription maybeHeld = a.kind == GameAction.Kind.HOLD ? last : null;
        boolean accepted = svc.dispatchGesture(b.build(), new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                if (maybeHeld != null) held = maybeHeld;
                cb.done(true, SystemClock.uptimeMillis() - t0, "");
            }
            @Override public void onCancelled(GestureDescription g) {
                held = null;
                cb.done(false, SystemClock.uptimeMillis() - t0, "cancelled (the screen was touched, or the app changed)");
            }
        }, handler);
        if (!accepted) cb.done(false, 0, "rejected — is the tap service still on?");
    }
}
