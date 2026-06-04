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
            entry.createdAt = System.currentTimeMillis();
            entries.put(item, entry);
        }
        BlacklistEntry entry = entries.get(item);
        double newDistance = getPos(item).squaredDistanceTo(mod.getPlayer().getPos());
        MiningRequirement newTool = StorageHelper.getCurrentMiningRequirement();
        // For distance, add a slight threshold so it doesn't reset EVERY time we move a tiny bit closer.
        if (newTool.ordinal() > entry.bestTool.ordinal() || (newDistance < entry.bestDistanceSq - 1)) {
            if (newTool.ordinal() > entry.bestTool.ordinal()) entry.bestTool = newTool;
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
     * By default, expires entries older than MAX_ENTRY_AGE_MS.
     */
    protected boolean isStale(T item) {
        BlacklistEntry entry = entries.get(item);
        if (entry != null) {
            return System.currentTimeMillis() - entry.createdAt > MAX_ENTRY_AGE_MS;
        }
        return false;
    }

    // Key: BlockPos
    private static class BlacklistEntry {
        public int numberOfFailuresAllowed;
        public int numberOfFailures;
        public double bestDistanceSq;
        public MiningRequirement bestTool;
        public long createdAt;
    }

    /**
     * Maximum lifetime of a blacklist entry before it is considered stale (60 seconds).
     * Prevents unbounded growth over long sessions while keeping entries long enough
     * to avoid re-trying truly unreachable targets.
     */
    private static final long MAX_ENTRY_AGE_MS = 60000;
}
