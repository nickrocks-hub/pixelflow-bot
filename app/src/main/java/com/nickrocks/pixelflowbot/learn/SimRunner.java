package com.nickrocks.pixelflowbot.learn;

import java.util.List;

/** Plays whole episodes in a simulator with an agent (any game). */
public final class SimRunner {
    private SimRunner() { }

    public static final class Result {
        public boolean win;
        public float reward;
        public int steps;
    }

    public static <S> Result episode(Agent agent, GameAdapter<S> ad, Env<S> env, long episodeId, int maxSteps,
                                     java.util.function.BooleanSupplier stop) {
        Result res = new Result();
        S s = env.reset(agent.rnd);
        for (int step = 0; step < maxSteps; step++) {
            if (stop != null && stop.getAsBoolean()) break;
            List<GameAdapter.Candidate> cands = ad.candidates(s);
            if (cands.isEmpty()) break;
            Agent.Choice ch = agent.choose(cands);
            GameAdapter.Candidate c = cands.get(ch.index);
            Env.Outcome<S> o = env.step(s, c.action);
            Transition t = new Transition();
            t.source = "sim";
            t.episode = episodeId;
            t.step = step;
            t.time = System.currentTimeMillis();
            t.state = agent.log != null ? ad.describe(s) : null;
            t.action = c.label;
            t.features = c.features;
            t.reward = o.reward;
            t.done = o.done;
            t.nextState = agent.log != null ? ad.describe(o.next) : null;
            t.nextBest = o.done ? null : agent.bestFeatures(ad.candidates(o.next));
            t.confidence = o.confidence;
            t.explored = ch.explored;
            t.q = ch.q;
            t.note = o.note;
            agent.record(t);
            res.reward += o.reward;
            res.steps++;
            s = o.next;
            if (o.done) { res.win = o.win; break; }
        }
        agent.endEpisode(res.win, res.reward);
        return res;
    }
}
