package com.aigame.minesweeper.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exact Minesweeper logic. From the opened numbers it finds every tile that is certainly safe and
 * every tile that is certainly a mine, and when nothing is certain it works out the exact chance of
 * a mine under each closed tile (counting every arrangement of mines that fits the numbers and the
 * mine total), so a forced guess is always the least risky one.
 *
 * Cells use MineVision's values: -1 closed, -2 flagged (treated as closed, never tapped), 0..8.
 */
public final class MineSolver {

    public static final class Result {
        public final List<Integer> safe = new ArrayList<>();   // certain, cell indexes
        public final List<Integer> mines = new ArrayList<>();
        public double[] prob;                                  // per cell, NaN if opened
        public int guess = -1;                                 // best cell to try when nothing is safe
        public double guessRisk = 1;
        public boolean exact = true;                           // false if a region was too big to count fully
        public boolean contradiction;                          // the numbers don't fit together (misread)
        /** log of the number of mine layouts that fit (used to weigh "what would this tile show"). */
        public double logWays = Double.NEGATIVE_INFINITY;
    }

    /** Biggest connected region counted exactly; beyond it, local logic + estimates. */
    public static int MAX_EXACT = 48;
    static final long MAX_NODES = 3_000_000;

    public static Result solve(int cols, int rows, int[] cell, int totalMines) {
        int n = cols * rows;
        Result res = new Result();
        res.prob = new double[n];
        Arrays.fill(res.prob, Double.NaN);

        // variables = closed tiles; constraints = opened numbers
        int[] var = new int[n];
        Arrays.fill(var, -1);
        int nv = 0;
        int[] varCell = new int[n];
        for (int i = 0; i < n; i++) if (cell[i] == MineVision.UNKNOWN || cell[i] == MineVision.FLAG) { var[i] = nv; varCell[nv++] = i; }
        if (nv == 0) return res;

        List<int[]> cVars = new ArrayList<>();
        List<Integer> cNeed = new ArrayList<>();
        boolean[] frontier = new boolean[nv];
        for (int i = 0; i < n; i++) {
            int v = cell[i];
            if (v < 0 || v > 8) continue;
            int c = i % cols, r = i / cols;
            int[] tmp = new int[8];
            int k = 0;
            for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int cc = c + dc, rr = r + dr;
                if (cc < 0 || rr < 0 || cc >= cols || rr >= rows) continue;
                int j = rr * cols + cc;
                if (var[j] >= 0) tmp[k++] = var[j];
            }
            if (k == 0) { if (v != 0) res.contradiction = true; continue; }
            if (v > k) res.contradiction = true;
            cVars.add(Arrays.copyOf(tmp, k));
            cNeed.add(v);
            for (int q = 0; q < k; q++) frontier[tmp[q]] = true;
        }
        if (res.contradiction) return res;

        // connected regions of frontier tiles (joined through shared numbers)
        int[] parent = new int[nv];
        for (int i = 0; i < nv; i++) parent[i] = i;
        for (int[] cv : cVars) for (int q = 1; q < cv.length; q++) union(parent, cv[0], cv[q]);
        int[] compOf = new int[nv];
        Arrays.fill(compOf, -1);
        List<List<Integer>> comps = new ArrayList<>();
        for (int i = 0; i < nv; i++) {
            if (!frontier[i]) continue;
            int root = find(parent, i);
            int id = -1;
            for (int q = 0; q < comps.size(); q++) if (find(parent, comps.get(q).get(0)) == root) { id = q; break; }
            if (id < 0) { id = comps.size(); comps.add(new ArrayList<>()); }
            comps.get(id).add(i);
            compOf[i] = id;
        }
        int outside = 0;
        for (int i = 0; i < nv; i++) if (!frontier[i]) outside++;

        // count each region's arrangements by number of mines
        int nc = comps.size();
        double[][] ways = new double[nc][];          // ways[comp][k]
        double[][][] mineWays = new double[nc][][];  // mineWays[comp][local var][k]
        boolean[] exactComp = new boolean[nc];
        for (int ci = 0; ci < nc; ci++) {
            List<Integer> vs = comps.get(ci);
            Counter cnt = new Counter(vs, cVars, cNeed, nv);
            exactComp[ci] = vs.size() <= MAX_EXACT && cnt.run();
            if (exactComp[ci]) {
                ways[ci] = cnt.ways;
                // the counter orders tiles its own way: put them back in region order
                mineWays[ci] = new double[vs.size()][];
                for (int q = 0; q < vs.size(); q++) mineWays[ci][q] = cnt.mineWays[cnt.local[vs.get(q)]];
                double tot = 0;
                for (double w : ways[ci]) tot += w;
                if (tot == 0) { res.contradiction = true; return res; }
            } else {
                res.exact = false;
            }
        }

