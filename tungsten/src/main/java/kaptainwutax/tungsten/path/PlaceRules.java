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
        // must be an empty/replaceable cell to place into
        if (world != null && !world.getBlockState(pos).isReplaceable()) return false;

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

    private static boolean inZone(BlockPos pos, int[] z) {
        return pos.getX() >= Math.min(z[0], z[3]) && pos.getX() <= Math.max(z[0], z[3])
            && pos.getY() >= Math.min(z[1], z[4]) && pos.getY() <= Math.max(z[1], z[4])
            && pos.getZ() >= Math.min(z[2], z[5]) && pos.getZ() <= Math.max(z[2], z[5]);
    }
}
