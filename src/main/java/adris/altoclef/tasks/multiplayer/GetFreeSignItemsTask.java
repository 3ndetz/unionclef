package adris.altoclef.tasks.multiplayer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.time.TimerGame;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Collects items from [Free] sign shops.
 *
 * <p>Scans loaded chunks for [Free] signs whose item-ID line matches a
 * wanted entry.  When nothing is found for {@link #IDLE_BEFORE_EXPLORE_SECS}
 * seconds the bot launches a long wander to load new chunks.  The task
 * finishes once all {@link ItemTarget} goals are satisfied.
 *
 * <p>Usage example (leather armour):
 * <pre>
 *   Map&lt;String, ItemTarget&gt; wants = new LinkedHashMap&lt;&gt;();
 *   wants.put("298", new ItemTarget(Items.LEATHER_HELMET,     1));
 *   wants.put("299", new ItemTarget(Items.LEATHER_CHESTPLATE, 1));
 *   wants.put("300", new ItemTarget(Items.LEATHER_LEGGINGS,   1));
 *   wants.put("301", new ItemTarget(Items.LEATHER_BOOTS,      1));
 *   mod.runUserTask(new GetFreeSignItemsTask(wants), t -> {});
 * </pre>
 *
 * <p>Keys are the raw strings from sign line 2 (whatever the server plugin
 * writes there — numeric IDs, namespaced IDs, etc.).
 */
public class GetFreeSignItemsTask extends Task {

    /** Seconds without any visible sign before launching exploration. */
    private static final int IDLE_BEFORE_EXPLORE_SECS = 5;
    /** Distance (blocks) to wander during exploration before returning to search. */
    private static final float EXPLORE_DISTANCE = 200;

    /** signItemId → what Minecraft item + count to expect. */
    private final Map<String, ItemTarget> wantedItems;

    private final TimerGame idleTimer = new TimerGame(IDLE_BEFORE_EXPLORE_SECS);

    private enum Phase { SEARCHING, SHOPPING, EXPLORING }
    private Phase phase = Phase.SEARCHING;

    private SignShopTask activeShopTask;
    private TimeoutWanderTask activeExploreTask;

    public GetFreeSignItemsTask(Map<String, ItemTarget> wantedItems) {
        this.wantedItems = new LinkedHashMap<>(wantedItems);
    }

    @Override
    protected void onStart() {
        phase = Phase.SEARCHING;
        activeShopTask = null;
        activeExploreTask = null;
        idleTimer.reset();
    }

    @Override
    public boolean isFinished() {
        AltoClef mod = AltoClef.getInstance();
        for (ItemTarget target : wantedItems.values()) {
            if (!StorageHelper.itemTargetsMet(mod, target)) return false;
        }
        return true;
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // ── SHOPPING ────────────────────────────────────────────────────────
        if (phase == Phase.SHOPPING && activeShopTask != null) {
            if (!activeShopTask.isFinished()) {
                return activeShopTask;
            }
            // Shop task done (either success or blacklisted) — back to search
            activeShopTask = null;
            phase = Phase.SEARCHING;
            idleTimer.reset();
        }

        // ── EXPLORING ───────────────────────────────────────────────────────
        if (phase == Phase.EXPLORING && activeExploreTask != null) {
            if (!activeExploreTask.isFinished()) {
                return activeExploreTask;
            }
            // Exploration finished — search again for a while before re-exploring
            activeExploreTask = null;
            idleTimer.reset();
            phase = Phase.SEARCHING;
        }

        // ── SEARCHING ───────────────────────────────────────────────────────
        Optional<BlockPos> bestSign = findBestSign(mod);

        if (bestSign.isPresent()) {
            idleTimer.reset();
            activeShopTask = new SignShopTask(bestSign.get());
            phase = Phase.SHOPPING;
            setDebugState("Going to sign at " + bestSign.get().toShortString());
            return activeShopTask;
        }

        if (!idleTimer.elapsed()) {
            setDebugState("Searching for sign shops...");
            return null;
        }

        // Nothing found long enough — run big exploration
        Debug.logMessage("[SignShopGet] No signs in loaded chunks, exploring...");
        activeExploreTask = new TimeoutWanderTask(EXPLORE_DISTANCE, true);
        phase = Phase.EXPLORING;
        idleTimer.reset(); // avoid re-triggering immediately after exploration
        setDebugState("Exploring for sign shops...");
        return activeExploreTask;
    }

    @Override
    protected void onStop(Task interruptTask) {}

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof GetFreeSignItemsTask task) {
            return task.wantedItems.equals(wantedItems);
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "GetFreeSignItems";
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Returns the nearest sign pos among all unmet item targets, or empty. */
    private Optional<BlockPos> findBestSign(AltoClef mod) {
        Vec3d playerPos = mod.getPlayer().getPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (Map.Entry<String, ItemTarget> entry : wantedItems.entrySet()) {
            if (StorageHelper.itemTargetsMet(mod, entry.getValue())) continue;

            Optional<BlockPos> sign = SignShopTask.findNearestFreeSign(mod, entry.getKey());
            if (sign.isPresent()) {
                double dist = sign.get().toCenterPos().squaredDistanceTo(playerPos);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = sign.get();
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