        // combine regions with the mine total: weight(K) = C(outside, M - K) for K mines on the frontier
        // (all in log space so big boards don't overflow)
        int M = totalMines;
        double[] logConv = {0};                  // distribution over frontier mines of exact comps
        for (int ci = 0; ci < nc; ci++) if (exactComp[ci]) logConv = convolve(logConv, logOf(ways[ci]));
        double[] probVar = new double[nv];
        Arrays.fill(probVar, Double.NaN);

        if (M >= 0) {
            int maxK = logConv.length - 1;
            double[] logW = new double[maxK + 1];
            double best = Double.NEGATIVE_INFINITY;
            for (int K = 0; K <= maxK; K++) {
                int rest = M - K;
                logW[K] = (logConv[K] == Double.NEGATIVE_INFINITY || rest < 0 || rest > outside) ? Double.NEGATIVE_INFINITY
                        : logConv[K] + logChoose(outside, rest);
                best = Math.max(best, logW[K]);
            }
            if (best == Double.NEGATIVE_INFINITY) {
                // the counter disagrees with the numbers (misread counter?) — fall back to no total
                M = -1;
            } else {
                double total = 0, outsideMines = 0;
                for (int K = 0; K <= maxK; K++) {
                    if (logW[K] == Double.NEGATIVE_INFINITY) continue;
                    double wgt = Math.exp(logW[K] - best);
                    total += wgt;
                    if (outside > 0) outsideMines += wgt * (M - K) / (double) outside;
                }
                res.logWays = best + Math.log(total);
                double pOut = outside > 0 ? outsideMines / total : 0;
                for (int i = 0; i < nv; i++) if (!frontier[i]) probVar[i] = pOut;
                for (int ci = 0; ci < nc; ci++) {
                    if (!exactComp[ci]) continue;
                    // distribution of the other exact regions
                    double[] others = {0};
                    for (int cj = 0; cj < nc; cj++) if (cj != ci && exactComp[cj]) others = convolve(others, logOf(ways[cj]));
                    List<Integer> vs = comps.get(ci);
                    for (int lv = 0; lv < vs.size(); lv++) {
                        double num = 0;
                        for (int k = 0; k < mineWays[ci][lv].length; k++) {
                            if (mineWays[ci][lv][k] == 0) continue;
                            for (int o = 0; o < others.length; o++) {
                                if (others[o] == Double.NEGATIVE_INFINITY) continue;
                                int rest = M - k - o;
                                if (rest < 0 || rest > outside) continue;
                                num += Math.exp(Math.log(mineWays[ci][lv][k]) + others[o] + logChoose(outside, rest) - best);
                            }
                        }
                        probVar[vs.get(lv)] = num / total;
                    }
                }
            }
        }
        if (M < 0) {
            // no usable total: regions independent, outside tiles at a typical density
            double dens = 0.16;
            for (int i = 0; i < nv; i++) if (!frontier[i]) probVar[i] = dens;
            res.logWays = 0;
            for (int ci = 0; ci < nc; ci++) {
                if (!exactComp[ci]) continue;
                // weight arrangements by density^k (1-d)^(n-k) so fewer-mine layouts aren't overcounted
                List<Integer> vs = comps.get(ci);
                double tot = 0;
                double[] wk = new double[ways[ci].length];
                for (int k = 0; k < wk.length; k++) { wk[k] = ways[ci][k] * Math.pow(dens / (1 - dens), k); tot += wk[k]; }
                res.logWays += Math.log(tot);
                for (int lv = 0; lv < vs.size(); lv++) {
                    double num = 0;
                    for (int k = 0; k < wk.length; k++) num += mineWays[ci][lv][k] * Math.pow(dens / (1 - dens), k);
                    probVar[vs.get(lv)] = num / tot;
                }
            }
        }
        // regions too big to count: simple local estimates, plus single-number certainties
        for (int ci = 0; ci < nc; ci++) {
            if (exactComp[ci]) continue;
            for (int v : comps.get(ci)) {
                double worst = 0;
                for (int q = 0; q < cVars.size(); q++) {
                    int[] cv = cVars.get(q);
                    for (int x : cv) if (x == v) worst = Math.max(worst, cNeed.get(q) / (double) cv.length);
                }
                probVar[v] = worst;
            }
            localLogic(comps.get(ci), cVars, cNeed, probVar);
        }

