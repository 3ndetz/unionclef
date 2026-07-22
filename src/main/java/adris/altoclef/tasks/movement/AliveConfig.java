package adris.altoclef.tasks.movement;

import java.util.ArrayList;
import java.util.List;

/**
 * Live, agent-controllable config for {@link AliveTask} — the "act like a real player" behavior.
 *
 * The AGENT owns the GOALS here (via the @alive command) and can change them at any time WITHOUT
 * restarting the task; AliveTask reads this every tick and behaves accordingly. This is the
 * orchestration the operator asked for: the agent sets targets/mode, then just plays + talks, and
 * AliveTask carries out the in-game presence (look at / hang near specific nicks, roam, fidget) and
 * emits EVENTS back (target lost, stuck) that reach the agent's unified event loop. The task is a
 * mod-side script (mechanical); the THINKING — what to target, when to preempt — stays with the LLM.
 */
public final class AliveConfig {
    public enum Mode { IDLE, WATCH, NEAR, ROAM }

    private static volatile Mode mode = Mode.IDLE;
    private static final List<String> targets = new ArrayList<>();
    private static volatile double radius = 4.0;

    private AliveConfig() {}

    public static synchronized void set(Mode m, List<String> t, double r) {
        if (m != null) mode = m;
        if (r > 0) radius = r;
        targets.clear();
        if (t != null) for (String s : t) if (s != null && !s.isBlank()) targets.add(s.trim());
    }

    public static synchronized Mode mode() { return mode; }
    public static synchronized double radius() { return radius; }
    public static synchronized List<String> targets() { return new ArrayList<>(targets); }

    public static synchronized String describe() {
        return "mode=" + mode + " targets=" + targets + " radius=" + radius;
    }
}
