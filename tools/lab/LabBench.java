import com.nickrocks.pixelflowbot.core.*;
import com.nickrocks.pixelflowbot.game.blocks.BlockGame;
import com.nickrocks.pixelflowbot.learn.Agent;
import java.util.*;

/**
 * Learning benchmark for lab games: baselines vs. learner (untrained, then after training) on the
 * fixed EVALUATION seeds; training uses only TRAINING seeds.
 *   java -cp … LabBench <game> <trainEpisodes> <evalEpisodes> <seed>
 */
public class LabBench {
    public static void main(String[] a) {
        String which = a.length > 0 ? a[0] : "blocks";
        int train = a.length > 1 ? Integer.parseInt(a[1]) : 1000, evalN = a.length > 2 ? Integer.parseInt(a[2]) : 100;
        long seed = a.length > 3 ? Long.parseLong(a[3]) : 1;
        if (which.equals("latency")) { latency(train, evalN, seed); return; }
        run(game(which), train, evalN, seed);
    }

    /** Dodge with simulated capture/tap lag: how much delay can a real-time game tolerate? */
    static void latency(int train, int evalN, long seed) {
        com.nickrocks.pixelflowbot.game.dodge.DodgeGame g0 = new com.nickrocks.pixelflowbot.game.dodge.DodgeGame();
        Agent fast = trained(g0, train, seed);
        System.out.println("delay = screen-capture lag + tap lag (ticks of 50 ms); seconds survived on " + evalN + " held-out games");
        for (int[] d : new int[][]{{0, 0}, {1, 0}, {1, 1}, {2, 1}, {2, 2}, {4, 2}, {6, 4}}) {
            com.nickrocks.pixelflowbot.game.dodge.DodgeGame g = new com.nickrocks.pixelflowbot.game.dodge.DodgeGame(d[0], d[1]);
            Agent here = trained(g, train, seed);
            LabGame.Baseline<com.nickrocks.pixelflowbot.game.dodge.DodgeState> rule = g.baselines().get(2);
            System.out.printf(Locale.US, "lag %3d ms: rule %5.1f s · learner trained without lag %5.1f s · learner trained with this lag %5.1f s%n",
                    (d[0] + d[1]) * 50,
                    Lab.evaluate(g, "rule", Lab.of(rule, 7), evalN, g.maxSteps(), null).meanScore,
                    Lab.evaluate(g, "fast", fast, evalN, g.maxSteps(), null).meanScore,
                    Lab.evaluate(g, "here", here, evalN, g.maxSteps(), null).meanScore);
        }
    }

    static <S> Agent trained(LabGame<S> g, int train, long seed) {
        Agent ag = new Agent(g.id(), g.adapter().featureNames(), seed);
        g.configure(ag);
        Lab.Trainer<S> tr = new Lab.Trainer<>(g, 0);
        ag.mode = Agent.Mode.TRAINING;
        for (int k = 0; k < train; k++) tr.episode(ag, g.maxSteps(), null);
        return ag;
    }

    static LabGame<?> game(String w) {
        if (w.equals("blocks")) return new BlockGame();
        try { return (LabGame<?>) Class.forName("com.nickrocks.pixelflowbot.game.dodge.DodgeGame").getDeclaredConstructor().newInstance(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static <S> void run(LabGame<S> g, int train, int evalN, long seed) {
        System.out.println("== " + g.title() + " (" + g.adapter().featureNames().length + " features), eval " + evalN + " held-out seeds");
        for (LabGame.Baseline<S> b : g.baselines())
            System.out.println(Lab.evaluate(g, b.name(), Lab.of(b, 7), evalN, g.maxSteps(), null).line(g.scoreName()));
        Agent ag = new Agent(g.id(), g.adapter().featureNames(), seed);
        g.configure(ag);
        System.out.println(Lab.evaluate(g, "Learner (untrained)", ag, evalN, g.maxSteps(), null).line(g.scoreName()));
        Lab.Trainer<S> tr = new Lab.Trainer<>(g, 0);
        long t0 = System.currentTimeMillis();
        int chunk = Math.max(1, train / 5);
        for (int done = 0; done < train; ) {
            ag.mode = Agent.Mode.TRAINING;
            for (int k = 0; k < chunk && done < train; k++, done++) tr.episode(ag, g.maxSteps(), null);
            System.out.printf(Locale.US, "  after %5d training episodes (%.0fs): %s%n", done, (System.currentTimeMillis() - t0) / 1000f,
                    Lab.evaluate(g, "Learner", ag, evalN, g.maxSteps(), null).line(g.scoreName()));
        }
        System.out.println("Learned weights:\n" + ag.model.explain(20));
    }
}
