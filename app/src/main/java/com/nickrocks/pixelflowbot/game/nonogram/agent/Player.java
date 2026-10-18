package com.nickrocks.pixelflowbot.game.nonogram.agent;

import com.nickrocks.pixelflowbot.game.nonogram.Action;
import com.nickrocks.pixelflowbot.game.nonogram.Observation;

/**
 * Anything that can play the in-app Nonogram from an {@link Observation}: the constraint-solver
 * baseline, the learning agent, or a random baseline. Players never see the solution.
 */
public interface Player {
    /** Where a decision came from — shown next to every AI move so solver logic is never passed off as learning. */
    enum Source {
        CONSTRAINT_SOLVER("Constraint solver (rules, not learned)"),
        LEARNED("Learned policy"),
        LEARNED_SOLVER_ASSISTED("Learned policy, solver-assisted features"),
        RANDOM("Random baseline");

        public final String label;
        Source(String label) { this.label = label; }
    }

    final class Decision {
        public final Action action;     // null = nothing to do / gives up
        public final Source source;
        public final String reason;
        public final float value;       // learned Q-value (NaN for the solver)

        public Decision(Action action, Source source, String reason, float value) {
            this.action = action;
            this.source = source;
            this.reason = reason;
            this.value = value;
        }
    }

    String name();

    Source source();

    Decision decide(Observation obs);
}
