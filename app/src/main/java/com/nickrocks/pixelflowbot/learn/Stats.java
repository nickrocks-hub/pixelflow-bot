package com.nickrocks.pixelflowbot.learn;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;

/** Running results for one mode: episodes, wins, rewards, mistakes, recent win rate. */
public final class Stats {
    public long episodes, wins, steps, mistakes;
    public double totalReward;
    public float lastEpisodeReward;
    private final ArrayDeque<Boolean> recent = new ArrayDeque<>();
    private static final int WINDOW = 50;

    public void step(float reward) {
        steps++;
        if (reward < 0) mistakes++;
        totalReward += reward;
    }

    public void episode(boolean win, float episodeReward) {
        episodes++;
        if (win) wins++;
        lastEpisodeReward = episodeReward;
        recent.addLast(win);
        while (recent.size() > WINDOW) recent.removeFirst();
    }

    public float winRate() { return episodes == 0 ? 0 : wins / (float) episodes; }

    public float recentWinRate() {
        if (recent.isEmpty()) return 0;
        int w = 0;
        for (boolean b : recent) if (b) w++;
        return w / (float) recent.size();
    }

    public float mistakesPerEpisode() { return episodes == 0 ? 0 : mistakes / (float) episodes; }

    public String line() {
        return String.format(Locale.US, "%d episodes · win %.0f%% (last %d: %.0f%%) · %.2f mistakes/episode · avg reward %.1f",
                episodes, 100 * winRate(), Math.min(WINDOW, recent.size()), 100 * recentWinRate(),
                mistakesPerEpisode(), episodes == 0 ? 0 : totalReward / episodes);
    }

    public String toJson() {
        return "{\"episodes\":" + episodes + ",\"wins\":" + wins + ",\"steps\":" + steps + ",\"mistakes\":" + mistakes
                + ",\"totalReward\":" + totalReward + "}";
    }

    public static Stats fromJson(Map<String, Object> m) {
        Stats s = new Stats();
        if (m == null) return s;
        s.episodes = (long) Json.num(m, "episodes", 0);
        s.wins = (long) Json.num(m, "wins", 0);
        s.steps = (long) Json.num(m, "steps", 0);
        s.mistakes = (long) Json.num(m, "mistakes", 0);
        s.totalReward = Json.num(m, "totalReward", 0);
        return s;
    }
}
