package com.nickrocks.pixelflowbot.game.nonogram.agent;

import com.nickrocks.pixelflowbot.game.nonogram.Action;
import com.nickrocks.pixelflowbot.game.nonogram.Board;
import com.nickrocks.pixelflowbot.game.nonogram.Observation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Reference point: fills or marks a random unknown cell. Shows what "no skill" scores. */
public final class RandomPlayer implements Player {
    private final Random rnd;

    public RandomPlayer(long seed) { this.rnd = new Random(seed); }

    @Override public String name() { return "Random"; }
    @Override public Source source() { return Source.RANDOM; }

    @Override
    public Decision decide(Observation obs) {
        List<Integer> unknown = new ArrayList<>();
        for (int i = 0; i < obs.rows * obs.cols; i++) if (obs.cell(i) == Board.UNKNOWN) unknown.add(i);
        if (unknown.isEmpty()) return new Decision(null, Source.RANDOM, "no unknown cells", Float.NaN);
        int i = unknown.get(rnd.nextInt(unknown.size()));
        Action a = rnd.nextBoolean() ? Action.fill(i / obs.cols, i % obs.cols) : Action.markEmpty(i / obs.cols, i % obs.cols);
        return new Decision(a, Source.RANDOM, "random", Float.NaN);
    }
}
