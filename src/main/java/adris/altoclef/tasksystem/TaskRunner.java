package adris.altoclef.tasksystem;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.chains.GameMenuTaskChain;

import java.util.ArrayList;

public class TaskRunner {

    private final ArrayList<TaskChain> chains = new ArrayList<>();
    /**
     * The priority each chain bid on the last tick, by name.
     *
     * <p>Recorded rather than recomputed on demand: {@link TaskChain#getPriority()} is not a getter.
     * It scans the world and updates chain state, so asking it from the py4j thread both raced the
     * render thread (an IllegalStateException, observed) and let a diagnostic READ change what the
     * bot does. An instrument must not move the thing it measures.
     */
    private final java.util.LinkedHashMap<String, Float> lastPriority = new java.util.LinkedHashMap<>();
    private final AltoClef mod;
    private boolean active;

    private TaskChain cachedCurrentTaskChain = null;
    public GameMenuTaskChain gameMenuTaskChain = null;

    public String statusReport = " (no chain running) ";

    public TaskRunner(AltoClef mod) {
        this.mod = mod;
        active = false;
    }

    public void tick() {
        if (!active) {
            statusReport = " (no chain running) ";
            return;
        }
        if (!AltoClef.inGame()) {
            // Run menu chain even when not in game (handles reconnects, death screen, etc.)
            if (gameMenuTaskChain != null) {
                gameMenuTaskChain.getPriority();
                gameMenuTaskChain.tick();
            }
            statusReport = " (no chain running) ";
            return;
        }

        // Get highest priority chain and run
        TaskChain maxChain = null;
        float maxPriority = Float.NEGATIVE_INFINITY;
        for (TaskChain chain : chains) {
            if (!chain.isActive()) continue;
            float priority = chain.getPriority();
            lastPriority.put(chain.getName(), priority);
            if (priority > maxPriority) {
                maxPriority = priority;
                maxChain = chain;
            }
        }
        if (cachedCurrentTaskChain != null && maxChain != cachedCurrentTaskChain) {
            cachedCurrentTaskChain.onInterrupt(maxChain);
        }
        cachedCurrentTaskChain = maxChain;
        if (maxChain != null) {
            statusReport = "Chain: " + maxChain.getName() + ", priority: " + maxPriority;
            maxChain.tick();
        } else {
            statusReport = " (no chain running) ";
        }
    }

    public void addTaskChain(TaskChain chain) {
        if (chain instanceof GameMenuTaskChain menuChain) {
            gameMenuTaskChain = menuChain;
        }
        chains.add(chain);
    }

    public void enable() {
        if (!active) {
            mod.getBehaviour().push();
            mod.getBehaviour().setPauseOnLostFocus(false);
        }
        active = true;
    }

    public void disable() {
        if (active) {
            mod.getBehaviour().pop();
            Debug.logMessage("Stopped");
        }
        for (TaskChain chain : chains) {
            chain.stop();
        }
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Every chain with its live priority, for diagnosis.
     *
     * <p>When the bot stands still and the task readout says "No tasks", two very different things
     * look identical from outside: the runner is switched off, or it is on and every chain declined
     * the tick. Only the per-chain priorities tell them apart, and they were previously visible
     * nowhere but the in-game overlay.
     */
    public String describeChains() {
        StringBuilder sb = new StringBuilder("active=").append(active).append(" | ").append(statusReport).append(" | ");
        for (TaskChain chain : chains) {
            sb.append(chain.getName()).append("(on=").append(chain.isActive())
                    .append(",p=").append(lastPriority.get(chain.getName())).append(") ");
        }
        return sb.toString();
    }

    public TaskChain getCurrentTaskChain() {
        return cachedCurrentTaskChain;
    }

    // Kinda jank ngl
    public AltoClef getMod() {
        return mod;
    }
}
