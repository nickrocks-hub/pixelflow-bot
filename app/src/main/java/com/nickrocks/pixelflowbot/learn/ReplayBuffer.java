package com.nickrocks.pixelflowbot.learn;

import java.util.Random;

/** Fixed-size ring buffer of past transitions, sampled uniformly for replay updates. */
public final class ReplayBuffer {
    private final Transition[] buf;
    private int next, size;

    public ReplayBuffer(int capacity) { buf = new Transition[capacity]; }

    public synchronized void add(Transition t) {
        buf[next] = t;
        next = (next + 1) % buf.length;
        if (size < buf.length) size++;
    }

    public synchronized Transition sample(Random rnd) { return buf[rnd.nextInt(size)]; }

    public synchronized int size() { return size; }

    public synchronized void clear() {
        java.util.Arrays.fill(buf, null);
        next = size = 0;
    }
}