        for (int i = 0; i < nv; i++) {
            int c = varCell[i];
            double p = probVar[i];
            res.prob[c] = p;
            if (cell[c] == MineVision.FLAG) continue;            // the player's flags are never tapped
            if (p <= 1e-9) res.safe.add(c);
            else if (p >= 1 - 1e-9) res.mines.add(c);
        }
        // best guess: lowest risk; ties go to tiles with fewer closed neighbours (corners open more)
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < nv; i++) {
            int c = varCell[i];
            if (cell[c] == MineVision.FLAG) continue;
            double p = probVar[i];
            if (Double.isNaN(p)) continue;
            int closedNb = 0, cc = c % cols, rr = c / cols;
            for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int x = cc + dc, y = rr + dr;
                if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                if (var[y * cols + x] >= 0) closedNb++;
            }
            double s = p + closedNb * 1e-4 + (frontier[i] ? 0 : 5e-5);
            if (s < bestScore) { bestScore = s; res.guess = c; res.guessRisk = p; }
        }
        return res;
    }

    /** How much a guess is worth beyond surviving it; see {@link #pickGuess}. */
    public static double LOOK_ALPHA = 0.6;

    /**
     * Better forced guess: among the least risky tiles, prefer the one whose number is most likely to
     * lead straight to safe tiles. For each candidate and each number it could show, the board is
     * solved again; the number's likelihood comes from counting the layouts that fit.
     * score = P(safe) · (alpha + (1 − alpha) · P(the revealed number gives a safe tile)).
     * Stops looking at more candidates when {@code budgetMs} runs out.
     */
    public static int pickGuess(int cols, int rows, int[] cell, int totalMines, Result base, long budgetMs) {
        if (base.guess < 0) return -1;
        double minP = base.guessRisk;
        long t0 = System.nanoTime();
        // candidates: lowest risk first, within a few points of the best
        List<Integer> cand = new ArrayList<>();
        List<Double> risk = new ArrayList<>();
        int n = cols * rows;
        Integer[] order = new Integer[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (cell[i] != MineVision.UNKNOWN) continue;
            double p = base.prob[i];
            if (Double.isNaN(p) || p > minP + 0.04 || p >= 1 - 1e-9) continue;
            order[m++] = i;
        }
        Integer[] o = java.util.Arrays.copyOf(order, m);
        java.util.Arrays.sort(o, (a, b) -> Double.compare(base.prob[a], base.prob[b]));
        // many tiles far from the numbers share one risk: try only a few of them (corners/edges first)
        int outsideTaken = 0;
        for (int i : o) {
            boolean frontier = false;
            int c = i % cols, r = i / cols, closed = 0;
            for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int x = c + dc, y = r + dr;
                if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                int v = cell[y * cols + x];
                if (v >= 0 && v <= 8) frontier = true;
                if (v == MineVision.UNKNOWN || v == MineVision.FLAG) closed++;
            }
            if (!frontier) {
                if (closed > 5 || outsideTaken >= 3) continue;
                outsideTaken++;
            }
            cand.add(i);
            risk.add(base.prob[i]);
            if (cand.size() >= 10) break;
        }
        if (!cand.contains(base.guess)) { cand.add(0, base.guess); risk.add(0, base.guessRisk); }
        double bestScore = -1;
        int best = base.guess;
        int[] work = cell.clone();
        for (int k = 0; k < cand.size(); k++) {
            if (k > 0 && (System.nanoTime() - t0) / 1_000_000 > budgetMs) break;
            int c = cand.get(k);
            double pSafe = 1 - risk.get(k);
            double wSum = 0, wProg = 0;
            for (int v = 0; v <= 8; v++) {
                work[c] = v;
                Result r = solve(cols, rows, work, totalMines);
                if (r.contradiction || r.logWays == Double.NEGATIVE_INFINITY) continue;
                double w = Math.exp(r.logWays - base.logWays);
                if (!(w > 0) || Double.isInfinite(w)) { if (Double.isInfinite(w)) w = 1e300; else continue; }
                wSum += w;
                if (!r.safe.isEmpty()) wProg += w;
            }
            work[c] = cell[c];
            double pProg = wSum > 0 ? wProg / wSum : 0;
            double score = pSafe * (LOOK_ALPHA + (1 - LOOK_ALPHA) * pProg);
            if (score > bestScore + 1e-12) { bestScore = score; best = c; }
        }
        return best;
    }

    /** Single-number and two-number (subset) rules, for regions too big to count. */
    static void localLogic(List<Integer> vs, List<int[]> cVars, List<Integer> cNeed, double[] probVar) {
        java.util.HashSet<Integer> in = new java.util.HashSet<>(vs);
        int[] known = new int[probVar.length];   // 0 unknown, 1 safe, 2 mine
        boolean progress = true;
        int guard = 0;
        while (progress && guard++ < 50) {
            progress = false;
            for (int q = 0; q < cVars.size(); q++) {
                int[] cv = cVars.get(q);
                if (cv.length == 0 || !in.contains(cv[0])) continue;
                int need = cNeed.get(q), open = 0;
                for (int x : cv) { if (known[x] == 2) need--; else if (known[x] == 0) open++; }
                if (open == 0) continue;
                if (need == 0) { for (int x : cv) if (known[x] == 0) { known[x] = 1; progress = true; } }
                else if (need == open) { for (int x : cv) if (known[x] == 0) { known[x] = 2; progress = true; } }
            }
            // subset rule: A ⊂ B  →  B \ A holds need(B) - need(A) mines
            for (int a = 0; a < cVars.size(); a++) {
                int[] A = cVars.get(a);
                if (A.length == 0 || !in.contains(A[0])) continue;
                for (int b = 0; b < cVars.size(); b++) {
                    if (a == b) continue;
                    int[] B = cVars.get(b);
                    if (B.length <= A.length || !in.contains(B[0])) continue;
                    if (!subset(A, B, known)) continue;
                    int needA = cNeed.get(a), needB = cNeed.get(b), restOpen = 0;
                    for (int x : A) if (known[x] == 2) needA--;
                    for (int x : B) if (known[x] == 2) needB--;
                    for (int x : B) if (known[x] == 0 && !contains(A, x)) restOpen++;
                    if (restOpen == 0) continue;
                    int diff = needB - needA;
                    if (diff == 0) { for (int x : B) if (known[x] == 0 && !contains(A, x)) { known[x] = 1; progress = true; } }
                    else if (diff == restOpen) { for (int x : B) if (known[x] == 0 && !contains(A, x)) { known[x] = 2; progress = true; } }
                }
            }
        }
        for (int v : vs) {
            if (known[v] == 1) probVar[v] = 0;
            else if (known[v] == 2) probVar[v] = 1;
            else probVar[v] = Math.min(0.999, Math.max(1e-3, probVar[v]));
        }
    }

    static boolean subset(int[] A, int[] B, int[] known) {
        for (int x : A) if (known[x] == 0 && !contains(B, x)) return false;
        return true;
    }

    static boolean contains(int[] a, int x) { for (int y : a) if (y == x) return true; return false; }

    /** Backtracking count of all mine arrangements in one region. */
    static final class Counter {
        final int[] vars;          // global var ids
        final int[] local;         // global -> local index
        final int[][] cons;        // constraint -> local vars
        final int[] need;
        final int[][] consOf;      // local var -> constraints
        final byte[] val;
        final int[] placed, open;  // per constraint
        double[] ways;
        double[][] mineWays;
        long nodes;

        Counter(List<Integer> vs, List<int[]> cVars, List<Integer> cNeed, int nv) {
            int n = vs.size();
            vars = new int[n];
            local = new int[nv];
            Arrays.fill(local, -1);
            // order variables so neighbours come together (BFS through constraints)
            List<Integer> order = new ArrayList<>();
            boolean[] seen = new boolean[nv];
            java.util.ArrayDeque<Integer> dq = new java.util.ArrayDeque<>();
            dq.add(vs.get(0)); seen[vs.get(0)] = true;
            java.util.HashSet<Integer> inComp = new java.util.HashSet<>(vs);
            while (!dq.isEmpty()) {
                int v = dq.poll();
                order.add(v);
                for (int[] cv : cVars) {
                    if (!contains(cv, v)) continue;
                    for (int x : cv) if (!seen[x] && inComp.contains(x)) { seen[x] = true; dq.add(x); }
                }
            }
            for (int i = 0; i < n; i++) { vars[i] = order.get(i); local[vars[i]] = i; }
            List<int[]> cs = new ArrayList<>();
            List<Integer> nd = new ArrayList<>();
            for (int q = 0; q < cVars.size(); q++) {
                int[] cv = cVars.get(q);
                if (cv.length == 0 || local[cv[0]] < 0) continue;
                int[] l = new int[cv.length];
                for (int k = 0; k < cv.length; k++) l[k] = local[cv[k]];
                cs.add(l);
                nd.add(cNeed.get(q));
            }
            cons = cs.toArray(new int[0][]);
            need = new int[nd.size()];
            for (int q = 0; q < need.length; q++) need[q] = nd.get(q);
            List<List<Integer>> co = new ArrayList<>();
            for (int i = 0; i < n; i++) co.add(new ArrayList<>());
            for (int q = 0; q < cons.length; q++) for (int x : cons[q]) co.get(x).add(q);
            consOf = new int[n][];
            for (int i = 0; i < n; i++) { consOf[i] = new int[co.get(i).size()]; for (int k = 0; k < consOf[i].length; k++) consOf[i][k] = co.get(i).get(k); }
            val = new byte[n];
            placed = new int[cons.length];
            open = new int[cons.length];
            for (int q = 0; q < cons.length; q++) open[q] = cons[q].length;
            ways = new double[n + 1];
            mineWays = new double[n][n + 1];
        }

        boolean run() {
            rec(0, 0);
            return nodes <= MAX_NODES;
        }

        private void rec(int i, int mines) {
            if (++nodes > MAX_NODES) return;
            if (i == vars.length) {
                ways[mines] += 1;
                for (int k = 0; k < vars.length; k++) if (val[k] == 1) mineWays[k][mines] += 1;
                return;
            }
            for (int v = 0; v <= 1; v++) {
                boolean ok = true;
                for (int q : consOf[i]) {
                    int p = placed[q] + v, o = open[q] - 1;
                    if (p > need[q] || p + o < need[q]) { ok = false; break; }
                }
                if (!ok) continue;
                for (int q : consOf[i]) { placed[q] += v; open[q]--; }
                val[i] = (byte) v;
                rec(i + 1, mines + v);
                for (int q : consOf[i]) { placed[q] -= v; open[q]++; }
                val[i] = 0;
                if (nodes > MAX_NODES) return;
            }
        }
    }

    // ---------------------------------------------------------------- maths

    static double[] logOf(double[] a) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] > 0 ? Math.log(a[i]) : Double.NEGATIVE_INFINITY;
        return r;
    }

    static double[] convolve(double[] la, double[] lb) {
        double[] r = new double[la.length + lb.length - 1];
        Arrays.fill(r, Double.NEGATIVE_INFINITY);
        for (int i = 0; i < la.length; i++) {
            if (la[i] == Double.NEGATIVE_INFINITY) continue;
            for (int j = 0; j < lb.length; j++) {
                if (lb[j] == Double.NEGATIVE_INFINITY) continue;
                r[i + j] = logAdd(r[i + j], la[i] + lb[j]);
            }
        }
        return r;
    }

    static double logAdd(double a, double b) {
        if (a == Double.NEGATIVE_INFINITY) return b;
        if (b == Double.NEGATIVE_INFINITY) return a;
        double m = Math.max(a, b);
        return m + Math.log(Math.exp(a - m) + Math.exp(b - m));
    }

    private static double[] lf = new double[1];

    static synchronized double logFact(int n) {
        if (n >= lf.length) {
            double[] nl = new double[Math.max(n + 1, lf.length * 2)];
            System.arraycopy(lf, 0, nl, 0, lf.length);
            for (int i = Math.max(1, lf.length); i < nl.length; i++) nl[i] = nl[i - 1] + Math.log(i);
            lf = nl;
        }
        return lf[n];
    }

    static double logChoose(int n, int k) {
        if (k < 0 || k > n) return Double.NEGATIVE_INFINITY;
        return logFact(n) - logFact(k) - logFact(n - k);
    }

    private static int find(int[] p, int x) { while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; } return x; }
    private static void union(int[] p, int a, int b) { a = find(p, a); b = find(p, b); if (a != b) p[a] = b; }
}
