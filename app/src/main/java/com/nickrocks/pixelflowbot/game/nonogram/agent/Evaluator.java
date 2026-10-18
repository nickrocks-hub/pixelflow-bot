package com.nickrocks.pixelflowbot.game.nonogram.agent;

import com.nickrocks.pixelflowbot.game.nonogram.NonogramEnv;
import com.nickrocks.pixelflowbot.game.nonogram.Observation;
import com.nickrocks.pixelflowbot.game.nonogram.Puzzle;
import com.nickrocks.pixelflowbot.game.nonogram.PuzzleGenerator;
import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.Env;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Scores a player on the fixed EVALUATION puzzles (never used for training). The player only
 * gets observations; the environment uses the hidden answer to count mistakes for the report.
 * Learning agents are switched to EVALUATION mode (greedy, no learning) for the run and restored after.
 */
public final class Evaluator {
    public static final class Report {
        public String player;
        public int size, puzzles, solved;
        public long steps, mistakes, repeats, gaveUp;
        public long ms;

        public float solveRate() { return puzzles == 0 ? 0 : solved / (float) puzzles; }

        public String line() {
            return String.format(Locale.US, "%-24s %2d×%-2d  solved %3d/%-3d (%5.1f%%)  steps/puzzle %6.1f  mistakes/puzzle %5.2f  repeats %4d  stuck %d",
                    player, size, size, solved, puzzles, 100 * solveRate(), puzzles == 0 ? 0 : steps / (float) puzzles,
                    puzzles == 0 ? 0 : mistakes / (float) puzzles, repeats, gaveUp);
        }
    }

    public static Report run(Player p, PuzzleGenerator gen, int size, int count, BooleanSupplier stop) {
        Agent.Mode prev = null;
        if (p instanceof LearnerPlayer) {
            prev = ((LearnerPlayer) p).agent.mode;
            ((LearnerPlayer) p).agent.mode = Agent.Mode.EVALUATION;
        }
        Report rep = new Report();
        rep.player = p.name();
        rep.size = size;
        long t0 = System.currentTimeMillis();
        try {
            int n = Math.min(count, PuzzleGenerator.evalCount(size));
            NonogramEnv env = new NonogramEnv(gen);
            for (int i = 0; i < n; i++) {
                if (stop != null && stop.getAsBoolean()) break;
                Puzzle pz = gen.get(Puzzle.Split.EVAL, size, i);
                Observation obs = env.load(pz);
                boolean done = false;
                while (!done) {
                    Player.Decision d = p.decide(obs);
                    if (d.action == null) { rep.gaveUp++; break; }
                    Env.Outcome<Observation> o = env.step(obs, d.action);
                    obs = o.next;
                    done = o.done;
                }
                rep.puzzles++;
                if (env.solved()) rep.solved++;
                rep.steps += env.steps();
                rep.mistakes += env.mistakes;
                rep.repeats += env.repeats;
            }
        } finally {
            if (prev != null) ((LearnerPlayer) p).agent.mode = prev;
        }
        rep.ms = System.currentTimeMillis() - t0;
        return rep;
    }
}
