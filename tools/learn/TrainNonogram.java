import com.nickrocks.pixelflowbot.learn.*;
import com.nickrocks.pixelflowbot.learn.nono.*;
import java.io.File;

/**
 * Desktop training/evaluation for the Nonogram agent (same code the phone runs).
 *   java TrainNonogram <trainEpisodes> [modelDir]
 * Writes model_vN.json into modelDir; copy it to the phone (Download/PixelFlowBot/models) and
 * use "Import" in the AI Lab to load it.
 */
public class TrainNonogram {
    public static void main(String[] a) throws Exception {
        int train = a.length > 0 ? Integer.parseInt(a[0]) : 2000;
        File dir = new File(a.length > 1 ? a[1] : "models");
        NonoAdapter ad = new NonoAdapter();
        NonoSim sim = new NonoSim();
        Agent agent = new Agent(ad.id(), ad.featureNames(), 42);
        eval("before training", agent, ad, sim, 300);
        agent.mode = Agent.Mode.TRAINING;
        long t0 = System.nanoTime();
        long steps0 = agent.stats.get(Agent.Mode.TRAINING).steps;
        for (int e = 1; e <= train; e++) {
            SimRunner.episode(agent, ad, sim, e, 10000, null);
            if (e % (train / 5) == 0) {
                System.out.printf("  trained %5d episodes · ε=%.3f · %s%n", e, agent.epsilon(), agent.stats.get(Agent.Mode.TRAINING).line());
            }
        }
        long steps = agent.stats.get(Agent.Mode.TRAINING).steps - steps0;
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("  training: %d steps in %.1f s (%.0f steps/s incl. puzzle generation)%n", steps, secs, steps / secs);
        eval("after training", agent, ad, sim, 300);
        System.out.println("Learned weights (strongest first):\n" + agent.model.explain(12));
        ModelStore store = new ModelStore(dir, ad.id());
        System.out.println("saved " + store.saveVersion(agent, "desktop " + train + " episodes"));
    }

    static void eval(String label, Agent agent, NonoAdapter ad, NonoSim sim, int n) {
        Agent.Mode prev = agent.mode;
        agent.mode = Agent.Mode.EVALUATION;
        agent.stats.put(Agent.Mode.EVALUATION, new Stats());
        int[] sz = sim.sizes;
        for (int size : new int[]{5, 10, 15}) {
            sim.sizes = new int[]{size};
            Stats s = new Stats();
            agent.stats.put(Agent.Mode.EVALUATION, s);
            for (int e = 0; e < n; e++) SimRunner.episode(agent, ad, sim, -1 - e, 10000, null);
            System.out.printf("%-16s %2dx%-2d  %s%n", label, size, size, s.line());
        }
        sim.sizes = sz;
        agent.mode = prev;
    }
}
