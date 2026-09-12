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
            Debug.logMessage("Circumstances changed, attempts restored: " + item.toString());
        }
        entry.numberOfFailures++;
        entry.totalFailures++;
        entry.numberOfFailuresAllowed = numberOfFailuresAllowed;
        entry.lastFailureMs = System.currentTimeMillis();
        // ⛔ ZERO ATTEMPTS ALLOWED IS NOT A FAILURE, IT IS A DECISION (G63b, 2026-09-12). The
        // speedrun brain marks blocks it does not WANT with allowedFailures=0 -- "extra furnace"
        // when it carries its own, "dangerous log" near a pillager, a witch's crafting table,
        // ancient-city wool. The first G63 build gave those a cool-off and then, with nothing else
        // in the scan, HANDED THEM BACK: the 19:38 run stood seven minutes at a smoker with
        // banLifted=8918, "Blacklisting extra furnace" x80 re-issued every 45 s, and the container
        // task alternating "walk=INF" against a furnace eleven hundred blocks away. A decision does
        // not go stale and is not a price; it stands until the brain clears it.
        entry.deliberate = numberOfFailuresAllowed == 0;
        if (entry.deliberate) {
            Debug.logMessage("Excluded on purpose: " + item.toString());
        } else {
            Debug.logMessage("Costing " + item.toString() + ": attempt " + entry.numberOfFailures
                    + " / " + entry.numberOfFailuresAllowed + " — stepping aside for "
                    + (COOL_OFF_MS / 1000) + "s");
        }
    }

    /** True for an exclusion the brain made on purpose (allowedFailures == 0): never rested,
     *  never handed back as a last resort, never priced -- see the note in blackListItem. */
    public boolean excludedDeliberately(T item) {
        BlacklistEntry entry = entries.get(item);
        return entry != null && entry.deliberate;
    }

    protected abstract Vec3d getPos(T item);

    /**
     * ⛔ A TARGET IS NEVER CONDEMNED FOR GOOD (G63, 2026-09-12).
     *
     * <p>This used to answer "unreachable" from the failure count alone, with no clock at all: once
     * a pig, a log or a chest had failed three times it was gone until something called
     * {@link #clear()}. The operator's standing complaint — "failed to get target, blacklisting.
     * There must be NO such cases at all" — is this method. Worse, the callers FILTER by it, so a
     * world whose nearby candidates had all failed once produced an empty candidate list, which
     * reads downstream as "nothing to do" and comes out as the wander and the "Failed exploring"
     * on the recordings: the bot standing in a forest that it had decided did not exist.
     *
     * <p>A failure is evidence about NOW — a mob behind a fence, a log across a ravine, a body that
     * has not found its way yet — and it goes stale. So the verdict lasts {@link #COOL_OFF_MS} from
     * the last failure and then the target is offered again; a target that is genuinely hopeless
     * fails again at once and steps aside again, which costs one attempt rather than the rest of
     * the run. The count still rises, and {@link #penaltyBlocks} exposes it so a chooser can prefer
     * the target that has NOT been fighting it, which is what "blacklisting" was reaching for.
     */
    public boolean unreachable(T item) {
        BlacklistEntry entry = entries.get(item);
        if (entry == null) return false;
        if (entry.deliberate) return true;            // a decision, not evidence: no cool-off
        if (entry.numberOfFailures <= entry.numberOfFailuresAllowed) return false;
        if (System.currentTimeMillis() - entry.lastFailureMs > COOL_OFF_MS) {
            // the cool-off is over: it gets its attempts back, and the history stays as a price
            entry.numberOfFailures = 0;
            blacklistExpired++;
            return false;
        }
        return true;
    }

    /**
     * What this target has cost so far, in blocks, for a chooser that ranks by distance. Not a
     * veto: a target that has failed twice is worth passing over for one three blocks further
     * away, and worth walking to when it is the only thing in the world.
     */
    public double penaltyBlocks(T item) {
        BlacklistEntry entry = entries.get(item);
        if (entry == null) return 0;
        long idle = System.currentTimeMillis() - entry.lastFailureMs;
        if (idle > COOL_OFF_MS * 4) return 0;               // long forgotten
        double fade = idle > COOL_OFF_MS ? 0.25 : 1.0;      // stale evidence is worth less
        // totalFailures, not numberOfFailures: the cool-off hands the ATTEMPTS back, and a price
        // that reset with them would let a hopeless target look as cheap as a fresh one for ever.
        return entry.totalFailures * PENALTY_PER_FAILURE * fade;
    }

    /** How long a run of failures keeps a target out of the running, and what each one prices. */
    private static final long COOL_OFF_MS = 45_000L;
    private static final double PENALTY_PER_FAILURE = 16.0;
    /** Verdicts that timed out and handed the target its attempts back. Read as banExpired. */
    public static volatile int blacklistExpired;

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
        /** When the last failure happened — the verdict is dated, not permanent (G63). */
        public long lastFailureMs;
        /** Every failure ever, kept across cool-offs so the PRICE remembers what the verdict forgets. */
        public int totalFailures;
        /** Marked with zero attempts allowed: the brain's own exclusion, not a failed approach (G63b). */
        public boolean deliberate;
    }
}
