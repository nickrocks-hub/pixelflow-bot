package com.nickrocks.aibot.core;

import java.util.Map;

/** What the agent reports into: the run's log, counters, memory and in-game clock. */
public interface RunContext {
    void log(int level, String type, String msg, Map<String, Object> data);
    RunStats stats();
    Learner learner();
    long gameTimeMs();
}
