package kaptainwutax.tungsten.path;

import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenModDataContainer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;

/**
 * Central "may we place a block here" policy — the placing counterpart of
 * BreakRules. Consulted by every placing primitive (placeBlockAt, fillSelection,
 * BridgeTask, future schematic builder) and the py4j/MCP prediction API, so the
 * mod honours protected areas / claims exactly like baritone.
 *
 * Deny reasons, in order: placing disabled, target not replaceable, configured
 * deny zone, the external hook (altoclef's place-avoiders / protected zones via
 * canPlaceHook).
 */
public final class PlaceRules {

    private PlaceRules() {}

    /** May we place a block at pos? (Does not check reach or having a block —
     *  that is the caller's concern; this is purely the protection policy.) */
    public static boolean canPlace(WorldView world, BlockPos pos) {
        TungstenConfig cfg = TungstenConfig.get();
        if (!cfg.allowPlace) return false;
        if (refusedRecently(pos)) return false;
        // must be an empty/replaceable cell to place into
        if (world != null && !world.getBlockState(pos).isReplaceable()) return false;
        // BARITONE-PORT.md, off-thread-world-access section: baritone checks the world border
        // on every placement (CalculationContext.java:193); tungsten's break side already gets
        // this for free through MovementHelperB.avoidBreaking, the place side never checked it
        // at all. Reuses the same ported BetterWorldBorder.canPlaceAt body, not a new copy.
        if (world != null
                && !kaptainwutax.tungsten.path.movements.MovementHelperB
                        .worldBorderCanPlaceAt(world, pos.getX(), pos.getZ())) {
            return false;
        }

        for (int[] zone : cfg.placeDenyZones) {
            if (zone != null && zone.length >= 6 && inZone(pos, zone)) return false;
        }

        java.util.function.Predicate<BlockPos> hook = TungstenModDataContainer.canPlaceHook;
        if (hook != null) {
            try {
                if (!hook.test(pos)) return false;
            } catch (Throwable ignored) {
                // protection hook failure must not lock or unlock building
            }
        }
        return true;
    }

    /** Protection-only check (no world/replaceable test) — for API predictions
     *  where the caller only wants to know if the zone/claim allows building. */
    public static boolean allowedByPolicy(BlockPos pos) {
        TungstenConfig cfg = TungstenConfig.get();
        if (!cfg.allowPlace) return false;
        for (int[] zone : cfg.placeDenyZones) {
            if (zone != null && zone.length >= 6 && inZone(pos, zone)) return false;
        }
        java.util.function.Predicate<BlockPos> hook = TungstenModDataContainer.canPlaceHook;
        if (hook != null) {
            try { if (!hook.test(pos)) return false; } catch (Throwable ignored) {}
        }
        return true;
    }

    // ── G67: a placement that timed out is not offered again for a while ──────────────────────
    //
    // ⛔ THE EXECUTOR GAVE UP ON A BRIDGE CELL AND THE PLANNER HANDED IT THE SAME CELL AGAIN. The
    // 21:27 run, after a respawn at (92,100,-14): "Path needs bridging: 1 block(s) at segment end",
    // "At the gap -- bridging without a physics leg", "Bridge place aborted (TIMEOUT) ... target=
    // 91,100,-18" every ten seconds for eight minutes. Two hundred ticks in range without a click
    // that lands is a fact about that cell from where the body can stand; the plan never learned
    // it. Baritone's PathExecutor cancels at cost+100 and re-plans with the failed edge priced
    // out; PillarTask remembers a refused column (G62). This is the same memory for any placed
    // cell: the executor records the refusal, and every place move the planner prices through
    // canPlace() sees COST_INF there for a minute.
    private static final java.util.Map<BlockPos, Long> refusedUntilMs = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REFUSAL_MS = 60_000L;
    /** Placements refused because that cell had just timed out. Read as placeRefused. */
    public static volatile int placeRefusedRecently;

    public static void refuseForAWhile(BlockPos pos) {
        if (pos == null) return;
        long now = System.currentTimeMillis();
        refusedUntilMs.entrySet().removeIf(e -> e.getValue() < now);
        refusedUntilMs.put(pos.toImmutable(), now + REFUSAL_MS);
    }

    public static boolean refusedRecently(BlockPos pos) {
        if (refusedUntilMs.isEmpty()) return false;
        Long until = refusedUntilMs.get(pos);
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            refusedUntilMs.remove(pos);
            return false;
        }
        placeRefusedRecently++;
        return true;
    }

    private static boolean inZone(BlockPos pos, int[] z) {
        return pos.getX() >= Math.min(z[0], z[3]) && pos.getX() <= Math.max(z[0], z[3])
            && pos.getY() >= Math.min(z[1], z[4]) && pos.getY() <= Math.max(z[1], z[4])
            && pos.getZ() >= Math.min(z[2], z[5]) && pos.getZ() <= Math.max(z[2], z[5]);
    }
}
