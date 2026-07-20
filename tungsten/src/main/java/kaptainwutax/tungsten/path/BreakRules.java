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

    public static boolean canBreak(WorldView world, BlockPos pos, BlockState state) {
        TungstenConfig cfg = TungstenConfig.get();
        if (!cfg.allowBreak) return false;
        if (state.isAir()) return true;
        if (state.getHardness(world, pos) < 0) return false;
        if (!world.getFluidState(pos).isEmpty()) return false;
        if (state.hasBlockEntity()) return false;

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
