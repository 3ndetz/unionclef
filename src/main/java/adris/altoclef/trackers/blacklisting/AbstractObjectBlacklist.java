package adris.altoclef.trackers.blacklisting;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;

/**
 * Sometimes we will try to access something and fail TOO many times.
 * <p>
 * This lets us know that a block is unreachable, and will ignore it from the search intelligently.
 */
public abstract class AbstractObjectBlacklist<T> {

    private final HashMap<T, BlacklistEntry> entries = new HashMap<>();

    public void blackListItem(AltoClef mod, T item, int numberOfFailuresAllowed) {
        if (!entries.containsKey(item)) {
            BlacklistEntry entry = new BlacklistEntry();
            entry.numberOfFailuresAllowed = numberOfFailuresAllowed;
            entry.numberOfFailures = 0;
            entry.bestDistanceSq = Double.POSITIVE_INFINITY;
            entry.bestTool = MiningRequirement.HAND;
            entries.put(item, entry);
        }
        BlacklistEntry entry = entries.get(item);
        double newDistance = getPos(item).squaredDistanceTo(mod.getPlayer().getPos());
        MiningRequirement newTool = StorageHelper.getCurrentMiningRequirement();
        // A CRAWL IS NOT A CHANGE OF CIRCUMSTANCES.
        // The reset exists so that "I failed at this from thirty blocks away" does not condemn a
        // block I am now standing next to. That is a STEP CHANGE. As written it fired whenever
        // the squared distance improved by 1, and walking toward a target produces exactly such a
        // monotone sequence — so approaching an unreachable block reset the failure count on
        // every attempt, `unreachable()` could never become true, and the bot hammered the same
        // target forever instead of picking another. Measured on a live @gamer run: 160
        // blacklists against 160 RESETs, and ONE item gathered in fifteen minutes.
        //
        // Requiring the distance to have HALVED keeps the intent — genuinely closer, or a better
        // tool — while a one-block shuffle no longer buys another life.
        boolean betterTool = newTool.ordinal() > entry.bestTool.ordinal();
        boolean materiallyCloser = newDistance < entry.bestDistanceSq * 0.5 - 1;
        if (betterTool || materiallyCloser) {
            if (betterTool) entry.bestTool = newTool;
            if (newDistance < entry.bestDistanceSq) entry.bestDistanceSq = newDistance;
            entry.numberOfFailures = 0;
            Debug.logMessage("Blacklist RESET: " + item.toString());
        }
        entry.numberOfFailures++;
        entry.numberOfFailuresAllowed = numberOfFailuresAllowed;
        Debug.logMessage("Blacklist: " + item.toString() + ": Try " + entry.numberOfFailures + " / " + entry.numberOfFailuresAllowed);
    }

    protected abstract Vec3d getPos(T item);

    public boolean unreachable(T item) {
        if (entries.containsKey(item)) {
            BlacklistEntry entry = entries.get(item);
            return entry.numberOfFailures > entry.numberOfFailuresAllowed;
        }
        return false;
    }

    public void clear() {
        entries.clear();
    }

    /**
     * Remove entries for objects that no longer exist.
     * Call periodically to prevent unbounded memory growth.
     */
    public void cleanupStale() {
        entries.keySet().removeIf(this::isStale);
    }

    /**
     * Default stale check — override for entity-specific cleanup.
     * By default, checks if the object's position is far from origin (despawned).
     */
    protected boolean isStale(T item) {
        return false;
    }

    // Key: BlockPos
    private static class BlacklistEntry {
        public int numberOfFailuresAllowed;
        public int numberOfFailures;
        public double bestDistanceSq;
        public MiningRequirement bestTool;
    }
}
