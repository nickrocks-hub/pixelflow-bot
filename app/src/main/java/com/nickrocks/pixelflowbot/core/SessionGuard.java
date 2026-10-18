package com.nickrocks.pixelflowbot.core;

import java.util.Locale;

/**
 * The rules for when an agent must stop acting, shared by every game. Pure logic (no Android),
 * so it is unit-tested. The session asks {@link #check} before every action and pauses with the
 * returned reason if it isn't null.
 *
 * Pauses when: the user pressed stop · capture or the tap service is gone · another app or a
 * system dialog is in front · the reading is too unsure · the screen is a menu, ad, purchase or
 * login (anything the profile hasn't marked as gameplay) · too many actions in a row changed nothing.
 */
public final class SessionGuard {
    public float minConfidence = 0.6f;
    public int maxNoEffect = 4;
    /** Screens the agent may act on. Default: gameplay only. A walk-through agent also allows menus and level ends. */
    public final java.util.Set<StateObservation.Screen> playable = java.util.EnumSet.of(StateObservation.Screen.GAMEPLAY);

    public static final class Status {
        public boolean stopRequested;
        public boolean captureOn;
        public boolean controlOn;
        public String foregroundPackage;   // package in front, "?" if unknown
        public String ownPackage;          // this app (its own overlay may be "in front")
        public StateObservation latest;
        public int actionsWithoutEffect;
    }

    /** Null = OK to act. Otherwise a short reason to pause. */
    public String check(GameProfile profile, Status s) {
        if (s.stopRequested) return "stopped by you";
        if (!s.controlOn) return "the tap service is off";
        if (!s.captureOn) return "screen capture is off";
        String fg = s.foregroundPackage;
        if (profile.packageName != null && fg != null && !fg.equals("?") && !fg.equals(profile.packageName)
                && !fg.equals(s.ownPackage)) {
            return "another app is in front (" + fg + ")";
        }
        if (s.latest == null) return "nothing observed yet";
        if (s.latest.screen == StateObservation.Screen.BLOCKED) {
            return "an unrelated screen (ad, purchase, login or dialog) is showing" + (s.latest.problem != null ? ": " + s.latest.problem : "");
        }
        if (!playable.contains(s.latest.screen)) {
            return "not on a screen it may act on (" + s.latest.screen.name().toLowerCase(Locale.US) + ")";
        }
        if (s.latest.confidence < minConfidence) {
            return String.format(Locale.US, "not sure what's on screen (confidence %.2f < %.2f)", s.latest.confidence, minConfidence);
        }
        if (s.actionsWithoutEffect >= maxNoEffect) return s.actionsWithoutEffect + " actions in a row changed nothing";
        return null;
    }
}
