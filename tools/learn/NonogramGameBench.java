import com.nickrocks.pixelflowbot.game.nonogram.*;
import com.nickrocks.pixelflowbot.game.nonogram.agent.*;
import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.ModelStore;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/**
 * Desktop checks for the in-app Nonogram (same Java code as the app):
 *   1. rule tests (clues, board actions, undo, completion, invalid/no-op moves);
 *   2. puzzle sets: reproducible seeds, exactly one solution, training ∩ evaluation = ∅;
 *   3. observation hygiene: the agent-facing API exposes no solution;
 *   4. benchmark on the EVALUATION set: random vs constraint solver vs learner (untrained → trained),
 *      pure and solver-assisted, trained only on TRAINING puzzles.
 *
 * Build & run from the repo root:
 *   S=app/src/main/java/com/nickrocks/pixelflowbot
 *   javac -d /tmp/nb $S/game/nonogram/*.java $S/game/nonogram/agent/*.java $S/learn/*.java $S/nono/NonoSolver.java tools/learn/NonogramGameBench.java
 *   java -cp /tmp/nb NonogramGameBench [episodes=3000] [outDir]
 */
public class NonogramGameBench {
    static int fails = 0;

    static void check(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("FAIL: " + what); }
    }

    public static void main(String[] args) throws Exception {
        int episodes = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        File out = args.length > 1 ? new File(args[1]) : null;
        ruleTests();
        PuzzleGenerator gen = new PuzzleGenerator();
        puzzleTests(gen);
        hygieneTests();
        System.out.println(fails == 0 ? "All rule/puzzle tests passed." : fails + " test(s) FAILED");

        System.out.println("\n== Baselines on the evaluation set ==");
        for (int size : PuzzleGenerator.SIZES) {
            System.out.println(Evaluator.run(new RandomPlayer(1), gen, size, 99, null).line());
            System.out.println(Evaluator.run(new SolverPlayer(), gen, size, 99, null).line());
        }
        for (boolean assisted : new boolean[]{false, true}) {
            PlayAdapter ad = new PlayAdapter(assisted);
            Agent agent = new Agent(ad.id(), ad.featureNames(), 42);
            PlayAdapter.configure(agent);
            LearnerPlayer lp = new LearnerPlayer(agent, ad);
            System.out.println("\n== " + lp.name() + " (" + ad.featureNames().length + " features) ==");
            for (int size : PuzzleGenerator.SIZES) System.out.println("untrained  " + Evaluator.run(lp, gen, size, 99, null).line());
            Trainer tr = new Trainer(gen);
            int[][] stages = {{5}, {5, 10}, {5, 10, 15}};
            int per = episodes / 3;
            long t0 = System.currentTimeMillis();
            for (int st = 0; st < stages.length; st++) {
                tr.setSizes(stages[st]);
                agent.mode = Agent.Mode.TRAINING;
                for (int e = 0; e < per; e++) tr.episode(lp, null);
                System.out.printf(Locale.US, "after %d training episodes (sizes %s, %.1fs): %s%n", per * (st + 1),
                        Arrays.toString(stages[st]), (System.currentTimeMillis() - t0) / 1000f, agent.stats().line());
                for (int size : stages[st]) System.out.println("  eval   " + Evaluator.run(lp, gen, size, 99, null).line());
            }
            System.out.println("Top learned weights:\n" + agent.model.explain(12));
            if (out != null) {
                ModelStore ms = new ModelStore(new File(out, ad.id()), ad.id());
                System.out.println("saved " + ms.saveVersion(agent, "desktop " + episodes + " episodes"));
            }
        }
        if (fails > 0) System.exit(1);
    }

    static void ruleTests() {
        check(Arrays.equals(Clues.of(new boolean[]{true, true, true, false, true}), new int[]{3, 1}), "clue 3 1");
        check(Clues.of(new boolean[]{false, false}).length == 0, "empty line → empty clue");
        check(Clues.text(new int[0]).equals("0"), "empty clue shows 0");
        check(Arrays.equals(Clues.parse("3 1"), new int[]{3, 1}) && Clues.parse("0").length == 0, "parse");
        check(Clues.matches(new int[]{1, 1}, new boolean[]{true, false, true}), "groups touch edges");
        check(!Clues.matches(new int[]{2}, new boolean[]{true, false, true}), "separated groups are not one");
        check(Clues.minLength(new int[]{3, 1}) == 5, "min length");

        // 3x3 plus sign
        int[][] rc = {{1}, {3}, {1}}, cc = {{1}, {3}, {1}};
        Board b = new Board(rc, cc);
        check(b.apply(Action.fill(5, 0)) == Board.Result.INVALID, "invalid row rejected");
        check(b.apply(Action.fill(0, -1)) == Board.Result.INVALID, "invalid col rejected");
        check(b.apply(null) == Board.Result.INVALID, "null rejected");
        check(b.apply(Action.markEmpty(0, 0)) == Board.Result.CHANGED && b.get(0, 0) == Board.EMPTY, "mark empty");
        check(b.apply(Action.markEmpty(0, 0)) == Board.Result.NO_CHANGE, "repeat is no-op");
        check(b.apply(Action.clear(0, 0)) == Board.Result.CHANGED && b.get(0, 0) == Board.UNKNOWN, "clear");
        check(b.apply(Action.clear(0, 0)) == Board.Result.NO_CHANGE, "clear unknown is no-op");
        int[][] plus = {{0, 1}, {1, 0}, {1, 1}, {1, 2}, {2, 1}};
        for (int[] p : plus) b.apply(Action.fill(p[0], p[1]));
        check(b.isSolved(), "plus solved with unknown corners");
        b.apply(Action.fill(0, 0));
        check(!b.isSolved(), "extra fill breaks it");
        check(b.undo() && b.isSolved(), "undo restores");
        b.beginStroke();
        b.apply(Action.markEmpty(0, 0));
        b.apply(Action.markEmpty(0, 2));
        b.endStroke();
        check(b.get(0, 2) == Board.EMPTY && b.undo() && b.get(0, 0) == Board.UNKNOWN && b.get(0, 2) == Board.UNKNOWN, "stroke undo");
        b.restart();
        check(b.observe(5).count(Board.UNKNOWN) == 9 && !b.canUndo() && !b.isSolved(), "restart");
        int[][] empty = {{}, {}};
        Board z = new Board(empty, empty);
        check(z.isSolved(), "all-zero puzzle solved when nothing filled");
        try { new Board(new int[][]{{4}}, new int[][]{{1}, {1}, {1}}); check(false, "oversized clue accepted"); }
        catch (IllegalArgumentException ok) { }
    }

    static void puzzleTests(PuzzleGenerator gen) {
        for (int size : PuzzleGenerator.SIZES) {
            int n = PuzzleGenerator.evalCount(size);
            java.util.Set<String> eval = gen.evalFingerprints(size);
            check(eval.size() == n, size + ": evaluation puzzles are distinct");
            int overlap = 0;
            for (int i = 0; i < 300; i++) if (eval.contains(gen.get(Puzzle.Split.TRAIN, size, i).fingerprint())) overlap++;
            check(overlap == 0, size + ": training overlaps evaluation");
            for (int i = 0; i < n; i++) {
                Puzzle p = gen.get(Puzzle.Split.EVAL, size, i);
                LineSolver.Count c = new LineSolver(p.rowClues(), p.colClues()).countSolutions(null, 1_000_000);
                check(c.solutions == 1, p.id() + " has exactly one solution");
            }
            Puzzle a = gen.get(Puzzle.Split.EVAL, size, 3), b = new PuzzleGenerator().get(Puzzle.Split.EVAL, size, 3);
            check(a.fingerprint().equals(b.fingerprint()) && a.seed == b.seed, size + ": same seed → same puzzle");
            Puzzle s1 = gen.fromSeed(size, 12345), s2 = new PuzzleGenerator().fromSeed(size, 12345);
            check(s1.fingerprint().equals(s2.fingerprint()), size + ": custom seed reproducible");
        }
        // The solver baseline solves every evaluation puzzle without mistakes.
    }

    static void hygieneTests() {
        // The agent-facing types must not offer any way to reach the solution.
        for (Class<?> k : new Class<?>[]{Observation.class, Board.class}) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                check(!f.getType().equals(Puzzle.class), k.getSimpleName() + " holds a Puzzle");
                check(!f.getName().toLowerCase(Locale.US).contains("solution"), k.getSimpleName() + " has a solution field");
            }
        }
        for (java.lang.reflect.Method m : Puzzle.class.getMethods()) {
            check(!m.getName().toLowerCase(Locale.US).contains("solution"), "Puzzle exposes " + m.getName() + " publicly");
        }
    }
}
