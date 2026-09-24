package kaptainwutax.tungsten.path;

import kaptainwutax.tungsten.TungstenConfig;
import kaptainwutax.tungsten.TungstenModDataContainer;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;

/**
 * Central "may we mine this block" policy. Consulted by the block-space
 * planner (tryPlanBreakThrough), the executor (tickBreaking) and the py4j
 * prediction API — one source of truth for every layer.
 *
 * Deny reasons, in order: breaking disabled, unbreakable (bedrock-class),
 * fluid behind, block entity (chests/spawners/furnaces — always valuable),
 * configured deny-list id, configured deny zone, the external hook
 * (altoclef's protection stack via canBreakHook).
 */
public final class BreakRules {

    private BreakRules() {}

    /** Breaks refused because the block holds lava back (above or beside it). */
    public static volatile int refusedNextToLava = 0;

    /** Lava directly above or beside {@code pos} (not below -- lava does not flow up). */
    public static boolean holdsLavaBack(WorldView world, BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return lavaAt(world, x, y + 1, z) || lavaAt(world, x + 1, y, z) || lavaAt(world, x - 1, y, z)
                || lavaAt(world, x, y, z + 1) || lavaAt(world, x, y, z - 1);
    }

    private static boolean lavaAt(WorldView world, int x, int y, int z) {
        return world.getFluidState(new BlockPos(x, y, z)).isIn(net.minecraft.registry.tag.FluidTags.LAVA);
    }

    public static boolean canBreak(WorldView world, BlockPos pos, BlockState state) {
        TungstenConfig cfg = TungstenConfig.get();
        if (!cfg.allowBreak) return false;
        if (state.isAir()) return true;
        if (state.getHardness(world, pos) < 0) return false;
        if (!world.getFluidState(pos).isEmpty()) return false;
        if (state.hasBlockEntity()) return false;
        // ⛔ NOT A BLOCK THAT IS HOLDING LAVA BACK -- baritone MovementHelper.avoidAdjacentBreaking
        // (G108 nether, 2026-09-24). Baritone refuses to break a block with liquid directly above it
        // or beside it (north/south/east/west; below is fine, liquid does not flow up), because the
        // liquid "will start flowing if you give it a path". The port copied that into
        // MovementHelperB.avoidBreaking, which only the ported Movement classes call -- the planners
        // that actually drive (FastPlanner's break moves, BlockSpacePathFinder, the altoclef drive)
        // ask THIS method, which never looked. Measured in the nether: the body STANDING still
        // (velocity ~0, wasOnGround true) under the replay executor, and lava arriving in its own
        // cell at its own height -- a planned break that opened the pool onto it. Lava only here:
        // tungsten treats water as traversable and swims it, and the obsidian flood needs the rest.
        if (holdsLavaBack(world, pos)) {
            refusedNextToLava++;
            return false;
        }

        if (!cfg.breakDenyBlocks.isEmpty()) {
            String id = Registries.BLOCK.getId(state.getBlock()).toString();
            if (cfg.breakDenyBlocks.contains(id)) return false;
        }
        for (int[] zone : cfg.breakDenyZones) {
            if (zone != null && zone.length >= 6 && inZone(pos, zone)) return false;
        }

        java.util.function.Predicate<BlockPos> hook = TungstenModDataContainer.canBreakHook;
        if (hook != null) {
            try {
                if (!hook.test(pos)) return false;
            } catch (Throwable ignored) {
                // protection hook failure must not unlock or lock mining paths
            }
        }
        return true;
    }

    private static boolean inZone(BlockPos pos, int[] z) {
        return pos.getX() >= Math.min(z[0], z[3]) && pos.getX() <= Math.max(z[0], z[3])
            && pos.getY() >= Math.min(z[1], z[4]) && pos.getY() <= Math.max(z[1], z[4])
            && pos.getZ() >= Math.min(z[2], z[5]) && pos.getZ() <= Math.max(z[2], z[5]);
    }
}
