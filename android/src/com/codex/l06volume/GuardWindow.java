package com.codex.l06volume;

/** A route transition starts one bounded correction window. Duplicate hints do not extend it. */
final class GuardWindow {
    static final long DURATION_MS = 15000;
    private boolean active;
    private long deadline;

    boolean updateRoute(boolean l06Active, long now) {
        boolean started = l06Active && !active;
        if (started) deadline = now + DURATION_MS;
        if (!l06Active) deadline = 0;
        active = l06Active;
        return started;
    }

    boolean isActive() { return active; }

    boolean shouldApply(long now) {
        return active && now < deadline;
    }

    void disconnect() {
        active = false;
        deadline = 0;
    }
}
