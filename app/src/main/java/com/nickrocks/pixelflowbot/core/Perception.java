package com.nickrocks.pixelflowbot.core;

/**
 * Visual state extraction: turns one input (a screen {@code Frame}, or a simulator's own state)
 * into a {@link StateObservation} with confidences. One per adapter. Must not act on the game.
 */
public interface Perception<F> {
    StateObservation observe(F input, GameProfile profile);
}
