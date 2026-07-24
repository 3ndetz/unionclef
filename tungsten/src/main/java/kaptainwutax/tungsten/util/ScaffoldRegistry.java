package kaptainwutax.tungsten.util;

import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

/**
 * Records the blocks tungsten NAVIGATION places as scaffolding — pillar-up blocks (to reach
 * a high goal) and bridge floor (to cross a gap). These are "garbage" around a finished
 * build/route; a cleanup pass mines exactly these back out. The registry is a FINITE set
 * mined once each, so cleanup can never loop (no re-placing happens during cleanup).
 *
 * NOT recorded: buildBlocks / worldedit placements (those are the intended build, kept).
 */
public final class ScaffoldRegistry {

    private static final Set<BlockPos> placed = new LinkedHashSet<>();

    private ScaffoldRegistry() {}

    public static synchronized void record(BlockPos p) {
        if (p != null) placed.add(p.toImmutable());
    }

    /** Snapshot of scaffold blocks, HIGHEST Y first (mine top-down so removing a support
     *  doesn't strand the bot above the rest). */
    public static synchronized List<BlockPos> snapshotTopDown() {
        List<BlockPos> out = new ArrayList<>(placed);
        out.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        return out;
    }

    public static synchronized int size() { return placed.size(); }

    public static synchronized void clear() { placed.clear(); }

    public static synchronized void remove(BlockPos p) { if (p != null) placed.remove(p.toImmutable()); }
}
