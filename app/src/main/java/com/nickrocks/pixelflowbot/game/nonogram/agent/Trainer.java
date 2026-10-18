package com.nickrocks.pixelflowbot.game.nonogram.agent;

import com.nickrocks.pixelflowbot.game.nonogram.NonogramEnv;
import com.nickrocks.pixelflowbot.game.nonogram.Puzzle;
import com.nickrocks.pixelflowbot.game.nonogram.PuzzleGenerator;
import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.SimRunner;

import java.util.function.BooleanSupplier;

/**
 * Trains a learning agent on TRAINING puzzles only. Uses the shared {@link SimRunner}: observe →
 * choose (ε-greedy) → act → reward → store experience → Q-learning update (+ replay).
 * The curriculum goes 5×5 first, then adds bigger boards once 5×5 is reliable.
 */
public final class Trainer {
    private final NonogramEnv env;
    public long episodeId = 1;

    public Trainer(PuzzleGenerator gen) {
        env = new NonogramEnv(gen);
        env.split = Puzzle.Split.TRAIN;
    }

    public void setSizes(int[] sizes) { env.sizes = sizes.clone(); }

    /** One training episode. The agent must be in TRAINING mode to learn. */
    public SimRunner.Result episode(LearnerPlayer p, BooleanSupplier stop) {
        if (p.agent.mode != Agent.Mode.TRAINING) throw new IllegalStateException("agent is not in training mode");
        return SimRunner.episode(p.agent, p.adapter, env, episodeId++, 100_000, stop);
    }
}
