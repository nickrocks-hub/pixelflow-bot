package com.nickrocks.pixelflowbot.game.nonogram.agent;

import com.nickrocks.pixelflowbot.game.nonogram.Action;
import com.nickrocks.pixelflowbot.game.nonogram.Observation;
import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.GameAdapter;

import java.util.List;
import java.util.Locale;

/**
 * The learning agent as a {@link Player}: the shared {@link Agent} (linear Q-learning) choosing
 * among the {@link PlayAdapter}'s candidate actions. Its skill comes from trained weights; with an
 * untrained model it plays at random. Decisions are labelled LEARNED, or LEARNED_SOLVER_ASSISTED
 * when the adapter feeds it single-line solver output.
 */
public final class LearnerPlayer implements Player {
    public final Agent agent;
    public final PlayAdapter adapter;

    public LearnerPlayer(Agent agent, PlayAdapter adapter) {
        this.agent = agent;
        this.adapter = adapter;
    }

    @Override public String name() { return adapter.assisted ? "Learner + solver hints" : "Learner"; }
    @Override public Source source() { return adapter.assisted ? Source.LEARNED_SOLVER_ASSISTED : Source.LEARNED; }

    @Override
    public Decision decide(Observation obs) {
        List<GameAdapter.Candidate> cands = adapter.candidates(obs);
        if (cands.isEmpty()) return new Decision(null, source(), "no sensible action", Float.NaN);
        Agent.Choice ch = agent.choose(cands);
        GameAdapter.Candidate c = cands.get(ch.index);
        String why = ch.explored ? "exploring (random try)"
                : String.format(Locale.US, "highest learned value %.2f (next best %.2f)", ch.q, ch.secondQ);
        return new Decision((Action) c.action, source(), why, ch.q);
    }
}
